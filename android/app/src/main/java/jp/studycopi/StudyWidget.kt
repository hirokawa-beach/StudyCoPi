package jp.studycopi

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.text.SpannableString
import android.text.Spanned
import android.text.style.LocaleSpan
import android.widget.RemoteViews
import kotlinx.coroutines.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

data class WidgetContent(val date: String, val studied: String, val rows: List<String>, val remaining: Int)
fun widgetContent(data: StudyData, today: LocalDate = LocalDate.now()): WidgetContent {
    val plans = data.schedules.filter { it.date == today }
    val entries = plans.filterNot { it.finished }.map {
        dateTime(it.datetime).toLocalTime().toString().take(5) to "${dateTime(it.datetime).toLocalTime().toString().take(5)}  ${data.subject(it.subjectId).name}" + if (it.content.isBlank()) "" else "\n${it.content}"
    } + data.exams.filter { it.date == today.toString() }.map { (it.startTime.ifBlank { "00:00" }) to "${it.startTime.ifBlank { "時刻未定" }}  ${it.subject}（試験）" }
    val sorted = entries.sortedBy { it.first }.map { it.second }
    return WidgetContent(today.format(DateTimeFormatter.ofPattern("M月d日（E）", Locale.JAPANESE)),
        hoursText(plans.sumOf { it.actual }), sorted.take(3), (sorted.size - 3).coerceAtLeast(0))
}
class StudyWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) { refresh(context) }
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action in setOf(REFRESH, Intent.ACTION_DATE_CHANGED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED)) refresh(context)
    }
    private fun refresh(context: Context) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val data = (context.applicationContext as StudyApplication).repository.load()
                updateAll(context, data)
            } catch (error: Exception) {
                android.util.Log.e("StudyCoPi", "Could not read widget data", error)
                val manager = AppWidgetManager.getInstance(context)
                manager.getAppWidgetIds(ComponentName(context, StudyWidget::class.java)).forEach {
                    val views = views(context, WidgetContent("今日の学習", "—", listOf("アプリを開いてデータを確認してください"), 0))
                    manager.updateAppWidget(it, views)
                }
            } finally { pending.finish() }
        }
    }
    companion object {
        const val REFRESH = "jp.studycopi.WIDGET_REFRESH"
        private fun japanese(text: String): CharSequence = SpannableString(text).apply {
            if (isNotEmpty()) setSpan(LocaleSpan(Locale.JAPANESE), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        fun views(context: Context, content: WidgetContent): RemoteViews = RemoteViews(context.packageName, R.layout.study_widget).apply {
            setTextViewText(R.id.widget_date, japanese(content.date))
            setTextViewText(R.id.widget_label, japanese("今日の勉強時間 · 記録済み"))
            setTextViewText(R.id.widget_hours, japanese(content.studied))
            setTextViewText(R.id.widget_empty, japanese("今日の予定はありません（完了した予定はアプリで確認）"))
            setViewVisibility(R.id.widget_empty, if (content.rows.isEmpty()) View.VISIBLE else View.GONE)
            listOf(R.id.widget_row_1, R.id.widget_row_2, R.id.widget_row_3).forEachIndexed { index, id ->
                setTextViewText(id, japanese(content.rows.getOrNull(index).orEmpty()))
                setViewVisibility(id, if (index < content.rows.size) View.VISIBLE else View.GONE)
            }
            setTextViewText(R.id.widget_more, japanese(if (content.remaining > 0) "ほか${content.remaining}件 · アプリで見る ›" else "今日の学習を開く ›"))
            val open = PendingIntent.getActivity(context, 700, Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("openToday", true),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            setOnClickPendingIntent(R.id.widget_root, open)
            setOnClickPendingIntent(R.id.widget_refresh, PendingIntent.getBroadcast(context, 701,
                Intent(context, StudyWidget::class.java).setAction(REFRESH), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
        fun updateAll(context: Context, data: StudyData) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, StudyWidget::class.java))
            if (ids.isNotEmpty()) manager.updateAppWidget(ids, views(context, widgetContent(data)))
        }
    }
}

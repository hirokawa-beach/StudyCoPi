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
open class StudyWidget : AppWidgetProvider() {
    open val kind = WidgetKind.TODAY
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: android.os.Bundle) { refresh(context) }
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
                providers.forEach { (_, provider) -> manager.getAppWidgetIds(ComponentName(context, provider)).forEach {
                    val views = views(context, WidgetContent("今日の学習", "—", listOf("アプリを開いてデータを確認してください"), 0))
                    manager.updateAppWidget(it, views)
                } }
            } finally { pending?.finish() }
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
        val providers = listOf(WidgetKind.TODAY to StudyWidget::class.java, WidgetKind.PLANS to TodayPlansWidget::class.java,
            WidgetKind.HOURS to TodayHoursWidget::class.java, WidgetKind.TOTAL to TotalHoursWidget::class.java,
            WidgetKind.ALARM to NextAlarmWidget::class.java, WidgetKind.NEXT to NextPlanWidget::class.java)
        fun presentationViews(context: Context, kind: WidgetKind, content: WidgetPresentation, height: Int): RemoteViews {
            val compact = height < 280
            val rows = if (height < 130 || compact && kind in listOf(WidgetKind.ALARM, WidgetKind.NEXT)) emptyList() else content.rows.take(if (compact && kind == WidgetKind.PLANS) 2 else if (compact) 1 else 3)
            return RemoteViews(context.packageName, if (compact) R.layout.widget_compact else R.layout.study_widget).apply {
                setTextViewText(R.id.widget_date, japanese(content.title))
                setTextViewText(R.id.widget_label, japanese(content.label))
                setTextViewText(R.id.widget_hours, japanese(content.value))
                setTextViewText(R.id.widget_empty, japanese(content.empty))
                setViewVisibility(R.id.widget_empty, if (!compact && rows.isEmpty() && content.empty.isNotEmpty()) View.VISIBLE else View.GONE)
                listOf(R.id.widget_row_1, R.id.widget_row_2, R.id.widget_row_3).forEachIndexed { index, id ->
                    setTextViewText(id, japanese(rows.getOrNull(index).orEmpty()))
                    if (compact) setInt(id, "setMaxLines", 1)
                    setViewVisibility(id, if (index < rows.size) View.VISIBLE else View.GONE)
                }
                // Keep planned and actual totals visible even in the smaller layout.
                if (kind == WidgetKind.TOTAL && compact) setTextViewText(R.id.widget_label, japanese(content.rows.first().replace("累積予定時間  ", "予定 ")))
                if (kind == WidgetKind.TOTAL && compact) setViewVisibility(R.id.widget_row_1, View.GONE)
                setTextViewText(R.id.widget_more, japanese(if (content.rows.size > rows.size && kind in listOf(WidgetKind.TODAY, WidgetKind.PLANS)) "ほかの予定はタップして確認 ›" else content.more))
                setViewVisibility(R.id.widget_more, if (height < 220) View.GONE else View.VISIBLE)
                if (compact && kind == WidgetKind.PLANS) {
                    setViewVisibility(R.id.widget_hours, View.GONE)
                    setTextViewText(R.id.widget_date, japanese("今日の予定 · ${content.value}"))
                }
                if (compact && kind == WidgetKind.TODAY) setViewVisibility(R.id.widget_label, View.GONE)
                if (height < 130) {
                    setViewVisibility(R.id.widget_refresh, View.GONE)
                    setInt(R.id.widget_date, "setMaxLines", 1)
                    setViewVisibility(R.id.widget_label, View.GONE)
                    setTextViewTextSize(R.id.widget_date, android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextViewTextSize(R.id.widget_hours, android.util.TypedValue.COMPLEX_UNIT_SP, 20f)
                    if (kind == WidgetKind.NEXT) setTextViewText(R.id.widget_date, japanese(content.label))
                }
                val open = PendingIntent.getActivity(context, 710 + kind.ordinal, Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("openScreen", kind.route),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                setOnClickPendingIntent(R.id.widget_root, open)
                setOnClickPendingIntent(R.id.widget_refresh, PendingIntent.getBroadcast(context, 720 + kind.ordinal,
                    Intent(context, providers.first { it.first == kind }.second).setAction(REFRESH), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            }
        }
        fun adaptiveViews(context: Context, kind: WidgetKind, data: StudyData, options: android.os.Bundle): RemoteViews {
            val content = widgetPresentation(data, kind)
            if (android.os.Build.VERSION.SDK_INT >= 31) return RemoteViews(mapOf(
                android.util.SizeF(110f, 70f) to presentationViews(context, kind, content, 70),
                android.util.SizeF(110f, 140f) to presentationViews(context, kind, content, 140),
                android.util.SizeF(240f, 140f) to presentationViews(context, kind, content, 140),
                android.util.SizeF(240f, 280f) to presentationViews(context, kind, content, 280)))
            return presentationViews(context, kind, content, options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, if (kind == WidgetKind.TODAY) 320 else 170))
        }
        fun updateAll(context: Context, data: StudyData) {
            val manager = AppWidgetManager.getInstance(context)
            providers.forEach { (kind, provider) -> manager.getAppWidgetIds(ComponentName(context, provider)).forEach { id ->
                manager.updateAppWidget(id, adaptiveViews(context, kind, data, manager.getAppWidgetOptions(id)))
            } }
        }
    }
}

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
import android.text.style.StyleSpan
import android.text.style.ForegroundColorSpan
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
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: android.os.Bundle) { refresh(context, id, android.os.Bundle(options)) }
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) { refresh(context) }
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action in setOf(REFRESH, Intent.ACTION_DATE_CHANGED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED)) refresh(context)
    }
    private fun refresh(context: Context, id: Int? = null, options: android.os.Bundle? = null) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val data = (context.applicationContext as StudyApplication).repository.load()
                if (id == null) updateAll(context, data)
                else AppWidgetManager.getInstance(context).updateAppWidget(id, adaptiveViews(context, kind, data, options ?: android.os.Bundle()))
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
        private fun planRow(context: Context, text: String): CharSequence = SpannableString(japanese(text)).apply {
            if (Regex("^\\d{2}:\\d{2}").containsMatchIn(text)) {
                setSpan(StyleSpan(android.graphics.Typeface.BOLD), 0, 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(ForegroundColorSpan(context.getColor(R.color.widget_chart)), 0, 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
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
        fun presentationViews(context: Context, kind: WidgetKind, content: WidgetPresentation, height: Int, width: Int = 170): RemoteViews {
            val scale = context.resources.configuration.fontScale.coerceAtLeast(1f)
            val micro = height < 100
            val microWide = micro && width >= 280
            val padding = if (microWide) 8 else if (micro) 4 else if (height < 200) 12 else 16
            fun textHeight(sp: Int): Int {
                val paint = android.text.TextPaint().apply {
                    typeface = context.resources.getFont(R.font.widget_sans)
                    textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, sp.toFloat(), context.resources.displayMetrics)
                }
                return kotlin.math.ceil((paint.fontMetrics.descent - paint.fontMetrics.ascent) / context.resources.displayMetrics.density).toInt()
            }
            val titleHeight = maxOf(if (micro) 18 else 20, textHeight(if (micro) 12 else 14))
            val valueHeight = if (microWide) (height - padding * 2).coerceIn(48, 64) else if (micro) if (scale > 1.1f) (height - padding * 2 - titleHeight).coerceIn(28, 40) else 28 else if (height / scale < 170) 36 else 56
            val visual = content.visual
            val ring = !micro && visual != null && kind != WidgetKind.HOURS && width >= 220 && height / scale >= 180
            val bars = !micro && visual != null && (kind == WidgetKind.HOURS && height / scale >= 125 || kind == WidgetKind.TOTAL && height / scale >= 220)
            val footer = !micro && height / scale >= 220
            val progress = !micro && visual != null && kind != WidgetKind.HOURS && !ring && !bars
            val label = when (kind) {
                WidgetKind.TODAY -> "予定 ${hoursText(visual?.planned ?: 0.0)} · 未完了${content.entries.size}件"
                WidgetKind.HOURS -> if (micro) "7日計 ${hoursText(visual?.days?.sumOf { it.second } ?: 0.0)}" else ""
                WidgetKind.TOTAL -> "予定 ${hoursText(visual?.planned ?: 0.0)}" + (visual?.ratio?.let { " · ${(it * 100).toInt()}%" } ?: "")
                else -> content.label
            }
            val labelVisible = label.isNotEmpty() && !(micro && !microWide && scale > 1.1f)
            val labelHeight = if (labelVisible) if (micro) 16 else textHeight(12) + 4 else 0
            val footerHeight = if (footer) textHeight(12) + 4 else 0
            val bodyHeight = (height - padding * 2 - maxOf(titleHeight, if (footer) 40 else 0) - labelHeight - maxOf(valueHeight, if (ring) 64 else 0) - footerHeight - if (progress) 12 else 0).coerceAtLeast(0)
            val rowSp = if (height / scale >= 200) 16 else 14
            val lineHeight = kotlin.math.ceil(textHeight(rowSp) * 1.2).toInt() + 8
            val lead = content.entries.firstOrNull()
            var value = content.value
            val displayed = mutableListOf<String>()
            if (kind == WidgetKind.PLANS && lead != null) {
                value = if (micro && !microWide) "${lead.time} ${lead.subject}" else lead.time
                if (!micro && bodyHeight >= lineHeight) {
                    displayed += if (bodyHeight >= lineHeight * 2) lead.subject else "${lead.subject} · ${lead.detail}"
                    if (bodyHeight >= lineHeight * 2 && lead.detail.isNotBlank()) displayed += lead.detail
                    if (bodyHeight >= lineHeight * 4 && content.entries.size > 1) {
                        val next = content.entries[1]; displayed += "${next.time}  ${next.subject}\n${next.detail}"
                    }
                }
            } else if (!micro && !bars) {
                val candidates = if (kind == WidgetKind.TODAY) content.entries.map { "${it.time}  ${it.subject}\n${it.detail}" } else content.rows
                if (kind != WidgetKind.TOTAL) displayed += candidates.take((bodyHeight / (lineHeight * if (height / scale >= 180) 2 else 1)).coerceIn(0, 3))
            }
            val title = when {
                kind == WidgetKind.PLANS && width >= 160 -> "今日の予定 · ${content.value}"
                kind == WidgetKind.HOURS && width < 150 -> "今日の勉強"
                kind == WidgetKind.TOTAL && width < 150 -> "累積の勉強"
                else -> kind.title.let { if (kind == WidgetKind.TOTAL) "累積勉強時間" else it }
            }
            val layout = if (microWide) R.layout.widget_minimal_wide else if (micro) R.layout.widget_minimal else R.layout.widget_responsive
            return RemoteViews(context.packageName, layout).apply {
                val horizontalPadding = if (micro) 12 else padding
                setViewPadding(R.id.widget_root, dp(context, horizontalPadding), dp(context, padding), dp(context, horizontalPadding), dp(context, padding))
                setTextViewText(R.id.widget_date, japanese(title))
                setInt(R.id.widget_date, "setHeight", dp(context, titleHeight))
                val microLabel = when {
                    microWide && kind == WidgetKind.PLANS && lead != null -> "${lead.subject}\n${lead.detail}"
                    microWide && kind in listOf(WidgetKind.NEXT, WidgetKind.ALARM) -> label + content.rows.firstOrNull()?.let { "\n$it" }.orEmpty()
                    micro && kind == WidgetKind.PLANS && lead != null -> lead.detail
                    else -> label
                }
                setTextViewText(R.id.widget_label, japanese(microLabel))
                if (microWide) {
                    val labelSp = if (scale > 1.1f) 10 else 12
                    setTextViewTextSize(R.id.widget_label, android.util.TypedValue.COMPLEX_UNIT_SP, labelSp.toFloat())
                    setInt(R.id.widget_label, "setMaxLines", if (height - padding * 2 - titleHeight >= textHeight(labelSp) * 2) 2 else 1)
                }
                setViewVisibility(R.id.widget_label, if (labelVisible) View.VISIBLE else View.GONE)
                setTextViewText(R.id.widget_hours, japanese(value))
                setInt(R.id.widget_hours, "setHeight", dp(context, valueHeight))
                setContentDescription(R.id.widget_hours, japanese(visual?.description ?: "$title、${content.label}、$value"))
                setViewVisibility(R.id.widget_refresh, if (footer) View.VISIBLE else View.GONE)
                setTextViewText(R.id.widget_empty, japanese(content.empty))
                setTextViewTextSize(R.id.widget_empty, android.util.TypedValue.COMPLEX_UNIT_SP, if (height / scale < 180) 12f else 14f)
                setInt(R.id.widget_empty, "setMaxLines", if (height / scale < 180) 1 else 2)
                setViewVisibility(R.id.widget_empty, if (!micro && kind == WidgetKind.PLANS && lead == null && bodyHeight >= textHeight(12) + 8) View.VISIBLE else View.GONE)
                listOf(R.id.widget_row_1, R.id.widget_row_2, R.id.widget_row_3).forEachIndexed { index, id ->
                    setTextViewText(id, planRow(context, displayed.getOrNull(index).orEmpty()))
                    setTextViewTextSize(id, android.util.TypedValue.COMPLEX_UNIT_SP, rowSp.toFloat())
                    setInt(id, "setMaxLines", if (kind == WidgetKind.PLANS && index < 2 || height / scale < 180) 1 else 2)
                    setViewVisibility(id, if (index < displayed.size) View.VISIBLE else View.GONE)
                }
                if (kind == WidgetKind.TOTAL) setTextViewText(R.id.widget_row_1, japanese(content.rows.firstOrNull().orEmpty()))
                if (ring && visual != null) {
                    setImageViewBitmap(R.id.widget_ring, WidgetCharts.ring(context, visual, 64))
                    setContentDescription(R.id.widget_ring, japanese(visual.description))
                    setViewVisibility(R.id.widget_ring, View.VISIBLE)
                }
                if (progress && visual != null) {
                    setProgressBar(R.id.widget_progress, 100, ((visual.ratio ?: 0.0) * 100).toInt().coerceIn(0, 100), false)
                    setViewVisibility(R.id.widget_progress, View.VISIBLE)
                    setContentDescription(R.id.widget_progress, japanese(visual.description))
                }
                if (bars && visual != null) {
                    val captionHeight = textHeight(12) + 4
                    val graphHeight = (bodyHeight - captionHeight).coerceIn(32, 200)
                    setViewVisibility(R.id.widget_chart_label, View.VISIBLE)
                    setTextViewText(R.id.widget_chart_label, japanese(if (height < 180) "直近7日" else "直近7日 · 最大 ${hoursText(visual.days.maxOf { it.second })}"))
                    setImageViewBitmap(R.id.widget_chart, WidgetCharts.bars(context, visual, (width - padding * 2).coerceAtLeast(60), graphHeight))
                    setContentDescription(R.id.widget_chart, japanese("直近7日の勉強時間。" + visual.days.joinToString("、") { "${it.first.monthValue}/${it.first.dayOfMonth} ${hoursText(it.second)}" }))
                    setViewVisibility(R.id.widget_chart, View.VISIBLE)
                    setViewVisibility(R.id.widget_spacer, View.GONE)
                }
                val more = if (kind == WidgetKind.PLANS && lead != null) "未完了${content.entries.size}件 · 予定を開く ›" else content.more
                setTextViewText(R.id.widget_more, japanese(more))
                setViewVisibility(R.id.widget_more, if (footer) View.VISIBLE else View.GONE)
                val open = PendingIntent.getActivity(context, 710 + kind.ordinal, Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("openScreen", kind.route),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                setOnClickPendingIntent(R.id.widget_root, open)
                setOnClickPendingIntent(R.id.widget_refresh, PendingIntent.getBroadcast(context, 720 + kind.ordinal,
                    Intent(context, providers.first { it.first == kind }.second).setAction(REFRESH), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            }
        }
        private fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
        fun adaptiveViews(context: Context, kind: WidgetKind, data: StudyData, options: android.os.Bundle): RemoteViews {
            val content = widgetPresentation(data, kind)
            val sizes = widgetSizes(options, kind)
            fun render(size: android.util.SizeF) = presentationViews(context, kind, content, size.height.toInt(), size.width.toInt())
            if (android.os.Build.VERSION.SDK_INT >= 31) return RemoteViews(sizes.associateWith(::render))
            return if (sizes.size == 1) render(sizes.first()) else RemoteViews(render(sizes.last()), render(sizes.first()))
        }
        fun updateAll(context: Context, data: StudyData) {
            val manager = AppWidgetManager.getInstance(context)
            providers.forEach { (kind, provider) -> manager.getAppWidgetIds(ComponentName(context, provider)).forEach { id ->
                manager.updateAppWidget(id, adaptiveViews(context, kind, data, manager.getAppWidgetOptions(id)))
            } }
        }
    }
}

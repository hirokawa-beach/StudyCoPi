package jp.studycopi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

data class WidgetVisual(val actual: Double, val planned: Double, val days: List<Pair<LocalDate, Double>>) {
    val ratio: Double? get() = if (planned > 0) actual / planned else null
    val description: String get() = "実績 ${hoursText(actual)}、予定 ${hoursText(planned)}" +
        (ratio?.let { "、予定に対して${(it * 100).roundToInt()}%" } ?: "、予定時間は未設定")
}

fun widgetVisual(data: StudyData, kind: WidgetKind, today: LocalDate): WidgetVisual? {
    if (kind !in listOf(WidgetKind.TODAY, WidgetKind.HOURS, WidgetKind.TOTAL)) return null
    val records = if (kind == WidgetKind.TOTAL) data.schedules else data.schedules.filter { it.date == today }
    val daily = data.schedules.filter { it.date in today.minusDays(6)..today }.groupBy { it.date }
    return WidgetVisual(records.sumOf { it.actual }, records.sumOf { it.duration }, (6 downTo 0).map { offset ->
        val date = today.minusDays(offset.toLong()); date to daily[date].orEmpty().sumOf { it.actual }
    })
}

/** Data-derived graphics for RemoteViews, small enough for the widget binder budget. */
object WidgetCharts {
    private fun color(context: Context, id: Int) = context.getColor(id)
    fun ring(context: Context, visual: WidgetVisual): Bitmap {
        val bitmap = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 11f; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        val bounds = RectF(12f, 12f, 108f, 108f)
        paint.color = color(context, R.color.widget_track); canvas.drawArc(bounds, 0f, 360f, false, paint)
        paint.color = color(context, R.color.widget_chart)
        val ratio = visual.ratio
        if (ratio != null && ratio > 0) canvas.drawArc(bounds, -90f, (ratio.coerceAtMost(1.0) * 360).toFloat(), false, paint)
        paint.style = Paint.Style.FILL; paint.textAlign = Paint.Align.CENTER
        paint.typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
        paint.color = color(context, R.color.widget_text); paint.textSize = if (ratio == null) 24f else 32f
        canvas.drawText(ratio?.let { "${(it * 100).roundToInt()}%" } ?: "未設定", 60f, 71f, paint)
        return bitmap
    }
    fun bars(context: Context, visual: WidgetVisual, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val maximum = visual.days.maxOfOrNull { it.second }?.coerceAtLeast(1.0) ?: 1.0
        val baseline = height - 17f
        val barHeight = baseline - 4f
        val columnWidth = width / 7f
        val barWidth = columnWidth * .55f
        visual.days.forEachIndexed { index, (date, hours) ->
            val x = index * columnWidth + (columnWidth - barWidth) / 2
            paint.color = color(context, R.color.widget_track)
            canvas.drawRoundRect(x, 4f, x + barWidth, baseline, 3f, 3f, paint)
            paint.color = color(context, if (index == 6) R.color.widget_chart else R.color.widget_chart_past)
            val filled = (hours / maximum * barHeight).toFloat()
            if (filled > 0) canvas.drawRoundRect(x, baseline - filled, x + barWidth, baseline, 3f, 3f, paint)
            paint.textSize = 11f; paint.textAlign = Paint.Align.CENTER
            paint.typeface = android.graphics.Typeface.create("sans-serif", if (index == 6) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            paint.color = color(context, if (index == 6) R.color.widget_chart else R.color.widget_secondary)
            canvas.drawText(date.format(DateTimeFormatter.ofPattern("E", Locale.JAPANESE)), x + barWidth / 2, height - 5f, paint)
        }
        return bitmap
    }
}

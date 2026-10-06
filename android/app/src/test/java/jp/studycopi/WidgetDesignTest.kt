package jp.studycopi

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = StudyApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetDesignTest {
    private val now = LocalDateTime.of(2026, 10, 7, 12, 0)
    private fun fixture(): StudyData {
        val hours = listOf(1.25, .75, 0.0, 2.5, 1.0, .5, 1.5)
        return StudyData(schedules = hours.mapIndexed { index, actual ->
            Schedule("record-$index", if (index % 2 == 0) "s1" else "s2", now.minusDays(6L - index).withHour(9).toString(), 2.0,
                "問題集 p.24〜28", status = if (index == 6) "partial" else "done", actualDuration = actual)
        } + listOf(Schedule("next", "s1", now.withHour(17).withMinute(30).toString(), 1.0, "二次関数の復習")),
            wakeAlarms = listOf(WakeAlarm("morning", "学校の日", "07:30", days = setOf(1,2,3,4,5)).arm(now.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli())))
    }
    private fun render(kind: WidgetKind, width: Int, height: Int): View {
        val app = ApplicationProvider.getApplicationContext<StudyApplication>()
        val content = widgetPresentation(fixture(), kind, now)
        val root = StudyWidget.presentationViews(app, kind, content, height, width).apply(app, LinearLayout(app))
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, width, height)
        for (id in listOf(R.id.widget_hours, R.id.widget_row_1, R.id.widget_row_2, R.id.widget_more, R.id.widget_chart)) {
            val view = root.findViewById<View>(id)
            if (view.visibility == View.VISIBLE) {
                var top = view.top; var parent = view.parent as? View
                while (parent != null && parent !== root) { top += parent.top; parent = parent.parent as? View }
                assertTrue("$kind/$width/$height clipped view $id at $top height ${view.height}", top + view.height <= height - root.paddingBottom)
            }
        }
        if (kind == WidgetKind.HOURS && height >= 130 || kind == WidgetKind.TOTAL && height >= 130 && (width < 220 || height >= 170)) assertEquals(View.VISIBLE, root.findViewById<View>(R.id.widget_chart).visibility)
        if (kind == WidgetKind.TODAY && width >= 220 && height >= 130) assertEquals(View.VISIBLE, root.findViewById<View>(R.id.widget_ring).visibility)
        if (kind == WidgetKind.TOTAL) assertTrue(root.findViewById<TextView>(R.id.widget_label).text.toString().contains("15時間") || root.findViewById<TextView>(R.id.widget_row_1).text.toString().contains("15時間"))
        for (id in listOf(R.id.widget_date, R.id.widget_label, R.id.widget_hours, R.id.widget_row_1, R.id.widget_row_2, R.id.widget_more)) {
            val text = root.findViewById<TextView>(id)
            if (text.visibility == View.VISIBLE && text.layout != null) assertTrue("$kind/$width/$height text clipped at $id", text.layout.height + text.compoundPaddingTop + text.compoundPaddingBottom <= text.height)
        }
        return root
    }
    @Test fun lightWidgetsShowRealChartsAndKeepEveryVisibleElementInside() { board(false) }
    @Test @Config(qualifiers = "night") fun darkWidgetsShowRealChartsAndKeepEveryVisibleElementInside() { board(true) }
    private fun board(dark: Boolean) {
        val bitmap = Bitmap.createBitmap(770, 860, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap); canvas.drawColor(if (dark) Color.rgb(7, 12, 21) else Color.rgb(231, 237, 245))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (dark) Color.WHITE else Color.rgb(27, 35, 49); textSize = 24f; typeface = android.graphics.Typeface.DEFAULT_BOLD }
        canvas.drawText("StudyCoPi · ホーム画面ウィジェット", 24f, 42f, paint)
        val cases = listOf(
            Triple(WidgetKind.TODAY, 350, 320) to (24 to 70), Triple(WidgetKind.PLANS, 350, 320) to (396 to 70),
            Triple(WidgetKind.HOURS, 170, 140) to (24 to 414), Triple(WidgetKind.TOTAL, 170, 140) to (204 to 414),
            Triple(WidgetKind.ALARM, 350, 140) to (396 to 414), Triple(WidgetKind.TOTAL, 350, 280) to (24 to 578),
            Triple(WidgetKind.NEXT, 350, 140) to (396 to 578))
        for ((spec, position) in cases) {
            val (kind, width, height) = spec
            val root = render(kind, width, height)
            canvas.save(); canvas.translate(position.first.toFloat(), position.second.toFloat()); root.draw(canvas); canvas.restore()
        }
        val directory = File(requireNotNull(System.getProperty("studycopi.artifacts"))); directory.mkdirs()
        File(directory, "widgets-redesign-${if (dark) "dark" else "light"}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        for (kind in WidgetKind.entries) render(kind, if (kind in listOf(WidgetKind.HOURS, WidgetKind.TOTAL, WidgetKind.ALARM)) 170 else 350, 280)
        for (kind in WidgetKind.entries) render(kind, 350, 140)
    }
    @Test fun chartsHaveCorrectTotalsAndRemoteViewsStayBelowBinderBudget() {
        val app = ApplicationProvider.getApplicationContext<StudyApplication>()
        val visual = requireNotNull(widgetVisual(fixture(), WidgetKind.TODAY, now.toLocalDate()))
        assertEquals(1.5, visual.actual, 0.0); assertEquals(3.0, visual.planned, 0.0); assertEquals(.5, visual.ratio!!, 0.0)
        assertEquals(7, visual.days.size)
        assertEquals(0.0, visual.days[2].second, 0.0)
        assertNull(widgetVisual(StudyData(), WidgetKind.HOURS, now.toLocalDate())!!.ratio)
        val parcel = android.os.Parcel.obtain()
        try {
            StudyWidget.adaptiveViews(app, WidgetKind.TOTAL, fixture(), android.os.Bundle()).writeToParcel(parcel, 0)
            assertTrue("adaptive widget exceeds binder budget: ${parcel.dataSize()}", parcel.dataSize() < 800_000)
        } finally { parcel.recycle() }
    }
}

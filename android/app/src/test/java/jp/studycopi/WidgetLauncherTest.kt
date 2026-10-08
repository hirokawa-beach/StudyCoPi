package jp.studycopi

import android.appwidget.AppWidgetManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.util.SizeF
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RemoteViews
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-night-xxhdpi", application = StudyApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetLauncherTest {
    private val now = LocalDateTime.of(2026, 10, 8, 18, 0)
    private val app get() = ApplicationProvider.getApplicationContext<StudyApplication>()
    private fun data() = StudyData(subjects = listOf(Subject("rik", "理数K")), schedules = listOf(
        Schedule("next", "rik", now.withHour(20).toString(), 1.0, "二次関数の復習")) +
        (0..6).map { index -> Schedule("record-$index", "rik", now.minusDays(6L - index).withHour(9).toString(), 2.0,
            status = "done", actualDuration = if (index == 6) .55 else listOf(0.0, 1.0, 2.5, 1.5, 3.0, 2.0)[index]) })
    private fun render(kind: WidgetKind, width: Int, height: Int, studyData: StudyData = data()): View {
        val root = StudyWidget.presentationViews(app, kind, widgetPresentation(studyData, kind, now), height, width).apply(app, LinearLayout(app))
        val density = app.resources.displayMetrics.density
        val w = (width * density).toInt(); val h = (height * density).toInt()
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)); root.layout(0, 0, w, h)
        for (id in listOf(R.id.widget_date, R.id.widget_label, R.id.widget_hours, R.id.widget_empty, R.id.widget_row_1, R.id.widget_row_2, R.id.widget_row_3, R.id.widget_chart_label, R.id.widget_more)) {
            val view = root.findViewById<TextView>(id)
            if (view.visibility != View.VISIBLE) continue
            var top = view.top; var parent = view.parent as? View
            while (parent != null && parent !== root) { top += parent.top; parent = parent.parent as? View }
            assertTrue("$kind/$width/$height/$id outside host", top + view.height <= h - root.paddingBottom)
            assertTrue("$kind/$width/$height/$id clipped text: ${view.layout.height}+${view.compoundPaddingTop}+${view.compoundPaddingBottom} > ${view.height}", view.layout.height + view.compoundPaddingTop + view.compoundPaddingBottom <= view.height)
        }
        return root
    }
    @Test fun actualLauncherSizesAndThreeTimesPixelDensityStaySharp() {
        val sizes = arrayListOf(SizeF(358f, 190f), SizeF(210f, 190f), SizeF(358f, 84f))
        val options = Bundle().apply { putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, sizes) }
        assertEquals(sizes, widgetSizes(options, WidgetKind.HOURS))
        val remote = StudyWidget.adaptiveViews(app, WidgetKind.HOURS, data(), options)
        val chosen = RemoteViews::class.java.getMethod("getRemoteViewsToApply", Context::class.java, SizeF::class.java).invoke(remote, app, sizes[1]) as RemoteViews
        val root = chosen.apply(app, LinearLayout(app))
        val image = root.findViewById<ImageView>(R.id.widget_chart)
        val bitmap = (image.drawable as BitmapDrawable).bitmap
        assertEquals((210 - 24) * 3, bitmap.width)
        assertTrue("graph must render at device pixel density", bitmap.height > 120)
        assertEquals(ImageView.ScaleType.FIT_CENTER, image.scaleType)
        assertEquals("直近7日", root.findViewById<TextView>(R.id.widget_chart_label).text.toString().take(4))
    }
    @Test fun screenshotSizesAndSmallWidgetsRetainTheirMeaning() {
        for (kind in WidgetKind.entries) for ((width, height) in listOf(110 to 70, 170 to 84, 210 to 140, 210 to 190, 358 to 84, 358 to 190, 358 to 280)) {
            val root = render(kind, width, height)
            val title = root.findViewById<TextView>(R.id.widget_date).text.toString()
            assertTrue("$kind lost its title", title.isNotBlank())
            if (kind == WidgetKind.NEXT) assertEquals("次の学習予定", title)
            if (kind == WidgetKind.PLANS) assertTrue(title.contains("今日の予定"))
        }
        board()
    }
    @Test fun largeFontKeepsEssentialLabelsAndTimesVisible() {
        RuntimeEnvironment.setFontScale(1.3f)
        for (kind in WidgetKind.entries) for ((width, height) in listOf(170 to 70, 210 to 140, 358 to 70, 358 to 84, 358 to 190)) render(kind, width, height)
    }
    @Test fun emptySchedulesAndZeroHoursRemainClear() {
        for (kind in WidgetKind.entries) for ((width, height) in listOf(110 to 70, 210 to 140, 358 to 190)) render(kind, width, height, StudyData())
    }
    @Test @Config(sdk = [26], qualifiers = "w360dp-h800dp-night-xxhdpi") fun oldLaunchersUsePortraitAndLandscapeBounds() {
        val options = Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 210); putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 358)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 84); putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 190)
        }
        assertEquals(listOf(SizeF(210f, 190f), SizeF(358f, 84f)), widgetSizes(options, WidgetKind.HOURS))
        assertNotNull(StudyWidget.adaptiveViews(app, WidgetKind.HOURS, data(), options).apply(app, LinearLayout(app)))
    }
    private fun board() {
        val bitmap = Bitmap.createBitmap(1080, 1590, Bitmap.Config.ARGB_8888); val canvas = Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.rgb(7, 12, 21))
        for ((kind, width, height, y) in listOf(
            listOf(WidgetKind.PLANS, 358, 190, 0), listOf(WidgetKind.HOURS, 210, 190, 600), listOf(WidgetKind.NEXT, 358, 84, 1200))) {
            val root = render(kind as WidgetKind, width as Int, height as Int)
            canvas.save(); canvas.translate(3f, (y as Int).toFloat()); root.draw(canvas); canvas.restore()
        }
        val path = File(requireNotNull(System.getProperty("studycopi.artifacts")), "widgets-launcher-density3.png")
        path.parentFile.mkdirs(); path.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

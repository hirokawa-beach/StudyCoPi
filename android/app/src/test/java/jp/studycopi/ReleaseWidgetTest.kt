package jp.studycopi

import android.app.NotificationManager
import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35], application = StudyApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReleaseWidgetTest {
    private val now = LocalDateTime.of(2026, 10, 4, 12, 0)
    private fun data() = StudyData(schedules = listOf(
        Schedule("today", "s1", now.plusHours(1).toString(), 1.0, "問題集 p.12"),
        Schedule("done", "s1", now.minusHours(1).toString(), 1.0, status = "done"),
        Schedule("old", "s2", now.minusDays(1).toString(), 2.0, actualDuration = .5, status = "partial")),
        wakeAlarms = listOf(WakeAlarm(id = "alarm", label = "学校の日", time = "07:30", days = setOf(1,2,3,4,5)).arm(now.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli())))
    @Test fun eachWidgetShowsItsOwnDataAndCompactAndLargeLayoutsRemainInsideBounds() {
        val app = ApplicationProvider.getApplicationContext<StudyApplication>()
        val data = data()
        assertEquals("1時間", widgetPresentation(data, WidgetKind.HOURS, now).value)
        assertEquals("1時間30分", widgetPresentation(data, WidgetKind.TOTAL, now).value)
        assertEquals(listOf("累積予定時間  4時間"), widgetPresentation(data, WidgetKind.TOTAL, now).rows)
        assertEquals("07:30", widgetPresentation(data, WidgetKind.ALARM, now).value)
        assertEquals("13:00", widgetPresentation(data, WidgetKind.NEXT, now).value)
        for (kind in WidgetKind.entries) for (height in if (kind in listOf(WidgetKind.HOURS, WidgetKind.ALARM, WidgetKind.NEXT)) listOf(70,140,320) else listOf(140,320)) {
            val width = if (kind in listOf(WidgetKind.HOURS, WidgetKind.ALARM, WidgetKind.TOTAL)) 170 else 350
            val content = widgetPresentation(data, kind, now)
            val root = StudyWidget.presentationViews(app, kind, content, height).apply(app, LinearLayout(app))
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            root.layout(0,0,width,height)
            val metric = root.findViewById<TextView>(R.id.widget_hours)
            if (metric.visibility == View.VISIBLE) { assertTrue("$kind/$height metric clipped", metric.bottom <= height - root.paddingBottom); assertEquals(content.value, metric.text.toString()) }
            if (kind == WidgetKind.TOTAL) assertTrue(root.findViewById<TextView>(if (height < 280) R.id.widget_label else R.id.widget_row_1).text.toString().contains("4時間"))
            if (height == 140 && kind == WidgetKind.PLANS) assertTrue(root.findViewById<TextView>(R.id.widget_row_1).text.toString().contains("数学"))
            val bitmap = android.graphics.Bitmap.createBitmap(width,height,android.graphics.Bitmap.Config.ARGB_8888)
            root.draw(android.graphics.Canvas(bitmap))
            val file = File(requireNotNull(System.getProperty("studycopi.artifacts")), "widget-${kind.name.lowercase()}-$height-${android.os.Build.VERSION.SDK_INT}.png")
            file.parentFile?.mkdirs(); file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
            root.performClick()
            assertEquals(kind.route, shadowOf(app).nextStartedActivity.getStringExtra("openScreen"))
        }
        assertEquals(6, StudyWidget.providers.size)
        for ((_, provider) in StudyWidget.providers) assertNotNull(app.packageManager.getReceiverInfo(android.content.ComponentName(app, provider), 0))
        val options = Bundle().apply { putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 140) }
        assertNotNull(StudyWidget.adaptiveViews(app, WidgetKind.PLANS, data, options).apply(app, LinearLayout(app)))
    }
    @Test fun scheduledFocusNotificationNeverStartsATimerAndRejectsStaleEditedPlans() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<StudyApplication>()
        val plan = Schedule("due", "s1", LocalDateTime.now().minusSeconds(1).toString(), 1.0)
        app.repository.restore(StudyData(schedules = listOf(plan)))
        app.repository.update { it.copy(preferences = it.preferences.copy(scheduledFocus = true)) }
        val data = app.repository.data.value
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val intent = android.content.Intent().putExtra("scheduleId", plan.id).putExtra("expected", plan.startWall())
        ScheduledFocus.notify(app, intent, data, plan.startWall())
        assertNull(app.repository.data.value.timer)
        assertEquals(0.0, app.repository.data.value.schedules.single().actual, 0.0)
        val notifications = shadowOf(app.getSystemService(NotificationManager::class.java))
        assertNotNull(notifications.getNotification("focus", plan.id.hashCode()))
        ScheduledFocus.reconcile(app, data)
        assertNotNull("notification must survive alarm reconciliation", notifications.getNotification("focus", plan.id.hashCode()))
        assertTrue(ScheduledFocus.claim(app, plan)); assertFalse(ScheduledFocus.claim(app, plan))
        assertNull(notifications.getNotification("focus", plan.id.hashCode()))
        val moved = plan.copy(datetime = LocalDateTime.now().plusHours(1).toString())
        ScheduledFocus.notify(app, intent, data.copy(schedules = listOf(moved)), plan.startWall())
        assertNull(notifications.getNotification("focus", plan.id.hashCode()))
    }
}

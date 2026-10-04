package jp.studycopi

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35], application = StudyApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TimeWidgetSoundTest {
    private val today = LocalDate.of(2026, 10, 4)
    private fun data() = StudyData(schedules = listOf(
        Schedule("one", "s1", "${today}T17:00", 1.0, "問題集", actualDuration = .5, status = "partial"),
        Schedule("two", "s2", "${today}T16:00", 1.0),
        Schedule("done", "s3", "${today}T09:00", 1.0, status = "done"),
        Schedule("miss", "s3", "${today}T10:00", 1.0, status = "miss"),
        Schedule("yesterday", "s1", "${today.minusDays(1)}T17:00", 3.0, status = "done")),
        exams = listOf(ExamSession("exam", "英語", today.toString(), "15:00", "16:00")))
    @Test fun widgetUsesOnlyTodaysRecordedTimeAndShowsPendingPlansAndExamsInOrder() {
        val content = widgetContent(data(), today)
        assertEquals("1時間30分", content.studied)
        assertTrue(content.rows.first().contains("15:00  英語（試験）"))
        assertTrue(content.rows[1].contains("16:00  英語"))
        assertTrue(content.rows[2].contains("17:00  数学"))
        assertTrue(widgetContent(data(), today.plusDays(1)).rows.isEmpty())
        assertEquals("0分", widgetContent(data(), today.plusDays(1)).studied)
    }
    @Test fun widgetLayoutInflatesOnSupportedApisAndOpensToday() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = StudyWidget.views(context, widgetContent(data(), today)).apply(context, LinearLayout(context))
        assertEquals("1時間30分", root.findViewById<TextView>(R.id.widget_hours).text.toString())
        root.measure(android.view.View.MeasureSpec.makeMeasureSpec(350, android.view.View.MeasureSpec.EXACTLY), android.view.View.MeasureSpec.makeMeasureSpec(320, android.view.View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 350, 320)
        val bitmap = android.graphics.Bitmap.createBitmap(350, 320, android.graphics.Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(bitmap))
        val capture = File(requireNotNull(System.getProperty("studycopi.artifacts")), "widget-${android.os.Build.VERSION.SDK_INT}.png")
        capture.parentFile?.mkdirs()
        capture.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        root.findViewById<android.view.View>(R.id.widget_root).performClick()
        assertTrue(shadowOf(context as StudyApplication).nextStartedActivity.getBooleanExtra("openToday", false))
        val receiver = context.packageManager.getReceiverInfo(ComponentName(context, StudyWidget::class.java), android.content.pm.PackageManager.GET_META_DATA)
        assertEquals(R.xml.study_widget_info, receiver.metaData.getInt("android.appwidget.provider"))
    }
    @Test fun savingARecordImmediatelyRefreshesTheBoundHomeWidget() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<StudyApplication>()
        val manager = AppWidgetManager.getInstance(app)
        val shadow = shadowOf(manager)
        val info = AppWidgetProviderInfo().apply { provider = ComponentName(app, StudyWidget::class.java); initialLayout = R.layout.study_widget }
        shadow.addBoundWidget(81, info)
        manager.updateAppWidget(81, StudyWidget.views(app, widgetContent(StudyData())))
        app.repository.load()
        app.repository.restore(StudyData(schedules = listOf(Schedule("record", "s1", "${LocalDate.now()}T17:00", 2.0, actualDuration = .5, status = "partial"))))
        assertEquals("30分", shadow.getViewFor(81).findViewById<TextView>(R.id.widget_hours).text.toString())
        app.repository.update { it.copy(schedules = listOf(it.schedules.single().copy(actualDuration = 1.25))) }
        assertEquals("1時間15分", shadow.getViewFor(81).findViewById<TextView>(R.id.widget_hours).text.toString())
    }
    @Test fun eachSoundSurvivesOldAndNewBackupAndIsSnapshottedAtFireTime() {
        val wall = System.currentTimeMillis()
        for (sound in AlarmSounds.choices.map { it.first }) {
            val alarm = WakeAlarm(id = "morning", sound = sound).arm(wall)
            val data = StudyData(wakeAlarms = listOf(alarm))
            assertEquals(sound, BackupCodec.decode(BackupCodec.encode(data)).wakeAlarms.single().sound)
            val fired = data.claimWake(alarm.id, alarm.nextAt, alarm.nextAt)
            assertEquals(sound, fired.wakeRuns.single().sound)
            assertEquals(sound, BackupCodec.decode(BackupCodec.encode(fired, internal = true), internal = true).wakeRuns.single().sound)
        }
        val old = JSONObject(BackupCodec.encode(StudyData(wakeAlarms = listOf(WakeAlarm(id = "old")))))
        old.getJSONObject("android").getJSONArray("wakeAlarms").getJSONObject(0).remove("sound")
        assertEquals("system", BackupCodec.decode(old.toString()).wakeAlarms.single().sound)
        val local = StudyData(wakeAlarms = listOf(WakeAlarm(id = "file", sound = "content://media/external/audio/media/123")))
        assertEquals("system", BackupCodec.decode(BackupCodec.encode(local)).wakeAlarms.single().sound)
        assertEquals(local, BackupCodec.decode(BackupCodec.encode(local, internal = true), internal = true))
        try { BackupCodec.validate(local.copy(wakeAlarms = listOf(local.wakeAlarms.single().copy(sound = "https://example.com/alarm")))); fail("accepted invalid sound") }
        catch (_: IllegalArgumentException) { }
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (sound in listOf("bell", "pulse", "chime")) context.assets.openFd("sounds/$sound.wav").use { assertTrue(it.length > 10000) }
    }
    @Test fun shortAndLateTimeBlocksKeepTheirStartTimeAndReadableNonoverlappingBounds() {
        val entries = listOf(TimeEntry("a", today, 1020, 1, "長い教科名", "17:00", "#185fa5"),
            TimeEntry("b", today, 1021, 5, "長い教科名", "17:01", "#185fa5"),
            TimeEntry("c", today, 1439, 1, "最後の予定", "23:59", "#185fa5"))
        val placed = placeTimeEntries(entries, 360, mapOf("a" to 140f, "b" to 140f))
        assertEquals(792f, placed[0].top, .01f)
        assertEquals(140f, placed[0].height, .01f)
        assertNotEquals(placed[0].lane, placed[1].lane)
        assertEquals((1439 - 360) * 1.2f, placed[2].top, .01f)
    }
}

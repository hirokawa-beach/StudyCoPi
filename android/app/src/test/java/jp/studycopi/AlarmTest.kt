package jp.studycopi

import android.app.AlarmManager
import android.app.NotificationManager
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35], application = StudyApplication::class)
class AlarmTest {
    @Test fun timerAlarmAndOngoingNotificationAreCancelledWhenPausedOrFinished() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<StudyApplication>()
        val point = clockPoint(app)
        val timer = TimerSession(subjectId = "s1", targetMs = 60_000, originalStart = "2026-10-03T17:00",
            runWall = point.wall, runMonotonic = point.monotonic, boot = point.boot)
        app.repository.load(); app.repository.restore(StudyData(timer = timer))
        AlarmScheduler.reconcile(app)
        val alarms = shadowOf(app.getSystemService(AlarmManager::class.java))
        assertEquals(1, alarms.scheduledAlarms.size)
        assertTrue(kotlin.math.abs(requireNotNull(alarms.peekNextScheduledAlarm()).triggerAtTime - (point.wall + 60_000)) < 1000)
        app.repository.update { it.copy(timer = it.timer!!.togglePause(clockPoint(app))) }
        AlarmScheduler.reconcile(app); assertTrue(alarms.scheduledAlarms.isEmpty())
        app.repository.update { it.copy(timer = null) }; AlarmScheduler.reconcile(app)
        assertTrue(shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications.isEmpty())
    }
    @Test fun exactAlarmDenialStillSchedulesAndChangedRemindersRemoveOldAlarms() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<StudyApplication>()
        if (Build.VERSION.SDK_INT >= 31) ShadowAlarmManager.setCanScheduleExactAlarms(false)
        app.repository.load()
        val future = LocalDateTime.now().plusDays(2)
        val item = Schedule(subjectId = "s1", datetime = future.toString(), duration = 1.0)
        app.repository.restore(StudyData(schedules = listOf(item)))
        app.repository.update { it.copy(preferences = Preferences(reminders = true)) }
        AlarmScheduler.reconcile(app)
        val alarms = shadowOf(app.getSystemService(AlarmManager::class.java))
        assertEquals(1, alarms.scheduledAlarms.size)
        app.repository.update { it.copy(schedules = listOf(item.copy(status = "done"))) }
        AlarmScheduler.reconcile(app); assertTrue(alarms.scheduledAlarms.isEmpty())
    }
}

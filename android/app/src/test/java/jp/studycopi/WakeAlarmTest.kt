package jp.studycopi

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35], application = StudyApplication::class)
class WakeAlarmTest {
    private fun app(): StudyApplication = ApplicationProvider.getApplicationContext<StudyApplication>().also {
        shadowOf(it).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        runBlocking { it.repository.load() }
    }
    @Test fun wakeAlarmsUseAlarmClockAndEditingOrDisablingRemovesOldReservations() = runBlocking {
        val app = app(); val manager = app.getSystemService(AlarmManager::class.java)
        val shadow = shadowOf(manager)
        val alarm = WakeAlarm(id = "morning").arm(System.currentTimeMillis())
        app.repository.restore(StudyData(wakeAlarms = listOf(alarm)))
        WakeScheduler.reconcile(app)
        assertEquals(alarm.nextAt, manager.nextAlarmClock.triggerTime)
        assertEquals("WAKE", shadowOf(shadow.scheduledAlarms.single().operation).savedIntent.action)
        val changed = alarm.copy(time = "08:30").arm(System.currentTimeMillis())
        app.repository.update { it.copy(wakeAlarms = listOf(changed)) }; WakeScheduler.reconcile(app)
        assertEquals(1, shadow.scheduledAlarms.size)
        assertEquals(changed.nextAt, manager.nextAlarmClock.triggerTime)
        app.repository.update { it.copy(wakeAlarms = listOf(changed.copy(enabled = false, nextAt = 0))) }
        WakeScheduler.reconcile(app); assertTrue(shadow.scheduledAlarms.isEmpty())
    }
    @Test fun deniedPermissionsPreventWakeReservationsAndRevokeCancelsExistingOnReconcile() = runBlocking {
        val app = app(); val shadow = shadowOf(app.getSystemService(AlarmManager::class.java))
        app.repository.restore(StudyData(wakeAlarms = listOf(WakeAlarm().arm(System.currentTimeMillis()))))
        WakeScheduler.reconcile(app); assertEquals(1, shadow.scheduledAlarms.size)
        if (Build.VERSION.SDK_INT >= 31) ShadowAlarmManager.setCanScheduleExactAlarms(false)
        else shadowOf(app.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(false)
        WakeScheduler.reconcile(app); assertTrue(shadow.scheduledAlarms.isEmpty())
    }
    @Test fun rebootRecoveryUsesAnAlarmAndDoesNotDirectlyStartAService() = runBlocking {
        val app = app(); val shadow = shadowOf(app.getSystemService(AlarmManager::class.java))
        val run = WakeRun("run", "morning", "起床", "math", 1, "", "", 1, System.currentTimeMillis(), stage = 2)
        app.repository.restore(StudyData(wakeRuns = listOf(run)))
        WakeScheduler.recoverAfterBoot(app); WakeScheduler.recoverAfterBoot(app)
        assertEquals(1, shadow.scheduledAlarms.size)
        assertEquals("WAKE_RECOVER", shadowOf(shadow.scheduledAlarms.single().operation).savedIntent.action)
        assertNull(shadowOf(app).nextStartedService)
        app.repository.update { it.copy(wakeRuns = emptyList()) }; WakeScheduler.recoverAfterBoot(app)
        assertTrue(shadow.scheduledAlarms.isEmpty())
    }
    @Test fun ringingServiceKeepsNotificationAfterFirstStepAndStopsOnlyAfterSecond(): Unit = runBlocking {
        val app = app()
        val run = WakeRun("run", "morning", "起床", "math", 1, "", "", 1, System.currentTimeMillis())
        app.repository.restore(StudyData(wakeRuns = listOf(run)))
        val controller = Robolectric.buildService(WakeRingingService::class.java).create()
        val service = controller.get(); service.onStartCommand(Intent(app, WakeRingingService::class.java), 0, 1)
        shadowOf(Looper.getMainLooper()).idle()
        val shadow = shadowOf(service)
        assertEquals(WakeScheduler.NOTIFICATION, shadow.lastForegroundNotificationId)
        app.repository.update { it.answerWake(run.id, run.key, run.question.answer.toString()) }
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(shadow.isForegroundStopped); assertEquals(2, app.repository.data.value.wakeRuns.single().stage)
        val second = app.repository.data.value.wakeRuns.single()
        app.repository.update { it.answerWake(second.id, second.key, second.question.answer.toString()) }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(shadow.isForegroundStopped); assertTrue(shadow.isStoppedBySelf)
        assertNull(app.getSystemService(NotificationManager::class.java).activeNotifications.firstOrNull { it.id == WakeScheduler.NOTIFICATION })
        controller.destroy()
    }
}

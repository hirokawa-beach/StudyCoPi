package jp.studycopi

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.*
import java.time.*

/** The OS owns deadlines. No WebView, wake lock, or permanently ticking background service. */
object AlarmScheduler {
    private const val TIMER = "timer"
    private const val TIMER_NOTIFICATION = 1
    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("study_timer", "実行中のタイマー", NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel("study_alerts", "タイマー終了・予定", NotificationManager.IMPORTANCE_HIGH))
    }
    fun exactAllowed(context: Context) = Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    fun notificationsAllowed(context: Context) = (Build.VERSION.SDK_INT < 33 ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
        context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
    private fun pending(context: Context, key: String, action: String, timerId: String = ""): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, AlarmReceiver::class.java).setAction(action)
            .setData(Uri.parse("studycopi://alarm/$key")).putExtra("key", key).putExtra("timerId", timerId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun open(context: Context, timer: Boolean = false): PendingIntent = PendingIntent.getActivity(context, if (timer) 1 else 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("openTimer", timer),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun schedule(context: Context, key: String, wall: Long, action: String, timerId: String = "") {
        val manager = context.getSystemService(AlarmManager::class.java)
        val operation = pending(context, key, action, timerId)
        try {
            if (exactAllowed(context)) manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, wall, operation)
            else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, wall, operation)
        } catch (_: SecurityException) { manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, wall, operation) }
    }
    @Synchronized fun reconcile(context: Context) {
        val data = (context.applicationContext as StudyApplication).repository.data.value
        val manager = context.getSystemService(AlarmManager::class.java)
        manager.cancel(pending(context, TIMER, "TIMER"))
        val timer = data.timer
        val point = clockPoint(context)
        if (timer != null && !timer.paused && timer.remaining(point) > 0)
            schedule(context, TIMER, point.wall + timer.remaining(point), "TIMER", timer.id)
        showTimer(context, data)
        val prefs = context.getSharedPreferences("alarms", Context.MODE_PRIVATE)
        val old = prefs.getStringSet("reminders", emptySet()).orEmpty().toSet()
        old.forEach { manager.cancel(pending(context, it, "REMINDER")) }
        val next = mutableSetOf<String>()
        if (data.preferences.reminders) data.schedules.filterNot { it.finished }.forEach {
            val wall = dateTime(it.datetime).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() - data.preferences.reminderMinutes * 60_000L
            if (wall > point.wall) { val key = "schedule:${it.id}"; schedule(context, key, wall, "REMINDER"); next += key }
        }
        if (data.preferences.examReminder) data.examGroups.forEach {
            val wall = LocalDate.parse(it.startDate).minusDays(1).atTime(18, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            if (wall > point.wall) { val key = "exam:${it.id}"; schedule(context, key, wall, "REMINDER"); next += key }
        }
        prefs.edit().putStringSet("reminders", next).apply()
        ScheduledFocus.reconcile(context, data)
    }
    private fun showTimer(context: Context, data: StudyData) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val timer = data.timer
        if (timer == null || !data.preferences.timerNotification || !notificationsAllowed(context)) { manager.cancel(TIMER_NOTIFICATION); return }
        val point = clockPoint(context)
        val builder = Notification.Builder(context, "study_timer").setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(data.subject(timer.subjectId).name + if (timer.paused) "・一時停止中" else "・勉強中")
            .setContentText(if (timer.paused) "残り ${hoursText(timer.remaining(point) / 3_600_000.0)}" else "タップしてタイマーに戻る")
            .setContentIntent(open(context, true)).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, if (timer.paused) "再開" else "一時停止",
                pending(context, "pause", "PAUSE", timer.id)).build())
            .addAction(Notification.Action.Builder(null, "終了して記録", pending(context, "stop", "STOP", timer.id)).build())
        if (!timer.paused) builder.setWhen(point.wall + timer.remaining(point)).setUsesChronometer(true).setChronometerCountDown(true)
        try { manager.notify(TIMER_NOTIFICATION, builder.build()) } catch (_: SecurityException) { }
    }
    fun notifyFinished(context: Context, subjectId: String) {
        val data = (context.applicationContext as StudyApplication).repository.data.value
        notify(context, 2, "${data.subject(subjectId).name}のタイマーが終了", "勉強時間を記録しました")
    }
    fun notifyReminder(context: Context, key: String, data: StudyData) {
        if (key.startsWith("schedule:") && data.preferences.reminders) {
            val item = data.schedules.find { it.id == key.substringAfter(':') && !it.finished } ?: return
            notify(context, key.hashCode(), "${data.subject(item.subjectId).name}の予定", "${dateTime(item.datetime).toLocalTime()} ${item.content}")
        } else if (key.startsWith("exam:") && data.preferences.examReminder) {
            val group = data.examGroups.find { it.id == key.substringAfter(':') } ?: return
            notify(context, key.hashCode(), "明日から ${group.name}", "試験範囲と準備を確認しよう")
        }
    }
    private fun notify(context: Context, id: Int, title: String, text: String) {
        if (!notificationsAllowed(context)) return
        try { context.getSystemService(NotificationManager::class.java).notify(id,
            Notification.Builder(context, "study_alerts").setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title).setContentText(text).setContentIntent(open(context)).setAutoCancel(true).build())
        } catch (_: SecurityException) { }
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repo = (context.applicationContext as StudyApplication).repository
                repo.load()
                val point = clockPoint(context)
                var finished: TimerSession? = null
                if (intent.action == "SCHEDULED_FOCUS") ScheduledFocus.notify(context, intent, repo.data.value, point.wall)
                else if (intent.action == "REMINDER") AlarmScheduler.notifyReminder(context, intent.getStringExtra("key").orEmpty(), repo.data.value)
                else repo.update { data ->
                    val timer = data.timer
                    if (timer == null || timer.id != intent.getStringExtra("timerId")) data
                    else when (intent.action) {
                        "TIMER" -> if (!timer.paused && timer.remaining(point) == 0L) { finished = timer; data.finishTimer(point) } else data
                        "STOP" -> data.finishTimer(point)
                        "PAUSE" -> if (!timer.paused && timer.remaining(point) == 0L) { finished = timer; data.finishTimer(point) }
                            else data.copy(timer = timer.togglePause(point))
                        else -> data
                    }
                }
                AlarmScheduler.reconcile(context)
                finished?.let { if (repo.data.value.preferences.timerNotification) AlarmScheduler.notifyFinished(context, it.subjectId) }
            } catch (e: Exception) { android.util.Log.e("StudyCoPi", "Could not process alarm; data retained", e) }
            finally { result.finish() }
        }
    }
}
class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
                AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)) return
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repo = (context.applicationContext as StudyApplication).repository
                repo.load(); val point = clockPoint(context)
                var finished: TimerSession? = null
                repo.update { data ->
                    val timer = data.timer
                    (if (timer != null && !timer.paused && timer.remaining(point) == 0L) { finished = timer; data.finishTimer(point) }
                    else data).normalizeWakeAlarms(point.wall, intent.action == Intent.ACTION_TIMEZONE_CHANGED || intent.action == Intent.ACTION_TIME_CHANGED)
                }
                AlarmScheduler.reconcile(context)
                WakeScheduler.reconcile(context)
                if (intent.action == Intent.ACTION_BOOT_COMPLETED) WakeScheduler.recoverAfterBoot(context)
                finished?.let { if (repo.data.value.preferences.timerNotification) AlarmScheduler.notifyFinished(context, it.subjectId) }
            } catch (e: Exception) { android.util.Log.e("StudyCoPi", "Could not reschedule alarms; data retained", e) }
            finally { result.finish() }
        }
    }
}

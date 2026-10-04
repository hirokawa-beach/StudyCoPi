package jp.studycopi

import android.app.*
import android.content.*
import android.net.Uri
import java.time.ZoneId

fun Schedule.startWall(): Long = dateTime(datetime).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
fun focusKey(plan: Schedule) = "${plan.id}:${plan.startWall()}"
fun focusDue(data: StudyData, wall: Long): List<Schedule> {
    if (!data.preferences.scheduledFocus || data.timer != null || data.wakeRuns.isNotEmpty()) return emptyList()
    return data.schedules.filter { !it.finished && wall >= it.startWall() &&
        wall - it.startWall() < minOf(5 * 60_000L, (it.duration * 3_600_000).toLong()) }.sortedBy { it.datetime }
}

/** OS alarm posts a normal notification; screen pinning is only requested by a foreground Activity. */
object ScheduledFocus {
    private fun prefs(context: Context) = context.getSharedPreferences("scheduled_focus", Context.MODE_PRIVATE)
    fun seen(context: Context, plan: Schedule) = focusKey(plan) in prefs(context).getStringSet("seen", emptySet()).orEmpty()
    @Synchronized fun claim(context: Context, plan: Schedule): Boolean {
        if (seen(context, plan)) return false
        val keys = (prefs(context).getStringSet("seen", emptySet()).orEmpty() + focusKey(plan))
            .sortedByDescending { it.substringAfterLast(':').toLongOrNull() ?: 0 }.take(256).toSet()
        check(prefs(context).edit().putStringSet("seen", keys).commit()) { "集中モードの状態を保存できません" }
        context.getSystemService(NotificationManager::class.java).cancel("focus", plan.id.hashCode())
        return true
    }
    fun intent(context: Context, plan: Schedule) = Intent(context, ScheduledFocusActivity::class.java)
        .putExtra("scheduleId", plan.id).putExtra("expected", plan.startWall())
    private fun pending(context: Context, key: String, expected: Long = 0) = PendingIntent.getBroadcast(context, 0,
        Intent(context, AlarmReceiver::class.java).setAction("SCHEDULED_FOCUS").setData(Uri.parse("studycopi://focus/$key"))
            .putExtra("scheduleId", key).putExtra("expected", expected), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    @Synchronized fun reconcile(context: Context, data: StudyData) {
        val manager = context.getSystemService(AlarmManager::class.java)
        val previous = prefs(context).getStringSet("scheduled", emptySet()).orEmpty().toSet()
        previous.forEach {
            manager.cancel(pending(context, it))
            if (focusDue(data, System.currentTimeMillis()).none { plan -> plan.id == it })
                context.getSystemService(NotificationManager::class.java).cancel("focus", it.hashCode())
        }
        val future = if (data.preferences.scheduledFocus) data.schedules.filter { !it.finished && it.startWall() > System.currentTimeMillis() && !seen(context, it) } else emptyList()
        future.forEach { plan ->
            val operation = pending(context, plan.id, plan.startWall())
            try {
                if (AlarmScheduler.exactAllowed(context)) manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, plan.startWall(), operation)
                else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, plan.startWall(), operation)
            } catch (_: SecurityException) { manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, plan.startWall(), operation) }
        }
        prefs(context).edit().putStringSet("scheduled", future.map { it.id }.toSet()).apply()
    }
    fun notify(context: Context, intent: Intent, data: StudyData, wall: Long) {
        val plan = focusDue(data, wall).find { it.id == intent.getStringExtra("scheduleId") && it.startWall() == intent.getLongExtra("expected", 0) } ?: return
        if (seen(context, plan) || !AlarmScheduler.notificationsAllowed(context)) return
        val open = PendingIntent.getActivity(context, 0, intent(context, plan).setData(Uri.parse("studycopi://focus/open/${focusKey(plan)}")),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(context, "study_alerts").setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("${data.subject(plan.subjectId).name}の勉強時間です")
            .setContentText("タップして画面固定へ。タイマーはまだ開始しません")
            .setContentIntent(open).setAutoCancel(true).setTimeoutAfter(5 * 60_000L).build()
        try { context.getSystemService(NotificationManager::class.java).notify("focus", plan.id.hashCode(), notification) } catch (_: SecurityException) { }
    }
}

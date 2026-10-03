package jp.studycopi

import android.app.*
import android.content.*
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.*

object WakeScheduler {
    const val CHANNEL = "wake_alarm"
    const val NOTIFICATION = 22001
    fun createChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "目覚まし", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "2段階で解除する目覚まし。音はアラーム音量で再生します。"
                setSound(null, null); enableVibration(false)
            })
    }
    fun notificationsAllowed(context: Context): Boolean = AlarmScheduler.notificationsAllowed(context) &&
        context.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    fun fullScreenAllowed(context: Context) = Build.VERSION.SDK_INT < 34 || context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
    fun open(context: Context): PendingIntent {
        val options = if (Build.VERSION.SDK_INT >= 35) ActivityOptions.makeBasic().apply {
            setPendingIntentCreatorBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
        }.toBundle() else null
        return PendingIntent.getActivity(context, 22001, Intent(context, WakeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE, options)
    }
    private fun operation(context: Context, id: String, wall: Long): PendingIntent = PendingIntent.getBroadcast(context, 0,
        Intent(context, WakeReceiver::class.java).setAction("WAKE").setData(Uri.parse("studycopi://wake/$id/$wall"))
            .putExtra("alarmId", id).putExtra("wall", wall), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun recovery(context: Context): PendingIntent = PendingIntent.getBroadcast(context, 22003,
        Intent(context, WakeReceiver::class.java).setAction("WAKE_RECOVER"),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    /** Boot receivers cannot launch media playback services on Android 15+. Use an alarm instead. */
    fun recoverAfterBoot(context: Context) {
        val manager = context.getSystemService(AlarmManager::class.java)
        manager.cancel(recovery(context))
        if ((context.applicationContext as StudyApplication).repository.data.value.wakeRuns.isEmpty() ||
            !AlarmScheduler.exactAllowed(context) || !notificationsAllowed(context)) return
        try { manager.setAlarmClock(AlarmManager.AlarmClockInfo(System.currentTimeMillis() + 2000, open(context)), recovery(context)) }
        catch (_: SecurityException) { }
    }
    @Synchronized fun reconcile(context: Context) {
        createChannel(context)
        val data = (context.applicationContext as StudyApplication).repository.data.value
        val manager = context.getSystemService(AlarmManager::class.java)
        val prefs = context.getSharedPreferences("wake_scheduler", Context.MODE_PRIVATE)
        prefs.getStringSet("keys", emptySet()).orEmpty().forEach { key ->
            val wall = key.substringAfter('|').toLongOrNull()
            if (wall != null) manager.cancel(operation(context, key.substringBefore('|'), wall))
        }
        val keys = mutableSetOf<String>()
        if (AlarmScheduler.exactAllowed(context) && notificationsAllowed(context)) {
            val now = System.currentTimeMillis()
            data.wakeAlarms.filter { it.enabled && it.nextAt >= now - 15 * 60_000 }.forEach { alarm ->
                try {
                    manager.setAlarmClock(AlarmManager.AlarmClockInfo(maxOf(alarm.nextAt, now + 500),
                        PendingIntent.getActivity(context, 22002, Intent(context, MainActivity::class.java).putExtra("openWakeAlarms", true),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)), operation(context, alarm.id, alarm.nextAt))
                    keys += "${alarm.id}|${alarm.nextAt}"
                } catch (_: SecurityException) { }
            }
        }
        prefs.edit().putStringSet("keys", keys).apply()
    }
    fun startRinging(context: Context) {
        if ((context.applicationContext as StudyApplication).repository.data.value.wakeRuns.isEmpty()) return
        context.startForegroundService(Intent(context, WakeRingingService::class.java))
    }
    fun notification(context: Context, run: WakeRun? = null): Notification {
        val builder = Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(run?.label ?: "目覚まし").setContentText("タップして2段階解除を開く")
            .setCategory(Notification.CATEGORY_ALARM).setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(open(context)).setOngoing(true).setOnlyAlertOnce(true)
        if (fullScreenAllowed(context)) builder.setFullScreenIntent(open(context), true)
        return builder.build()
    }
}

class WakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf("WAKE", "WAKE_RECOVER")) return
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repo = (context.applicationContext as StudyApplication).repository
                repo.load()
                val id = intent.getStringExtra("alarmId").orEmpty()
                val wall = intent.getLongExtra("wall", 0)
                var claimed = intent.action == "WAKE_RECOVER" && repo.data.value.wakeRuns.isNotEmpty()
                if (intent.action == "WAKE") repo.update { data -> data.claimWake(id, wall, System.currentTimeMillis()).also { claimed = it != data } }
                if (claimed) WakeScheduler.startRinging(context)
                WakeScheduler.reconcile(context)
            } catch (e: Exception) { android.util.Log.e("StudyCoPi", "Could not start wake alarm", e) }
            finally { result.finish() }
        }
    }
}

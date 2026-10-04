package jp.studycopi

import android.app.Service
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.media.AudioManager
import android.media.AudioFocusRequest
import android.os.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

/** Runs only while an alarm is ringing; progress remains in the atomic repository. */
class WakeRingingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null
    private var player: MediaPlayer? = null
    private var fallback: ToneGenerator? = null
    private var fallbackJob: Job? = null
    private var sounding = false
    private var soundingRun = ""
    private var audioFocus: AudioFocusRequest? = null
    private val vibration by lazy { getSystemService(Vibrator::class.java) }
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        WakeScheduler.createChannel(this)
        val notification = WakeScheduler.notification(this)
        if (Build.VERSION.SDK_INT >= 29) startForeground(WakeScheduler.NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(WakeScheduler.NOTIFICATION, notification)
        if (watcher == null) watcher = scope.launch {
            val repo = (application as StudyApplication).repository
            try {
                repo.load()
                repo.data.collectLatest { data ->
                    val run = data.wakeRuns.firstOrNull()
                    if (run == null) { stopSound(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
                    else {
                        getSystemService(NotificationManager::class.java).notify(WakeScheduler.NOTIFICATION, WakeScheduler.notification(this@WakeRingingService, run))
                        if (!sounding || soundingRun != run.id) { stopSound(); soundingRun = run.id; startSound(run.sound) }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("StudyCoPi", "Could not restore wake alarm", e)
                stopSound(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            }
        }
        return START_STICKY
    }
    private fun startSound(sound: String) {
        sounding = true
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        audioFocus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { }.build().also { getSystemService(AudioManager::class.java).requestAudioFocus(it) }
        vibration?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 700), 0), attributes)
        startPlayer(sound, attributes)
    }
    private fun startPlayer(sound: String, attributes: AudioAttributes) {
        player?.release()
        val current = MediaPlayer()
        player = current
        fun failed() {
            if (!sounding || player !== current) return
            if (sound != "system") startPlayer("system", attributes) else {
                current.release(); player = null; startFallback()
            }
        }
        try {
            current.setAudioAttributes(attributes)
            current.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK)
            AlarmSounds.source(current, this, sound)
            current.isLooping = true
            current.setOnPreparedListener { if (sounding && player === it) it.start() }
            current.setOnErrorListener { _, _, _ -> Handler(Looper.getMainLooper()).post { failed() }; true }
            current.prepareAsync()
        } catch (_: Exception) { failed() }
    }
    private fun startFallback() {
        if (!sounding || fallbackJob != null) return
        fallbackJob = scope.launch {
            try {
                fallback = ToneGenerator(AudioManager.STREAM_ALARM, 100)
                while (isActive && sounding) { fallback?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 900); delay(1500) }
            } catch (e: Exception) { android.util.Log.e("StudyCoPi", "Alarm audio unavailable", e) }
        }
    }
    private fun stopSound() {
        sounding = false; soundingRun = ""; fallbackJob?.cancel(); fallbackJob = null
        player?.release(); player = null; fallback?.release(); fallback = null; vibration?.cancel()
        audioFocus?.let { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }; audioFocus = null
    }
    override fun onDestroy() { scope.cancel(); stopSound(); super.onDestroy() }
}

package jp.studycopi

import android.app.ActivityManager
import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class ScheduledFocusActivity : JapaneseActivity() {
    private lateinit var model: StudyViewModel
    private var entered by mutableStateOf(false)
    private var attempted = false
    private var transferring = false
    private var pinned by mutableStateOf(false)
    private var message by mutableStateOf("")
    private fun plan() = model.data.value.schedules.find { it.id == intent.getStringExtra("scheduleId") && it.startWall() == intent.getLongExtra("expected", 0) && !it.finished }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        attempted = savedInstanceState?.getBoolean("attempted") ?: false
        entered = savedInstanceState?.getBoolean("entered") ?: false
        model = ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory(application))[StudyViewModel::class.java]
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) { combine(model.ready, model.data) { ready, data -> ready to data }.collect { (ready, data) ->
                if (!ready) return@collect
                val plan = plan()
                if (data.wakeRuns.isNotEmpty()) { leave(); startActivity(Intent(this@ScheduledFocusActivity, WakeActivity::class.java)); return@collect }
                if (data.timer != null) {
                    if (data.timer.scheduleId == plan?.id && entered) {
                        transferring = true
                        startActivity(Intent(this@ScheduledFocusActivity, FocusActivity::class.java)); finish()
                    } else leave()
                    return@collect
                }
                if (plan == null || !data.preferences.scheduledFocus || !entered && plan !in focusDue(data, System.currentTimeMillis())) { leave(); return@collect }
                if (!entered) {
                    if (!runCatching { ScheduledFocus.claim(this@ScheduledFocusActivity, plan) }.getOrDefault(false)) { leave(); return@collect }
                    entered = true
                }
                maybePin()
            } }
        }
        setContent { StudyTheme {
            val data by model.data.collectAsState()
            val ready by model.ready.collectAsState()
            val error by model.loadError.collectAsState()
            var confirming by remember { mutableStateOf(false) }
            LaunchedEffect(model) { model.notices.collect { message = it.text } }
            BackHandler { confirming = true }
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Text("勉強の時間です", style = MaterialTheme.typography.headlineSmall)
                    plan()?.let { plan ->
                        Text(data.subject(plan.subjectId).name, style = MaterialTheme.typography.headlineMedium)
                        Text(plan.content)
                        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(hoursText(plan.duration), style = MaterialTheme.typography.displaySmall)
                            Text("タイマーはまだ開始していません。勉強時間も記録されません。")
                        } }
                        Text(if (pinned) "画面固定中" else "Androidの確認画面で固定を許可してください", color = MaterialTheme.colorScheme.primary)
                        if (!pinned) OutlinedButton(onClick = { pin() }, enabled = entered && ready, modifier = Modifier.fillMaxWidth()) { Text("画面固定を開始") }
                        Button(onClick = { model.startTimer(plan.subjectId, plan.examGroupId, (plan.duration * 60).roundToInt().coerceIn(1, 1440), plan.id) },
                            enabled = entered, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("勉強を始める") }
                        TextButton(onClick = { confirming = true }, modifier = Modifier.fillMaxWidth()) { Text("今回は解除する") }
                        Text("固定はAndroid所定の操作でも解除できます。ほかのアプリを使っている間は通知からこの画面を開けます。", style = MaterialTheme.typography.bodySmall)
                    }
                    if (!ready) CircularProgressIndicator()
                    if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
                    error?.let { Text(it); TextButton(onClick = { leave() }) { Text("戻る") } }
                }
            }
            if (confirming) AlertDialog(onDismissRequest = { confirming = false }, title = { Text("画面固定を解除しますか？") },
                text = { Text("タイマーを開始せずに戻ります。この予定では再表示しません。") },
                confirmButton = { TextButton(onClick = { leave() }) { Text("解除して戻る") } },
                dismissButton = { TextButton(onClick = { confirming = false }) { Text("続ける") } })
        } }
    }
    private fun maybePin() {
        if (entered && !attempted && hasWindowFocus() && model.ready.value &&
            model.data.value.preferences.scheduledFocus && model.data.value.timer == null &&
            model.data.value.wakeRuns.isEmpty() && plan() != null) { attempted = true; pin() }
    }
    private fun pin() {
        attempted = true
        if (getSystemService(KeyguardManager::class.java).isKeyguardLocked) { message = "端末のロックを解除してから固定してください"; return }
        runCatching { startLockTask(); refreshPin() }.onFailure { message = "Androidの「アプリの固定」を有効にしてください" }
    }
    private fun refreshPin() { pinned = getSystemService(ActivityManager::class.java).lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE }
    private fun release() { runCatching { if (getSystemService(ActivityManager::class.java).lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE) stopLockTask() }; pinned = false }
    private fun leave() { release(); finish() }
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus && ::model.isInitialized) { refreshPin(); maybePin() } }
    override fun onSaveInstanceState(outState: Bundle) { outState.putBoolean("attempted", attempted); outState.putBoolean("entered", entered); super.onSaveInstanceState(outState) }
    override fun onDestroy() { if (isFinishing && !transferring) release(); super.onDestroy() }
}

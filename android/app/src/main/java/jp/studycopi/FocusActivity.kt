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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider

class FocusActivity : JapaneseActivity() {
    private lateinit var model: StudyViewModel
    private var pinned by mutableStateOf(false)
    private var status by mutableStateOf("")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        model = ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory(application))[StudyViewModel::class.java]
        refreshPin()
        observeScreenPin(::refreshPin)
        setContent { StudyTheme {
            val data by model.data.collectAsState()
            val point by model.now.collectAsState()
            val ready by model.ready.collectAsState()
            val error by model.loadError.collectAsState()
            var confirm by remember { mutableStateOf(false) }
            LaunchedEffect(model) { model.notices.collect { status = it.text } }
            LaunchedEffect(ready, data.timer?.id) { if (ready && data.timer == null) leave() }
            LaunchedEffect(data.wakeRuns.firstOrNull()?.id) {
                if (data.wakeRuns.isNotEmpty()) { releasePin(); startActivity(Intent(this@FocusActivity, WakeActivity::class.java)) }
            }
            BackHandler { confirm = true }
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("集中モード", style = MaterialTheme.typography.headlineSmall)
                    Text(if (pinned) "画面固定中" else "画面固定を開始してください", color = MaterialTheme.colorScheme.primary)
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error); TextButton(onClick = { leave() }) { Text("戻る") } }
                    data.timer?.let { timer ->
                        Text(data.subject(timer.subjectId).name, style = MaterialTheme.typography.titleLarge)
                        if (timer.examGroupId.isNotEmpty()) Text(data.groupName(timer.examGroupId), style = MaterialTheme.typography.bodySmall)
                        val seconds = (timer.remaining(point) + 999) / 1000
                        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                            Column(Modifier.fillMaxWidth().padding(vertical = 36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("%02d:%02d".format(seconds / 60, seconds % 60), style = MaterialTheme.typography.displayMedium.copy(fontFeatureSettings = "tnum"))
                                Text(if (timer.paused) "一時停止中" else "残り時間", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        if (!pinned) {
                            Button(onClick = { pin() }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("画面固定を開始") }
                            Text("Androidの確認画面で固定を許可してください。固定中はホームや他アプリに移動しにくくなります。", style = MaterialTheme.typography.bodySmall)
                        }
                        if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = model::pauseTimer, modifier = Modifier.fillMaxWidth()) { Text(if (timer.paused) "勉強を再開" else "一時停止") }
                        TextButton(onClick = { confirm = true }, modifier = Modifier.fillMaxWidth()) { Text("終了して記録") }
                        Text("固定はAndroid所定の操作でも解除できます。タイマー終了時は固定を解除して戻ります。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (!ready && error == null) CircularProgressIndicator()
                }
            }
            if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("集中モードを終了？") },
                text = { Text("ここまでの勉強時間を記録して、画面固定を解除します。") },
                confirmButton = { TextButton(onClick = { confirm = false; model.stopTimer() }) { Text("終了して記録") } },
                dismissButton = { TextButton(onClick = { confirm = false }) { Text("勉強を続ける") } })
        } }
    }
    private fun pin() {
        refreshPin()
        if (pinned) return
        if (getSystemService(KeyguardManager::class.java).isKeyguardLocked) { status = "端末のロックを解除してから固定してください"; return }
        runCatching { startLockTask(); status = "確認画面が出ない場合は、Androidの「アプリの固定」を有効にしてください"; refreshPin() }
            .onFailure { status = "画面固定を開始できません。Androidの設定を確認してください" }
    }
    private fun refreshPin() {
        pinned = isScreenPinned()
        if (pinned && status.startsWith("確認画面が出ない場合")) status = ""
    }
    private fun releasePin() { runCatching { if (getSystemService(ActivityManager::class.java).lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE) stopLockTask() }; pinned = false }
    private fun leave() { releasePin(); finish() }
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus) refreshPin() }
    override fun onStart() { super.onStart(); model.resume(); refreshPin() }
    override fun onStop() { model.suspendTicks(); super.onStop() }
    override fun onDestroy() { if (isFinishing) releasePin(); super.onDestroy() }
}

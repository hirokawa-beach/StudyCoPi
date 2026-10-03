package jp.studycopi

import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import android.nfc.NfcAdapter
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

class WakeViewModel(application: android.app.Application) : AndroidViewModel(application) {
    private val repo = (application as StudyApplication).repository
    val data = repo.data
    val ready = MutableStateFlow(false)
    val error = MutableStateFlow("")
    init { viewModelScope.launch {
        try { repo.load(); ready.value = true } catch (_: Exception) { error.value = "解除状態を読み込めません。Androidのアプリ設定から停止できます。" }
    } }
    private fun change(action: (StudyData) -> StudyData) { viewModelScope.launch {
        try { repo.update(action); error.value = "" } catch (e: Exception) { error.value = e.message ?: "保存できませんでした" }
    } }
    fun answer(id: String, key: String, answer: String) = change { it.answerWake(id, key, answer) }
    fun scan(id: String, tag: String) = change { it.scanWake(id, tag) }
    fun emergency(id: String) = change { it.copy(wakeRuns = it.wakeRuns.filterNot { run -> run.id == id }) }
}

class WakeActivity : JapaneseActivity() {
    private lateinit var model: WakeViewModel
    private var locked by mutableStateOf(false)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        model = ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory(application))[WakeViewModel::class.java]
        setContent { StudyTheme {
            val data by model.data.collectAsState(); val ready by model.ready.collectAsState(); val error by model.error.collectAsState()
            val run = data.wakeRuns.firstOrNull()
            var status by remember { mutableStateOf("") }
            var answer by rememberSaveable { mutableStateOf("") }
            var answerKey by rememberSaveable { mutableStateOf("") }
            LaunchedEffect(run?.key) {
                if (answerKey != run?.key.orEmpty()) { answer = ""; answerKey = run?.key.orEmpty() }
            }
            LaunchedEffect(ready, run?.id) {
                if (ready && run == null) { stopService(Intent(this@WakeActivity, WakeRingingService::class.java)); finish() }
                else if (ready) runCatching { WakeScheduler.startRinging(this@WakeActivity) }.onFailure { model.error.value = "音を開始できませんでした。アプリの通知設定を確認してください" }
            }
            BackHandler(enabled = run != null) { status = "2段階の解除が終わるまで、目覚ましは鳴り続けます" }
            ReadNfc(this, run?.needsTag == true && !locked,
                { tag -> run?.let { model.scan(it.id, tag) } }, { status = it })
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Text("おはようございます", style = MaterialTheme.typography.headlineSmall)
                    if (run == null) {
                        if (!ready && error.isEmpty()) CircularProgressIndicator()
                        if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
                    } else {
                        Text(run.label, style = MaterialTheme.typography.titleMedium)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            repeat(2) { stage -> LinearProgressIndicator(progress = {
                                if (run.stage > stage + 1) 1f else if (run.stage == stage + 1 && !run.needsTag) run.solved.toFloat() / run.questions else 0f
                            }, modifier = Modifier.weight(1f).height(6.dp)) }
                        }
                        Text("ステップ ${run.stage} / 2", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        if (run.needsTag) {
                            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) { Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("${run.tagName}を読み取る", style = MaterialTheme.typography.titleLarge)
                                Text("登録した場所へ移動し、スマホの背面をタグに近づけてください。")
                            } }
                            if (locked) Button(onClick = { getSystemService(KeyguardManager::class.java).requestDismissKeyguard(this@WakeActivity,
                                object : KeyguardManager.KeyguardDismissCallback() {
                                    override fun onDismissSucceeded() { locked = false }
                                }) }, modifier = Modifier.fillMaxWidth()) { Text("ロックを解除してNFCを読む") }
                            Text(status.ifEmpty { "NFCタグの読み取りを待っています" }, style = MaterialTheme.typography.bodySmall)
                            if (!locked && NfcAdapter.getDefaultAdapter(this@WakeActivity) != null)
                                OutlinedButton(onClick = { startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) }, modifier = Modifier.fillMaxWidth()) { Text("NFCの設定を開く") }
                        } else {
                            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) { Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(if (run.stage == 1) "まずは計算問題" else "最後の計算問題", style = MaterialTheme.typography.titleLarge)
                                Text("${run.solved + 1} / ${run.questions}問", style = MaterialTheme.typography.labelMedium)
                                Text(run.question.text, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.align(Alignment.CenterHorizontally))
                            } }
                            OutlinedTextField(answer, { answer = it.filter(Char::isDigit).take(6) }, label = { Text("答え") }, singleLine = true,
                                modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { model.answer(run.id, run.key, answer) }))
                            Button(onClick = { model.answer(run.id, run.key, answer) }, enabled = answer.isNotEmpty(), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("回答") }
                        }
                        if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
                        if (!run.needsTag && status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)
                        Text("音量はスマホの「アラーム音量」に従います。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(12.dp))
                        HoldToConfirm("緊急停止", 10) { model.emergency(run.id) }
                    }
                }
            }
        } }
    }
    override fun onStart() { super.onStart(); locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked }
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus) locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked }
}

@Composable fun HoldToConfirm(label: String, seconds: Int, confirmed: () -> Unit) {
    var progress by remember { mutableFloatStateOf(0f) }
    var accessibleConfirm by remember { mutableStateOf(false) }
    val action by rememberUpdatedState(confirmed)
    Box(Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
        .semantics { role = Role.Button; contentDescription = "$label、${seconds}秒長押し"
            onClick("操作を確認") { accessibleConfirm = true; true } }
        .focusable().pointerInput(seconds) {
            detectTapGestures(onPress = {
                coroutineScope {
                    val hold = launch {
                        for (tick in 1..seconds * 10) { delay(100); progress = tick.toFloat() / (seconds * 10) }
                        action()
                    }
                    try { tryAwaitRelease() } finally { hold.cancel(); progress = 0f }
                }
            })
        }, contentAlignment = Alignment.Center) {
        Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (progress > 0) "${label}まで ${kotlin.math.ceil(seconds * (1 - progress)).toInt()}秒" else "$label・${seconds}秒長押し", style = MaterialTheme.typography.labelMedium)
            if (progress > 0) LinearProgressIndicator(progress = { progress }, modifier = Modifier.padding(top = 8.dp).fillMaxWidth())
        }
    }
    if (accessibleConfirm) AlertDialog(onDismissRequest = { accessibleConfirm = false }, title = { Text("${label}しますか？") },
        text = { Text("NFCタグを使えないときなどに、現在の目覚ましを停止できます。") },
        confirmButton = { TextButton(onClick = { accessibleConfirm = false; action() }) { Text(label) } },
        dismissButton = { TextButton(onClick = { accessibleConfirm = false }) { Text("続ける") } })
}

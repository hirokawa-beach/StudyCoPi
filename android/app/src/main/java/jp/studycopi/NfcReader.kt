package jp.studycopi

import android.app.Activity
import android.content.Intent
import android.nfc.NfcAdapter
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner

@Composable fun ReadNfc(activity: JapaneseActivity, active: Boolean, scanned: (String) -> Unit, status: (String) -> Unit) {
    val scan by rememberUpdatedState(scanned)
    val report by rememberUpdatedState(status)
    DisposableEffect(activity, active) {
        val adapter = NfcAdapter.getDefaultAdapter(activity)
        fun enable() {
            if (!active) return
            when {
                adapter == null -> report("この端末はNFCに対応していません")
                !adapter.isEnabled -> report("Androidの設定でNFCをオンにしてください")
                else -> runCatching {
                    adapter.enableReaderMode(activity, { tag -> activity.runOnUiThread {
                        val id = nfcId(tag.id ?: byteArrayOf())
                        if (validNfcId(id)) scan(id) else report("このタグのIDを読み取れません。別のNFCタグを使ってください")
                    } }, NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_NFC_F or
                        NfcAdapter.FLAG_READER_NFC_V or NfcAdapter.FLAG_READER_NFC_BARCODE or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK, null)
                    report("スマホの背面をNFCタグに近づけてください")
                }.onFailure { report("NFCを開始できません。設定を確認して、もう一度開いてください") }
            }
        }
        fun disable() { runCatching { adapter?.disableReaderMode(activity) } }
        val observer = object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) { enable() }
            override fun onPause(owner: LifecycleOwner) { disable() }
        }
        activity.lifecycle.addObserver(observer)
        if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) enable()
        onDispose { disable(); activity.lifecycle.removeObserver(observer) }
    }
}

class NfcRegisterActivity : JapaneseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { StudyTheme {
            var status by remember { mutableStateOf("NFCを確認しています") }
            ReadNfc(this, true, { id -> setResult(Activity.RESULT_OK, Intent().putExtra("tagId", id)); finish() }, { status = it })
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("解除用のタグを登録", style = MaterialTheme.typography.headlineSmall)
                    Text("1階など、朝に移動する場所へ置くNFCタグを読み取ってください。タグには書き込みません。")
                    Spacer(Modifier.weight(1f))
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) { Text(status, Modifier.padding(24.dp), style = MaterialTheme.typography.titleMedium) }
                    Spacer(Modifier.weight(1f))
                    if (NfcAdapter.getDefaultAdapter(this@NfcRegisterActivity) != null)
                        OutlinedButton(onClick = { startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) }, modifier = Modifier.fillMaxWidth()) { Text("NFCの設定を開く") }
                    TextButton(onClick = { finish() }, modifier = Modifier.fillMaxWidth()) { Text("キャンセル") }
                }
            }
        } }
    }
}

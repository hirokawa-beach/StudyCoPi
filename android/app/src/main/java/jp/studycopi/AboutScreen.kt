package jp.studycopi

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

@Composable fun AboutScreen() {
    val context = LocalContext.current
    val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    fun open(url: String) { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("StudyCoPiについて", style = MaterialTheme.typography.headlineSmall)
        Image(painterResource(R.drawable.app_icon_art), "StudyCoPiのアイコン", Modifier.size(88.dp))
        Text("StudyCoPi", style = MaterialTheme.typography.headlineMedium)
        Text("バージョン $version · Android版", style = MaterialTheme.typography.titleMedium)
        Text("予定を立てて、勉強して、振り返る。試験ごとの学習を続けるためのアプリです。")
        HorizontalDivider()
        Text("開発者", style = MaterialTheme.typography.titleMedium)
        Text("ひろかわびーち（hirokawa-beach）")
        Text("ライセンス：MIT", style = MaterialTheme.typography.bodySmall)
        Text("学習データはこの端末に保存されます。自動同期はありません。端末の変更や削除に備えて、バックアップを保存してください。", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { open("https://github.com/hirokawa-beach/StudyCoPi") }, modifier = Modifier.fillMaxWidth()) { Text("ソースコード・開発者ページ") }
        OutlinedButton(onClick = { open("https://github.com/hirokawa-beach/StudyCoPi/releases") }, modifier = Modifier.fillMaxWidth()) { Text("更新情報・APKをダウンロード") }
        TextButton(onClick = { open("https://github.com/hirokawa-beach/StudyCoPi/issues") }) { Text("不具合・要望を報告") }
    }
}

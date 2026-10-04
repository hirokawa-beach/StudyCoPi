@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package jp.studycopi

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.media.MediaPlayer
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.nfc.NfcAdapter
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

val wakeDayNames = listOf("月", "火", "水", "木", "金", "土", "日")
fun wakeDaysText(days: Set<Int>) = when {
    days.isEmpty() -> "1回だけ"
    days.size == 7 -> "毎日"
    days == setOf(1, 2, 3, 4, 5) -> "平日"
    else -> days.sorted().joinToString("・") { wakeDayNames[it - 1] }
}

@Composable fun WakeAlarmsScreen(data: StudyData, model: StudyViewModel, edit: (String) -> Unit, notifications: () -> Unit) {
    val context = LocalContext.current
    val notificationAllowed = WakeScheduler.notificationsAllowed(context)
    val exactAllowed = AlarmScheduler.exactAllowed(context)
    val fullScreenAllowed = WakeScheduler.fullScreenAllowed(context)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text("目覚まし", style = MaterialTheme.typography.headlineSmall)
            Text("数学・英語・世界史の問題 → NFCタグ、または追加の問題で解除", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        }
        if (!notificationAllowed || !exactAllowed || !fullScreenAllowed) item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("目覚ましを使うための設定", style = MaterialTheme.typography.titleMedium)
                    if (!notificationAllowed) {
                        Text("通知を許可すると目覚ましをオンにできます。目覚ましの通知チャンネルも有効にしてください。", style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = notifications, modifier = Modifier.fillMaxWidth()) { Text("通知を許可") }
                        TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).putExtra(Settings.EXTRA_CHANNEL_ID, WakeScheduler.CHANNEL)) }) { Text("目覚ましの通知設定") }
                    }
                    if (!exactAllowed && Build.VERSION.SDK_INT >= 31) OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        Uri.parse("package:${context.packageName}"))) }, modifier = Modifier.fillMaxWidth()) { Text("正確なアラームを許可") }
                    if (!fullScreenAllowed && Build.VERSION.SDK_INT >= 34) {
                        Text("ロック中に解除画面を開くには、全画面通知を許可してください。未許可なら通知から解除画面を開けます。", style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                            Uri.parse("package:${context.packageName}"))) }, modifier = Modifier.fillMaxWidth()) { Text("全画面通知を許可") }
                    }
                }
            }
        }
        if (data.wakeRuns.isNotEmpty()) item {
            Button(onClick = { context.startActivity(Intent(context, WakeActivity::class.java)) }, modifier = Modifier.fillMaxWidth()) { Text("鳴っている目覚ましを解除") }
        }
        if (data.wakeAlarms.isEmpty()) item {
            Text("目覚ましはまだありません。右上の「追加」から登録できます。", modifier = Modifier.padding(vertical = 24.dp))
        }
        items(data.wakeAlarms.sortedBy { it.time }, key = { it.id }) { alarm ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).clickable { edit(alarm.id) }) {
                            Text(alarm.time, style = MaterialTheme.typography.displaySmall)
                            Text(alarm.label, style = MaterialTheme.typography.titleMedium)
                        }
                        Switch(checked = alarm.enabled, onCheckedChange = { model.toggleWakeAlarm(alarm.id, it) },
                            enabled = alarm.enabled || notificationAllowed && exactAllowed,
                            modifier = Modifier.semantics { contentDescription = "${alarm.label}をオン・オフ" })
                    }
                    Text("${wakeDaysText(alarm.days)} · ${AlarmSounds.label(alarm.sound)}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                    Text("${wakeSubjectLabel(alarm.questionSubject)} ${alarm.questions}問 → ${if (alarm.secondStep == "nfc") alarm.tagName else "追加${alarm.questions}問"}", style = MaterialTheme.typography.bodySmall)
                    if (alarm.enabled) Text("次回 " + Instant.ofEpochMilli(alarm.nextAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M/d HH:mm")),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { model.testWakeAlarm(alarm.id) }, enabled = notificationAllowed && data.wakeRuns.isEmpty()) { Text("今すぐテスト") }
                        IconButton(onClick = { edit(alarm.id) }) { Icon(Icons.Default.Edit, "${alarm.label}を編集") }
                    }
                }
            }
        }
        item { Text("NFCタグは先に登録し、1階など起床後に行く場所へ置いてください。NFCの読み取りは実機で確認できます。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable fun WakeAlarmForm(existing: WakeAlarm?, data: StudyData, model: StudyViewModel, close: () -> Unit) {
    val context = LocalContext.current
    val id = rememberSaveable { existing?.id ?: newId() }
    var label by rememberSaveable { mutableStateOf(existing?.label ?: "目覚まし") }
    var time by rememberSaveable { mutableStateOf(existing?.time ?: "07:00") }
    var sound by rememberSaveable { mutableStateOf(existing?.sound ?: "system") }
    var preview by remember { mutableStateOf<MediaPlayer?>(null) }
    fun stopPreview() { preview?.release(); preview = null }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) stopPreview() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); stopPreview() }
    }
    var days by rememberSaveable { mutableStateOf(existing?.days?.sorted()?.joinToString(",") ?: "") }
    var mode by rememberSaveable { mutableStateOf(existing?.secondStep ?: "math") }
    var questionSubject by rememberSaveable { mutableStateOf(existing?.questionSubject ?: "math") }
    var questions by rememberSaveable { mutableStateOf(existing?.questions ?: 3) }
    var tagId by rememberSaveable { mutableStateOf(existing?.tagId ?: "") }
    var tagName by rememberSaveable { mutableStateOf(existing?.tagName ?: "1階のタグ") }
    val allowed = WakeScheduler.notificationsAllowed(context) && AlarmScheduler.exactAllowed(context)
    var enabled by rememberSaveable { mutableStateOf(existing?.enabled ?: allowed) }
    var error by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf(false) }
    val registration = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) result.data?.getStringExtra("tagId")?.let { tagId = it; error = "" }
    }
    val soundPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            @Suppress("DEPRECATION")
            val uri = result.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            if (uri != null && AlarmSounds.valid(uri.toString())) sound = uri.toString()
        }
    }
    val audioFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            require(AlarmSounds.valid(uri.toString())) { "この音声ファイルは利用できません" }
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            sound = uri.toString(); error = ""
        }.onFailure { error = "音源を保存できませんでした。別の音源を選んでください" }
    }
    val selectedDays = days.split(',').mapNotNull(String::toIntOrNull).toSet()
    val ringing = data.wakeRuns.any { it.alarmId == id }
    Column(Modifier.fillMaxWidth().heightIn(max = 720.dp).padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (existing == null) "目覚ましを追加" else "目覚ましを編集", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = close) { Text("閉じる") }
        }
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            OutlinedTextField(label, { label = it.take(100) }, label = { Text("目覚ましの名前") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            TimeField("鳴らす時刻", time) { time = it }
            ChoiceField("アラーム音", sound, AlarmSounds.choices + if (sound.startsWith("content://")) listOf(sound to "選択した端末の音源") else emptyList()) { stopPreview(); sound = it }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    stopPreview()
                    soundPicker.launch(Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, if (sound.startsWith("content://")) Uri.parse(sound) else RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)))
                }, modifier = Modifier.weight(1f)) { Text("端末の音") }
                OutlinedButton(onClick = { stopPreview(); audioFile.launch(arrayOf("audio/*")) }, modifier = Modifier.weight(1f)) { Text("音声ファイル") }
            }
            TextButton(onClick = {
                if (preview != null) stopPreview() else runCatching {
                    val player = MediaPlayer(); preview = player
                    player.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
                    AlarmSounds.source(player, context, sound)
                    player.setOnPreparedListener { it.start() }
                    player.setOnCompletionListener { stopPreview() }
                    player.setOnErrorListener { _, _, _ -> stopPreview(); error = "音源を再生できません。選び直してください"; true }
                    player.prepareAsync()
                }.onFailure { stopPreview(); error = "音源を再生できません。選び直してください" }
            }) { Text(if (preview == null) "音を試聴" else "試聴を止める") }
            Text("端末の音源は別のスマホへの復元時に選び直してください。", style = MaterialTheme.typography.bodySmall)
            Text("繰り返し · ${wakeDaysText(selectedDays)}", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                wakeDayNames.forEachIndexed { index, name -> FilterChip(selected = index + 1 in selectedDays, onClick = {
                    val value = index + 1; days = (if (value in selectedDays) selectedDays - value else selectedDays + value).sorted().joinToString(",")
                }, label = { Text(name) }) }
            }
            Row { TextButton(onClick = { days = "1,2,3,4,5" }) { Text("平日") }; TextButton(onClick = { days = "1,2,3,4,5,6,7" }) { Text("毎日") }; TextButton(onClick = { days = "" }) { Text("1回だけ") } }
            ChoiceField("問題の教科", questionSubject, wakeSubjects) { questionSubject = it }
            ChoiceField("問題の数（各ステップ）", questions.toString(), (1..10).map { it.toString() to "${it}問" }) { questions = it.toInt() }
            Text("ステップ1：選んだ教科の問題を解く", style = MaterialTheme.typography.titleSmall)
            ChoiceField("ステップ2の解除方法", mode, listOf("math" to "追加の問題", "nfc" to "登録したNFCタグ")) { mode = it }
            if (mode == "nfc") {
                OutlinedTextField(tagName, { tagName = it.take(100) }, label = { Text("タグを置く場所") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = { registration.launch(Intent(context, NfcRegisterActivity::class.java)) }, modifier = Modifier.fillMaxWidth()) { Text(if (tagId.isEmpty()) "NFCタグを読み取って登録" else "NFCタグを登録し直す") }
                Text(if (tagId.isEmpty()) "未登録" else "登録済み · $tagId", style = MaterialTheme.typography.bodySmall)
                if (NfcAdapter.getDefaultAdapter(context) == null) Text("この端末にはNFCがありません。実機で登録するか、追加の問題を選んでください。", style = MaterialTheme.typography.bodySmall)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("保存してオンにする", Modifier.weight(1f)); Switch(enabled, { enabled = it }, enabled = enabled || allowed)
            }
            if (!allowed) Text("通知と正確なアラームを許可するまでは、オフで保存できます。", style = MaterialTheme.typography.bodySmall)
        }
        if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp))
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (existing != null) TextButton(onClick = { deleting = true }, enabled = !ringing) { Text("削除") }
            OutlinedButton(onClick = close, modifier = Modifier.weight(1f)) { Text("キャンセル") }
            Button(onClick = {
                runCatching {
                    require(!enabled || allowed) { "通知と正確なアラームを許可してください" }
                    val item = WakeAlarm(id, label.trim(), time, selectedDays, mode, questions, tagId, tagName.trim(), enabled, sound = sound, questionSubject = questionSubject)
                    val checked = if (enabled) item.arm(System.currentTimeMillis()) else item
                    WakeBackup.validate(data.copy(wakeAlarms = data.wakeAlarms.filterNot { it.id == id } + checked))
                    model.saveWakeAlarm(item); close()
                }.onFailure { error = it.message ?: "入力を確認してください" }
            }, modifier = Modifier.weight(1f)) { Text("保存") }
        }
    }
    if (deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("目覚ましを削除？") },
        confirmButton = { TextButton(onClick = { model.deleteWakeAlarm(id); close() }) { Text("削除する") } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text("キャンセル") } })
}

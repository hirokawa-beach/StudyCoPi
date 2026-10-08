@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package jp.studycopi

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.math.roundToInt

fun groupChoices(data: StudyData, all: Boolean = false): List<Pair<String, String>> =
    listOf("" to if (all) "すべての試験・学習" else "試験未指定 / 日常の学習") +
        (if (all) listOf("unassigned" to "試験未指定のみ") else emptyList()) +
        data.examGroups.sortedByDescending { it.startDate }.map { it.id to "${it.startDate.take(4)}年・${it.name}" }

@Composable fun ChoiceField(label: String, value: String, choices: List<Pair<String, String>>, change: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(bottom = 6.dp))
        ExposedDropdownMenuBox(expanded, { expanded = !expanded }) {
            OutlinedTextField(value = choices.find { it.first == value }?.second ?: "未選択", onValueChange = {}, readOnly = true,
                singleLine = false, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).semantics { contentDescription = label })
            ExposedDropdownMenu(expanded, { expanded = false }, modifier = Modifier.heightIn(max = 320.dp)) {
                choices.forEach { (key, title) -> DropdownMenuItem(text = { Text(title) }, onClick = { change(key); expanded = false }) }
            }
        }
    }
}
@Composable fun DateField(label: String, value: String, modifier: Modifier = Modifier, change: (String) -> Unit) {
    val context = LocalContext.current
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(bottom = 6.dp))
        OutlinedButton(onClick = {
            val date = LocalDate.parse(value)
            DatePickerDialog(context, { _, year, month, day -> change(LocalDate.of(year, month + 1, day).toString()) }, date.year, date.monthValue - 1, date.dayOfMonth).show()
        }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(12.dp)) { Text(shortDate(value)) }
    }
}
@Composable fun TimeField(label: String, value: String, optional: Boolean = false, change: (String) -> Unit) {
    val context = LocalContext.current
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(bottom = 6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = {
                val time = if (value.isEmpty()) LocalTime.of(9, 0) else LocalTime.parse(value)
                TimePickerDialog(context, { _, hour, minute -> change("%02d:%02d".format(hour, minute)) }, time.hour, time.minute, true).show()
            }, modifier = Modifier.weight(1f).heightIn(min = 56.dp), shape = RoundedCornerShape(12.dp)) { Text(value.ifEmpty { "未指定" }) }
            if (optional && value.isNotEmpty()) TextButton(onClick = { change("") }) { Text("解除") }
        }
    }
}
@Composable fun MinuteField(label: String, value: String, change: (String) -> Unit) {
    OutlinedTextField(value, { change(it) }, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
}
@Composable private fun TextField(label: String, value: String, multiline: Boolean = false, change: (String) -> Unit) {
    OutlinedTextField(value, change, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), singleLine = !multiline, minLines = if (multiline) 2 else 1)
}
@Composable private fun FormFrame(title: String, error: String?, close: () -> Unit, save: () -> Unit,
    delete: (() -> Unit)? = null, enabled: Boolean = true, fields: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(max = 720.dp).padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton(onClick = close) { Text("閉じる") }
        }
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = fields)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp)) }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (delete != null) TextButton(onClick = delete, enabled = enabled) { Text("削除", color = MaterialTheme.colorScheme.error) }
            OutlinedButton(onClick = close, modifier = Modifier.weight(1f)) { Text("キャンセル") }
            Button(onClick = save, enabled = enabled, modifier = Modifier.weight(1f)) { Text("保存") }
        }
    }
}
private fun readableError(e: Throwable): String = when (e) {
    is java.time.format.DateTimeParseException -> "日付・時刻を確認してください"
    else -> e.message ?: "入力内容を確認してください"
}

@Composable fun ScheduleForm(data: StudyData, existing: Schedule?, groupFilter: String, model: StudyViewModel, close: () -> Unit) {
    val id = rememberSaveable { existing?.id ?: newId() }
    var subject by rememberSaveable { mutableStateOf(existing?.subjectId ?: data.subjects.firstOrNull()?.id.orEmpty()) }
    var group by rememberSaveable { mutableStateOf(existing?.examGroupId ?: groupFilter.takeIf { key -> data.examGroups.any { it.id == key } }.orEmpty()) }
    val initial = existing?.let { dateTime(it.datetime) } ?: LocalDateTime.now().withSecond(0).withNano(0)
    var date by rememberSaveable { mutableStateOf(initial.toLocalDate().toString()) }
    var time by rememberSaveable { mutableStateOf(initial.toLocalTime().toString().take(5)) }
    var minutes by rememberSaveable { mutableStateOf(existing?.let { (it.duration * 60).roundToInt().toString() } ?: "60") }
    var content by rememberSaveable { mutableStateOf(existing?.content.orEmpty()) }
    var note by rememberSaveable { mutableStateOf(existing?.note.orEmpty()) }
    var optional by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val protected = data.timer?.scheduleId == id
    FormFrame(if (existing == null) "学習予定を追加" else "学習予定を編集", error, close, save = {
        runCatching {
            val duration = minutes.toDoubleOrNull() ?: error("予定時間を入力してください")
            require(duration in 1.0..1440.0 && data.subjects.any { it.id == subject }) { "教科と予定時間（1〜1440分）を確認してください" }
            val item = (existing ?: Schedule(id = id, subjectId = subject, datetime = "${date}T$time", duration = duration / 60)).copy(
                subjectId = subject, examGroupId = group, datetime = "${date}T$time", duration = duration / 60, content = content.trim(), note = note)
            BackupCodec.validate(data.copy(schedules = data.schedules.filterNot { it.id == id } + item))
            model.saveSchedule(item); close()
        }.onFailure { error = readableError(it) }
    }, delete = if (existing != null) ({ model.deleteSchedule(id); close() }) else null, enabled = !protected && data.subjects.isNotEmpty()) {
        if (protected) Text("タイマーを終了してから編集してください", color = MaterialTheme.colorScheme.error)
        if (data.subjects.isEmpty()) Text("「その他 → 教科」から教科を追加してください")
        ChoiceField("教科", subject, data.subjects.map { it.id to it.name }) { subject = it }
        ChoiceField("対象の試験", group, groupChoices(data)) { group = it }
        DateField("日付", date) { date = it }; TimeField("開始時刻", time) { time = it }
        MinuteField("予定時間（分）", minutes) { minutes = it }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(15, 30, 45, 60).forEach { value -> OutlinedButton(onClick = { minutes = value.toString() }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) { Text("${value}分") } }
        }
        TextField("学習内容（任意）", content) { content = it }
        TextButton(onClick = { optional = !optional }) { Text("${if (optional) "−" else "+"} メモ") }
        if (optional) TextField("メモ（任意）", note, multiline = true) { note = it }
        existing?.let { Text("実績 ${hoursText(it.actual)}。時間の修正は「記録」からできます。", style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable fun RecordForm(item: Schedule, model: StudyViewModel, close: () -> Unit) {
    val data by model.data.collectAsState()
    var status by rememberSaveable { mutableStateOf(if (item.status == "pending") "done" else item.status) }
    var minutes by rememberSaveable { mutableStateOf(((item.actualDuration ?: if (item.status == "done" || item.status == "pending") item.duration else 0.0) * 60).let { if (it % 1.0 == 0.0) it.toInt().toString() else "%.2f".format(java.util.Locale.US, it) }) }
    var note by rememberSaveable { mutableStateOf(item.note) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    FormFrame("実績を記録", error, close, save = {
        runCatching {
            val value = minutes.toDoubleOrNull() ?: error("実績時間を入力してください")
            require(value.isFinite() && value >= 0) { "実績時間は0分以上にしてください" }
            model.record(item.id, status, value, note); close()
        }.onFailure { error = readableError(it) }
    }, enabled = data.timer?.scheduleId != item.id) {
        Text("${data.subject(item.subjectId).name}・予定 ${hoursText(item.duration)}", fontWeight = FontWeight.SemiBold)
        if (data.timer?.scheduleId == item.id) Text("タイマーを終了してから記録してください", color = MaterialTheme.colorScheme.error)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("done" to "完了", "partial" to "一部", "miss" to "未実施").forEach { (key, title) ->
                FilterChip(selected = status == key, onClick = { status = key; if (key == "miss") minutes = "0" }, label = { Text(title) }, modifier = Modifier.weight(1f))
            }
        }
        MinuteField("勉強した時間（分）", minutes) { minutes = it }
        TextField("メモ（任意）", note, multiline = true) { note = it }
        Text("0分も記録できます。予定時間と実績時間は別々に保存します。", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable fun GroupForm(data: StudyData, existing: ExamGroup?, model: StudyViewModel, close: () -> Unit) {
    val id = rememberSaveable { existing?.id ?: newId() }
    var name by rememberSaveable { mutableStateOf(existing?.name.orEmpty()) }
    var type by rememberSaveable { mutableStateOf(existing?.type ?: "定期考査") }
    var start by rememberSaveable { mutableStateOf(existing?.startDate ?: LocalDate.now().toString()) }
    var end by rememberSaveable { mutableStateOf(existing?.endDate ?: LocalDate.now().toString()) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    FormFrame(if (existing == null) "試験を追加" else "試験を編集", error, close, save = {
        runCatching {
            require(name.isNotBlank()) { "試験名を入力してください" }
            val group = ExamGroup(id, name.trim(), type, start, end)
            BackupCodec.validate(data.copy(examGroups = data.examGroups.filterNot { it.id == id } + group))
            require(data.exams.none { it.examGroupId == id && (it.date < start || it.date > end) }) { "教科別日程が期間から外れています" }
            model.saveGroup(group); close()
        }.onFailure { error = readableError(it) }
    }, delete = if (existing != null) ({ deleting = true }) else null) {
        TextField("試験名", name) { name = it }
        ChoiceField("種類", type, examTypes.map { it to it }) { type = it }
        DateField("開始日", start) { start = it; if (end < start) end = start }
        DateField("終了日", end) { end = it }
        Text("例：2学期中間考査、第2回模試。1回ずつ登録すると、学習時間を分けて振り返れます。", style = MaterialTheme.typography.bodySmall)
    }
    if (deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("試験を削除しますか？") },
        text = { Text("学習記録と教科別日程は、試験未指定として残します。") },
        confirmButton = { TextButton(onClick = {
            if (data.timer?.examGroupId == id) { error = "この試験のタイマーを終了してください"; deleting = false }
            else { model.deleteGroup(id); close() }
        }) { Text("削除する") } }, dismissButton = { TextButton(onClick = { deleting = false }) { Text("キャンセル") } })
}

@Composable fun ExamForm(data: StudyData, existing: ExamSession?, initialGroup: String, model: StudyViewModel, close: () -> Unit) {
    val id = rememberSaveable { existing?.id ?: newId() }
    var group by rememberSaveable { mutableStateOf(existing?.examGroupId ?: initialGroup) }
    var subject by rememberSaveable { mutableStateOf(existing?.subject.orEmpty()) }
    var date by rememberSaveable { mutableStateOf(existing?.date ?: data.examGroups.find { it.id == initialGroup }?.startDate ?: LocalDate.now().toString()) }
    var start by rememberSaveable { mutableStateOf(existing?.startTime.orEmpty()) }
    var end by rememberSaveable { mutableStateOf(existing?.endTime.orEmpty()) }
    var range by rememberSaveable { mutableStateOf(existing?.range.orEmpty()) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    FormFrame(if (existing == null) "教科の日程を追加" else "教科の日程を編集", error, close, save = {
        runCatching {
            require(subject.isNotBlank()) { "教科を入力してください" }
            val g = data.examGroups.find { it.id == group }
            require(g == null || date in g.startDate..g.endDate) { "試験期間内の日付を選んでください" }
            require(start.isEmpty() || end.isEmpty() || end > start) { "終了時刻は開始時刻より後にしてください" }
            val exam = ExamSession(id, subject.trim(), date, start, end, range, group, existing?.checklist.orEmpty())
            BackupCodec.validate(data.copy(exams = data.exams.filterNot { it.id == id } + exam))
            model.saveExam(exam); close()
        }.onFailure { error = readableError(it) }
    }, delete = if (existing != null) ({ model.deleteExam(id); close() }) else null) {
        ChoiceField("対象の試験", group, groupChoices(data)) { key ->
            group = key; data.examGroups.find { it.id == key }?.let { if (date !in it.startDate..it.endDate) date = it.startDate }
        }
        TextField("教科名", subject) { subject = it }
        DateField("日付", date) { date = it }
        TimeField("開始時刻（任意）", start, optional = true) { start = it }
        TimeField("終了時刻（任意）", end, optional = true) { end = it }
        TextField("試験範囲・メモ（任意）", range, multiline = true) { range = it }
        Text("保存後、教科の日程にある「範囲チェックリスト」から、単元やページごとの進捗を管理できます。", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable fun SubjectForm(existing: Subject?, model: StudyViewModel, close: () -> Unit) {
    val data by model.data.collectAsState()
    val id = rememberSaveable { existing?.id ?: newId() }
    var name by rememberSaveable { mutableStateOf(existing?.name.orEmpty()) }
    var color by rememberSaveable { mutableStateOf(existing?.color ?: subjectColors.first()) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val used = data.schedules.any { it.subjectId == id } || data.timer?.subjectId == id
    FormFrame(if (existing == null) "教科を追加" else "教科を編集", error, close, save = {
        if (name.isBlank()) error = "教科名を入力してください" else { model.saveSubject(Subject(id, name.trim(), color)); close() }
    }, delete = if (existing != null && !used && data.subjects.size > 1) ({ model.deleteSubject(id); close() }) else null) {
        TextField("教科名", name) { name = it }
        Text("識別用の色", style = MaterialTheme.typography.labelMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (subjectColors + color).distinct().forEach { value ->
                Box(Modifier.size(48.dp).border(if (color == value) 3.dp else 1.dp, if (color == value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    RoundedCornerShape(8.dp)).padding(6.dp).background(Color(android.graphics.Color.parseColor(value)), RoundedCornerShape(4.dp))
                    .clickable { color = value }.semantics { contentDescription = "色 $value" })
            }
        }
        if (used) Text("学習記録に使われている教科は削除できません。", style = MaterialTheme.typography.bodySmall)
    }
}

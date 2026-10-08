package jp.studycopi

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable fun ChecklistSheet(exam: ExamSession, groupName: String, model: StudyViewModel, close: () -> Unit) {
    var additions by rememberSaveable(exam.id) { mutableStateOf("") }
    var filter by rememberSaveable(exam.id) { mutableStateOf("all") }
    var editingId by rememberSaveable(exam.id) { mutableStateOf("") }
    var title by rememberSaveable(exam.id) { mutableStateOf("") }
    var menuId by remember { mutableStateOf("") }
    var adding by rememberSaveable(exam.id) { mutableStateOf(exam.checklist.isEmpty()) }
    var rangeOpen by rememberSaveable(exam.id) { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val done = exam.checklist.count { it.status == "done" }
    val review = exam.checklist.count { it.status == "review" }
    val remaining = exam.checklist.size - done
    val visible = exam.checklist.filter { filter == "all" || filter == "remaining" && it.status != "done" || filter == "review" && it.status == "review" }
    val lines = additions.lines().map(String::trim).filter(String::isNotEmpty)
    val validAdditions = lines.isNotEmpty() && lines.all { it.length <= 300 } && exam.checklist.size + lines.size <= 200
    fun edit(item: ChecklistItem) {
        if (editingId == item.id) editingId = "" else {
            editingId = item.id; title = item.title
            scope.launch { listState.animateScrollToItem(visible.indexOf(item).coerceAtLeast(0) + (if (adding) 1 else 0) + (if (rangeOpen) 1 else 0)) }
        }
    }
    Column(Modifier.fillMaxWidth().heightIn(max = 720.dp)) {
        Column(Modifier.fillMaxWidth().testTag("checklist-overview").padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("試験範囲チェックリスト", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = close) { Text("閉じる") }
            }
            Text("$groupName · ${exam.subject}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("全${exam.checklist.size}件 · 完了${done}件 · 未完了${remaining}件 · 要復習${review}件", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
            LinearProgressIndicator(progress = { if (exam.checklist.isEmpty()) 0f else done.toFloat() / exam.checklist.size }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("all" to "すべて", "remaining" to "未完了", "review" to "要復習").forEach { (key, label) ->
                    FilterChip(selected = filter == key, onClick = { filter = key; editingId = ""; menuId = ""; scope.launch { listState.scrollToItem(0) } }, label = { Text(label) }, modifier = Modifier.weight(1f).heightIn(min = 44.dp).testTag("checklist-filter-$key"))
                }
            }
            Row {
                TextButton(onClick = { adding = !adding; if (adding) scope.launch { listState.scrollToItem(0) } }) { Text(if (adding) "入力欄を閉じる" else "範囲を追加") }
                if (exam.range.isNotBlank()) TextButton(onClick = { rangeOpen = !rangeOpen; if (rangeOpen) scope.launch { listState.scrollToItem(0) } }) { Text(if (rangeOpen) "メモを閉じる" else "範囲メモ") }
            }
        }
        HorizontalDivider()
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 520.dp).testTag("checklist-list"), state = listState, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
            if (rangeOpen) item { Text(exam.range, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(8.dp)) }
            if (adding) item {
                Column(Modifier.padding(4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("単元・ページを1行ずつ入力すると、まとめて追加できます。", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(additions, { additions = it }, label = { Text("範囲を追加（1行1項目）") }, placeholder = { Text("問題集 p.20〜25\n問題集 p.26〜30") }, minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth())
                    if (lines.any { it.length > 300 }) Text("1項目は300文字までです。", color = MaterialTheme.colorScheme.error)
                    if (exam.checklist.size + lines.size > 200) Text("チェックリストは200件までです。", color = MaterialTheme.colorScheme.error)
                    Button(onClick = {
                        saving = true
                        model.addChecklistItems(exam.id, additions) { saved -> saving = false; if (saved) { additions = ""; adding = false } }
                    }, enabled = validAdditions && !saving, modifier = Modifier.fillMaxWidth()) { Text("追加する") }
                }
            }
            items(visible, key = { it.id }) { item ->
                Column(Modifier.fillMaxWidth().testTag("checklist-item-${item.id}")) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { edit(item) }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = item.status == "done", onCheckedChange = { model.updateChecklistItem(exam.id, item.id, status = if (it) "done" else "pending") }, modifier = Modifier.testTag("check-${item.id}"))
                        Text(item.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(end = 4.dp))
                        Box(Modifier.testTag("checklist-status-${item.id}")) {
                            TextButton(onClick = { menuId = item.id }, contentPadding = PaddingValues(horizontal = 8.dp), modifier = Modifier.widthIn(min = 64.dp).heightIn(min = 44.dp)) {
                                Text(checklistStatuses.first { it.first == item.status }.second, style = MaterialTheme.typography.labelLarge)
                            }
                            DropdownMenu(expanded = menuId == item.id, onDismissRequest = { menuId = "" }) {
                                checklistStatuses.forEach { (key, label) -> DropdownMenuItem(text = { Text(label) }, modifier = Modifier.testTag("checklist-option-${item.id}-$key"), onClick = {
                                    menuId = ""; model.updateChecklistItem(exam.id, item.id, status = key)
                                }) }
                            }
                        }
                    }
                    if (editingId == item.id) Column(Modifier.padding(start = 48.dp, end = 8.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedTextField(title, { title = it }, label = { Text("範囲の名前") }, maxLines = 3, modifier = Modifier.fillMaxWidth())
                        Row {
                            TextButton(onClick = { model.updateChecklistItem(exam.id, item.id, title = title); editingId = "" }, enabled = title.isNotBlank() && title.trim().length <= 300) { Text("保存") }
                            TextButton(onClick = { editingId = "" }) { Text("キャンセル") }
                            TextButton(onClick = { model.deleteChecklistItem(exam.id, item.id); editingId = "" }) { Text("削除") }
                        }
                    }
                    HorizontalDivider()
                }
            }
            if (exam.checklist.isEmpty()) item { Text("まだ範囲がありません。問題集のページや単元を追加してください。", modifier = Modifier.padding(8.dp)) }
            else if (visible.isEmpty()) item { Text("この状況の項目はありません。", modifier = Modifier.padding(8.dp)) }
        }
    }
}

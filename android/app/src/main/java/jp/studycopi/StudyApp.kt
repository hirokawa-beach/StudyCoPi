@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package jp.studycopi

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.*
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.time.*
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

@Composable fun StudyApp(model: StudyViewModel) {
    val data by model.data.collectAsState()
    val ready by model.ready.collectAsState()
    val loadError by model.loadError.collectAsState()
    val point by model.now.collectAsState()
    val requestedScreen by model.requestedScreen.collectAsState()
    var screen by rememberSaveable { mutableStateOf("today") }
    var examFilter by rememberSaveable { mutableStateOf("") }
    var sheet by rememberSaveable { mutableStateOf("") }
    var sheetId by rememberSaveable { mutableStateOf("") }
    var importUri by remember { mutableStateOf<Uri?>(null) }
    val context = LocalContext.current
    val guide = remember { GuideContent.load(context) }
    var guideVisible by rememberSaveable { mutableStateOf(false) }
    var guideStep by rememberSaveable { mutableIntStateOf(0) }
    val guideBounds = remember { mutableStateMapOf<String, androidx.compose.ui.geometry.Rect>() }
    val displayScreen = if (guideVisible && data.wakeRuns.isEmpty()) listOf("exams", "plans", "timer", "plans", "stats")[guideStep] else screen
    val displayData = if (guideVisible && guideStep == 3) data.copy(schedules = listOf(Schedule(
        id = "guide-example", subjectId = data.subjects.firstOrNull()?.id ?: "s1", datetime = "${LocalDate.now()}T17:00",
        duration = .75, content = "問題集 p.24〜28（操作例・保存されません）"))) else data
    LaunchedEffect(ready, data.timer?.id, data.wakeRuns.isEmpty()) {
        if (ready && loadError == null && data.timer == null && data.wakeRuns.isEmpty() && !GuideContent.seen(context, guide.version)) guideVisible = true
    }
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { model.resume() }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { it?.let(model::export) }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { importUri = it }
    LaunchedEffect(model) { model.notices.collect { notice ->
        scope.launch {
            if (snack.showSnackbar(notice.text, actionLabel = if (notice.undo != null) "元に戻す" else null,
                    duration = if (notice.undo != null) SnackbarDuration.Long else SnackbarDuration.Short) == SnackbarResult.ActionPerformed) model.undo(notice)
        }
    } }
    fun open(kind: String, id: String = "") { sheetId = id; sheet = kind }
    fun requestNotifications() {
        if (AlarmScheduler.notificationsAllowed(context)) return
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
    }
    var previousTimer by remember { mutableStateOf(data.timer?.id) }
    LaunchedEffect(data.timer?.id) {
        if (data.timer != null && data.timer?.id != previousTimer && data.preferences.timerNotification) requestNotifications()
        previousTimer = data.timer?.id
    }
    LaunchedEffect(requestedScreen) { requestedScreen?.let { screen = it; model.requestedScreen.value = null } }
    LaunchedEffect(data.examGroups) {
        if (examFilter !in listOf("", "unassigned") && data.examGroups.none { it.id == examFilter }) examFilter = ""
    }
    BackHandler(enabled = screen != "today" && sheet.isEmpty()) { screen = "today" }
    val nav = listOf("today" to "今日", "plans" to "予定", "exams" to "試験", "stats" to "統計", "more" to "その他")
    val navIcons = listOf(Icons.Default.Home, Icons.Default.DateRange, Icons.Default.Star, Icons.AutoMirrored.Filled.List, Icons.Default.MoreVert)
    CompositionLocalProvider(LocalGuideBounds provides { key, bounds -> guideBounds[key] = bounds }) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background, snackbarHost = { SnackbarHost(snack) },
        topBar = { TopAppBar(title = { Text("StudyCoPi", style = MaterialTheme.typography.titleMedium) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            navigationIcon = { if (displayScreen !in nav.map { it.first }) IconButton(onClick = { screen = "more" }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "戻る") } },
            actions = {
                IconButton(onClick = { screen = "timer" }) { Icon(Icons.Default.PlayArrow, "集中タイマーを開く") }
                if (ready && loadError == null && displayScreen in listOf("today", "plans", "exams", "wake")) {
                    FilledTonalButton(onClick = { open(when (displayScreen) { "exams" -> "group"; "wake" -> "wake"; else -> "schedule" }) },
                        modifier = Modifier.padding(end = 12.dp).guideTarget("add"), contentPadding = PaddingValues(horizontal = 12.dp)) {
                        Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("追加")
                    }
                }
            }) },
        bottomBar = { Column {
            if (data.timer != null && displayScreen != "timer") {
                val timer = data.timer!!
                Surface(onClick = { screen = "timer" }, color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary, shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).semantics { contentDescription = "タイマーに戻る" }) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.PlayArrow, null, modifier = Modifier.padding(end = 10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(data.subject(timer.subjectId).name, style = MaterialTheme.typography.titleSmall)
                            Text(if (timer.paused) "一時停止中" else "計測中", style = MaterialTheme.typography.labelSmall)
                        }
                        Text(timerText(timer.remaining(point)), style = MaterialTheme.typography.titleLarge)
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                    }
                }
            }
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                nav.forEachIndexed { index, (key, label) -> NavigationBarItem(selected = displayScreen == key || (displayScreen == "timer" && key == "today") || (displayScreen !in nav.map { it.first } && displayScreen != "timer" && key == "more"),
                    onClick = { screen = key }, icon = { Icon(navIcons[index], null) }, label = { Text(label) }) }
            }
        } }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                loadError != null -> Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(loadError!!); Button(onClick = { import.launch(arrayOf("application/json", "text/*", "application/octet-stream")) }) { Text("バックアップから復元") }
                }
                !ready -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                displayScreen == "today" -> TodayScreen(data, model, { open("schedule", it) }, { open("record", it) },
                    { screen = "timer" }, { id -> examFilter = id; screen = "stats" }, { open("group") })
                displayScreen == "plans" -> key(if (guideVisible) "tour-$guideStep" else "plans") {
                    PlansScreen(displayData, model, if (guideVisible) "" else examFilter, { examFilter = it },
                        { open("schedule", it) }, { open("record", it) }, { screen = "timer" }, { open("exam", it) })
                }
                displayScreen == "exams" -> ExamsScreen(data, { open("group", it) }, { open("exam", it) },
                    { examFilter = it; screen = "stats" }, { examFilter = it; screen = "plans" }, { open("newExam", it) })
                displayScreen == "stats" -> StatsScreen(data, examFilter, { examFilter = it })
                displayScreen == "timer" -> TimerScreen(data, point, model, { requestNotifications() })
                displayScreen == "subjects" -> SubjectsScreen(data, model, { open("subject", it) })
                displayScreen == "settings" -> SettingsScreen(data, model, { requestNotifications() })
                displayScreen == "wake" -> WakeAlarmsScreen(data, model, { open("wake", it) }, { requestNotifications() })
                displayScreen == "backup" -> BackupScreen(data, { export.launch("StudyCoPi-${LocalDate.now()}.json") },
                    { import.launch(arrayOf("application/json", "text/*", "application/octet-stream")) })
                displayScreen == "help" -> StudyHelpScreen(guide, { guideStep = 0; guideVisible = true }, { screen = it })
                else -> MoreScreen { screen = it }
            }
        }
    }
    }
    if (guideVisible && ready && data.wakeRuns.isEmpty()) StudyGuideDialog(guide, guideStep, guideBounds[listOf("add", "add", "timer-start", "record", "stats-filter")[guideStep]], { guideStep = it }) {
        GuideContent.markSeen(context, guide.version); guideVisible = false
    }
    if (sheet.isNotEmpty() && ready) {
        ModalBottomSheet(onDismissRequest = { sheet = "" }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Box(Modifier.fillMaxWidth().imePadding()) {
                val close = { sheet = "" }
                when (sheet) {
                    "schedule" -> ScheduleForm(data, data.schedules.find { it.id == sheetId }, examFilter, model, close)
                    "record" -> data.schedules.find { it.id == sheetId }?.let { RecordForm(it, model, close) }
                    "group" -> GroupForm(data, data.examGroups.find { it.id == sheetId }, model, close)
                    "exam" -> ExamForm(data, data.exams.find { it.id == sheetId }, "", model, close)
                    "newExam" -> ExamForm(data, null, sheetId, model, close)
                    "subject" -> SubjectForm(data.subjects.find { it.id == sheetId }, model, close)
                    "wake" -> WakeAlarmForm(data.wakeAlarms.find { it.id == sheetId }, data, model, close)
                }
            }
        }
    }
    importUri?.let { uri -> AlertDialog(onDismissRequest = { importUri = null }, title = { Text("バックアップを復元") },
        text = { Text("現在の教科・試験・学習記録を、選んだファイルの内容で置き換えます。先にバックアップを保存することをおすすめします。") },
        confirmButton = { TextButton(onClick = { model.import(uri); importUri = null }) { Text("復元する") } },
        dismissButton = { TextButton(onClick = { importUri = null }) { Text("キャンセル") } }) }
}

@Composable private fun PageTitle(title: String, subtitle: String? = null) {
    Column(Modifier.padding(top = 4.dp, bottom = 20.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)) }
    }
}
@Composable private fun EmptyMessage(text: String, action: String? = null, onClick: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
        action?.let { OutlinedButton(onClick = onClick) { Text(it) } }
    }
}
@Composable private fun StudyRow(item: Schedule, data: StudyData, model: StudyViewModel,
    edit: (String) -> Unit, record: (String) -> Unit, timer: () -> Unit) {
    val subject = data.subject(item.subjectId)
    // The card measures its content; a five-minute plan has the same readable name as a long plan.
    Card(Modifier.fillMaxWidth().padding(bottom = 10.dp).testTag(if (item.id == "guide-example") "guide-target-record" else "schedule-${item.id}").then(if (item.id == "guide-example") Modifier.guideTarget("record") else Modifier),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f).clickable { edit(item.id) }.padding(end = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(8.dp).background(Color(android.graphics.Color.parseColor(subject.color)), CircleShape))
                        Text(dateTime(item.datetime).format(DateTimeFormatter.ofPattern("HH:mm")) + " · " + hoursText(item.duration),
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(subject.name, style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 5.dp).testTag("subject-${item.id}"))
                }
                Checkbox(checked = item.status == "done", onCheckedChange = { model.complete(item.id) },
                    modifier = Modifier.size(48.dp).semantics { contentDescription = "${subject.name}の完了を切り替え" })
            }
            if (item.content.isNotEmpty()) Text(item.content, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp).clickable { edit(item.id) })
            if (item.examGroupId.isNotEmpty()) Text(data.groupName(item.examGroupId), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            if (item.actualDuration != null || item.status != "pending") Text(
                when (item.status) { "done" -> "完了"; "partial" -> "一部完了"; "miss" -> "未実施"; else -> "計測済み" } + " · 実績 ${hoursText(item.actual)}",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 6.dp))
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(onClick = { edit(item.id) }) { Icon(Icons.Default.Edit, "${subject.name}の予定を編集", modifier = Modifier.size(20.dp)) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { record(item.id) }) { Text("記録") }
                if (!item.finished) FilledTonalButton(onClick = {
                    model.startTimer(item.subjectId, item.examGroupId, (item.duration * 60).roundToInt().coerceAtLeast(1), item.id); timer()
                }) { Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("開始") }
            }
        }
    }
}

@Composable private fun TodayScreen(data: StudyData, model: StudyViewModel, edit: (String) -> Unit,
    record: (String) -> Unit, timer: () -> Unit, stats: (String) -> Unit, addGroup: () -> Unit) {
    val today = LocalDate.now()
    val items = data.schedules.filter { it.date == today }.sortedBy { it.datetime }
    val pending = items.filterNot { it.finished }
    val finished = items.filter { it.finished }
    var expanded by rememberSaveable { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 96.dp)) {
        item { PageTitle("今日の学習", today.format(DateTimeFormatter.ofPattern("M月d日 E曜日", java.util.Locale.JAPANESE))) }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.fillMaxWidth().padding(20.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Metric("今日の勉強", hoursText(items.sumOf { it.actual }), Modifier.weight(1f))
                        Metric("予定時間", hoursText(items.sumOf { it.duration }), Modifier.weight(1f))
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        LinearProgressIndicator(progress = { if (items.isEmpty()) 0f else items.count { it.status == "done" }.toFloat() / items.size },
                            modifier = Modifier.weight(1f).height(6.dp), trackColor = MaterialTheme.colorScheme.primary.copy(alpha = .12f))
                        Text("${items.count { it.status == "done" }} / ${items.size}件 完了", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("これからの予定", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = timer) { Text("タイマー") }
            }
        }
        if (items.isEmpty()) item { EmptyMessage("今日の予定はありません", "予定を追加") { edit("") } }
        if (items.isNotEmpty() && pending.isEmpty()) item { EmptyMessage("今日の予定は完了しました") }
        items(pending, key = { "today-${it.id}" }) { StudyRow(it, data, model, edit, record, timer) }
        if (finished.isNotEmpty()) {
            item { TextButton(onClick = { expanded = !expanded }) { Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null); Text("完了・未実施 ${finished.size}件") } }
            if (expanded) items(finished, key = { "done-${it.id}" }) { StudyRow(it, data, model, edit, record, timer) }
        }
        item {
            val group = data.examGroups.filter { it.endDate >= today.toString() }.minByOrNull { it.startDate }
            Text("試験の準備", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 20.dp, bottom = 12.dp))
            Card(onClick = { if (group != null) stats(group.id) else addGroup() },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.fillMaxWidth().padding(18.dp)) {
                    Text(group?.let { if (it.startDate <= today.toString()) "試験期間中" else "あと ${java.time.temporal.ChronoUnit.DAYS.between(today, LocalDate.parse(it.startDate))}日" } ?: "次の試験", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(group?.name ?: "考査・模試を登録", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                    }
                    if (group != null) Text(shortDate(group.startDate), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val start = weekStart(today)
            TextButton(onClick = { stats("") }, modifier = Modifier.padding(top = 8.dp)) {
                Text("今週の勉強 ${hoursText(data.schedules.filter { it.date >= start && it.date < start.plusDays(7) }.sumOf { it.actual })}")
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
            }
        }
    }
}

@Composable private fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier) {
        val size = if (maxWidth < 128.dp) 18 else 24
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, fontWeight = FontWeight.SemiBold, fontSize = size.sp, lineHeight = (size + 8).sp)
        }
    }
}

@Composable private fun PlansScreen(data: StudyData, model: StudyViewModel, examFilter: String, filter: (String) -> Unit,
    edit: (String) -> Unit, record: (String) -> Unit, timer: () -> Unit, editExam: (String) -> Unit) {
    var filtersOpen by rememberSaveable { mutableStateOf(false) }
    var subject by rememberSaveable { mutableStateOf("") }
    var status by rememberSaveable { mutableStateOf("") }
    var pastOpen by rememberSaveable { mutableStateOf(false) }
    var weekly by rememberSaveable { mutableStateOf(false) }
    var weekOffset by rememberSaveable { mutableIntStateOf(0) }
    var dayOffset by rememberSaveable { mutableIntStateOf(LocalDate.now().dayOfWeek.value - 1) }
    val week = weekStart(LocalDate.now()).plusWeeks(weekOffset.toLong())
    val items = data.forExam(examFilter).filter { (subject.isEmpty() || it.subjectId == subject) && (status.isEmpty() || it.status == status) }
    val exams = data.exams.filter { (examFilter.isEmpty() || if (examFilter == "unassigned") it.examGroupId.isEmpty() else it.examGroupId == examFilter) &&
        (subject.isEmpty() || data.subject(subject).name == it.subject) && status.isEmpty() }
    val dates = (items.map { it.date } + exams.map { LocalDate.parse(it.date) }).distinct().sorted()
    val visibleItems = if (weekly) items.filter { it.date in week..week.plusDays(6) } else items
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 96.dp)) {
        item {
            PageTitle(if (weekly) "週間の予定" else "予定一覧")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(selected = !weekly, onClick = { weekly = false }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("一覧") }
                SegmentedButton(selected = weekly, onClick = { weekly = true }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("週間表示") }
            }
            TextButton(onClick = { filtersOpen = !filtersOpen }) {
                Icon(Icons.Default.Search, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                Text("絞り込み" + if (examFilter.isNotEmpty()) "・${data.groupName(examFilter)}" else "")
                Icon(if (filtersOpen) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null)
            }
            if (filtersOpen) Column(Modifier.padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ChoiceField("対象の試験", examFilter, groupChoices(data, all = true), filter)
                ChoiceField("教科", subject, listOf("" to "すべての教科") + data.subjects.map { it.id to it.name }) { subject = it }
                ChoiceField("状況", status, listOf("" to "すべての状況", "pending" to "未記録", "partial" to "一部完了", "done" to "完了", "miss" to "未実施")) { status = it }
            }
            if (weekly) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { weekOffset-- }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "前週") }
                    Text("${week.monthValue}/${week.dayOfMonth}〜${week.plusDays(6).monthValue}/${week.plusDays(6).dayOfMonth}", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    TextButton(onClick = { weekOffset = 0; dayOffset = LocalDate.now().dayOfWeek.value - 1 }) { Text("今週") }
                    IconButton(onClick = { weekOffset++ }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "次週") }
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    repeat(7) { index ->
                        val date = week.plusDays(index.toLong())
                        val count = items.count { it.date == date } + exams.count { it.date == date.toString() }
                        val selected = dayOffset == index
                        Surface(onClick = { dayOffset = index }, shape = RoundedCornerShape(14.dp),
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                            contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f).widthIn(min = 40.dp).semantics { contentDescription = "${date}の予定を表示" }) {
                            Column(Modifier.padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(date.format(DateTimeFormatter.ofPattern("E", java.util.Locale.JAPANESE)), style = MaterialTheme.typography.labelSmall)
                                Text("${date.dayOfMonth}", style = MaterialTheme.typography.titleMedium)
                                Box(Modifier.padding(top = 4.dp).size(4.dp).background(
                                    if (count > 0) if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape))
                            }
                        }
                    }
                }
            }
            Text("${if (weekly) "この週 " else ""}${visibleItems.size}件 · 予定 ${hoursText(visibleItems.sumOf { it.duration })} · 実績 ${hoursText(visibleItems.sumOf { it.actual })}",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 14.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val activeDates = if (weekly) listOf(week.plusDays(dayOffset.toLong())) else dates.filter { it >= LocalDate.now() }
        if (activeDates.isEmpty()) item { EmptyMessage("この条件の予定はありません", "予定を追加") { edit("") } }
        fun LazyListScope.day(date: LocalDate) {
            item(key = "date-$date") { Text(date.format(DateTimeFormatter.ofPattern("M月d日（E）", java.util.Locale.JAPANESE)), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp, bottom = 12.dp)) }
            val dayItems = items.filter { it.date == date }.sortedBy { it.datetime }
            items(dayItems, key = { "plan-${it.id}" }) { StudyRow(it, data, model, edit, record, timer) }
            items(exams.filter { it.date == date.toString() }.sortedBy { it.startTime }, key = { "exam-${it.id}" }) { ExamRow(it, editExam) }
            if (dayItems.isEmpty() && exams.none { it.date == date.toString() }) item { EmptyMessage("予定はありません", "予定を追加") { edit("") } }
        }
        activeDates.forEach { day(it) }
        if (!weekly && dates.any { it < LocalDate.now() }) {
            item { TextButton(onClick = { pastOpen = !pastOpen }) { Text("${if (pastOpen) "−" else "+"} 過去の予定") } }
            if (pastOpen) dates.filter { it < LocalDate.now() }.reversed().forEach { day(it) }
        }
    }
}

@Composable private fun ExamsScreen(data: StudyData, edit: (String) -> Unit, editExam: (String) -> Unit,
    stats: (String) -> Unit, plans: (String) -> Unit, addExam: (String) -> Unit) {
    var year by rememberSaveable { mutableStateOf("") }
    var expanded by rememberSaveable { mutableStateOf("") }
    val today = LocalDate.now().toString()
    val groups = data.examGroups.filter { year.isEmpty() || it.startDate.startsWith(year) }.sortedWith(
        compareBy<ExamGroup> { it.endDate < today }.thenComparator { a, b -> if (a.endDate < today) b.startDate.compareTo(a.startDate) else a.startDate.compareTo(b.startDate) })
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 96.dp)) {
        item {
            PageTitle("試験ごとの管理", "考査・模試ごとに、準備を振り返る")
            ChoiceField("開催年", year, listOf("" to "すべての年") + data.examGroups.map { it.startDate.take(4) }.distinct().sortedDescending().map { it to "${it}年" }) { year = it }
            Spacer(Modifier.height(20.dp))
        }
        if (groups.isEmpty()) item { EmptyMessage("試験はまだ登録されていません。「追加」から登録できます。") }
        items(groups, key = { it.id }) { group ->
            val schedules = data.forExam(group.id)
            val sessions = data.exams.filter { it.examGroupId == group.id }.sortedBy { it.date + it.startTime }
            Card(Modifier.fillMaxWidth().padding(bottom = 16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(18.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(group.type, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        Text(if (group.endDate < today) "終了" else if (group.startDate <= today) "試験期間中" else "あと ${java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), LocalDate.parse(group.startDate))}日",
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    Text(group.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp))
                    Text(shortDate(group.startDate) + if (group.endDate != group.startDate) "〜${shortDate(group.endDate)}" else "",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth().padding(vertical = 18.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Metric("準備の実績", hoursText(schedules.sumOf { it.actual }), Modifier.weight(1f))
                        Metric("予定時間", hoursText(schedules.sumOf { it.duration }), Modifier.weight(1f))
                    }
                    Text("${schedules.count { it.status == "done" }} / ${schedules.size}件 完了", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { plans(group.id) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(8.dp)) { Text("学習予定") }
                        TextButton(onClick = { stats(group.id) }, contentPadding = PaddingValues(8.dp)) { Text("統計") }
                        IconButton(onClick = { edit(group.id) }) { Icon(Icons.Default.Edit, "${group.name}を編集") }
                    }
                    TextButton(onClick = { expanded = if (expanded == group.id) "" else group.id }) {
                        Text("教科別の日程 ${sessions.size}件"); Icon(if (expanded == group.id) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null)
                    }
                    if (expanded == group.id) {
                        sessions.forEach { ExamRow(it, editExam) }
                        OutlinedButton(onClick = { addExam(group.id) }) { Text("教科の日程を追加") }
                    }
                }
            }
        }
        item {
            Text("試験未指定の記録", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
            Text("日常の学習や以前の記録も、ここから確認できます。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { plans("unassigned") }) { Text("未指定の学習を見る"); Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) }
            data.exams.filter { it.examGroupId.isEmpty() }.forEach { ExamRow(it, editExam) }
            TextButton(onClick = { addExam("") }) { Text("未指定の教科日程を追加") }
        }
    }
}

@Composable private fun ExamRow(exam: ExamSession, edit: (String) -> Unit) {
    Card(onClick = { edit(exam.id) }, modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("試験 · ${shortDate(exam.date)} ${exam.startTime}${if (exam.endTime.isNotEmpty()) "〜${exam.endTime}" else ""}",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(exam.subject, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 6.dp))
                if (exam.range.isNotEmpty()) Text(exam.range, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "${exam.subject}の日程を編集")
        }
    }
}

@Composable private fun StatsScreen(data: StudyData, examFilter: String, filter: (String) -> Unit) {
    var period by rememberSaveable { mutableStateOf("all") }
    var from by rememberSaveable { mutableStateOf(weekStart(LocalDate.now()).toString()) }
    var to by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    val today = LocalDate.now()
    val list = data.forExam(examFilter).filter {
        when (period) {
            "week" -> it.date in weekStart(today)..weekStart(today).plusDays(6)
            "month" -> it.date.year == today.year && it.date.month == today.month
            "custom" -> it.date in LocalDate.parse(from)..LocalDate.parse(to)
            else -> true
        }
    }
    val planned = list.sumOf { it.duration }; val actual = list.sumOf { it.actual }
    val max = data.subjects.maxOfOrNull { s -> list.filter { it.subjectId == s.id }.let { maxOf(it.sumOf { it.duration }, it.sumOf { it.actual }) } }?.coerceAtLeast(0.01) ?: 1.0
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp)) {
        item {
            PageTitle("学習の統計")
            Box(Modifier.guideTarget("stats-filter")) { ChoiceField("対象の試験", examFilter, groupChoices(data, all = true), filter) }
            Spacer(Modifier.height(12.dp))
            ChoiceField("期間", period, listOf("all" to "すべての期間", "week" to "今週", "month" to "今月", "custom" to "期間を指定")) { period = it }
            if (period == "custom") Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DateField("開始日", from, Modifier.weight(1f)) { from = it }; DateField("終了日", to, Modifier.weight(1f)) { to = it }
            }
            if (from > to && period == "custom") Text("開始日と終了日を確認してください", color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(20.dp))
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.fillMaxWidth().padding(20.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Metric("勉強した時間", hoursText(actual), Modifier.weight(1f)); Metric("予定時間", hoursText(planned), Modifier.weight(1f))
                    }
                    Text("${list.size}件の学習記録 · ${list.count { it.status == "done" }}件 完了",
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 14.dp))
                }
            }
            Text("教科ごとの時間", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp, bottom = 4.dp))
        }
        items(data.subjects, key = { it.id }) { subject ->
            val records = list.filter { it.subjectId == subject.id }
            Card(Modifier.fillMaxWidth().padding(top = 12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
              Column(Modifier.padding(18.dp)) {
                Text(subject.name, style = MaterialTheme.typography.titleMedium)
                MeasureBar("実績", records.sumOf { it.actual }, max, MaterialTheme.colorScheme.primary)
                MeasureBar("予定", records.sumOf { it.duration }, max, MaterialTheme.colorScheme.outline)
              }
            }
        }
        if (list.isEmpty()) item { EmptyMessage("この試験・期間の学習記録はありません") }
    }
}

@Composable private fun MeasureBar(label: String, hours: Double, maximum: Double, color: Color) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, fontSize = 12.sp)
        LinearProgressIndicator(progress = { (hours / maximum).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.weight(1f).height(5.dp), color = color, trackColor = MaterialTheme.colorScheme.surfaceVariant)
        Text(hoursText(hours), fontSize = 12.sp, modifier = Modifier.widthIn(min = 80.dp))
    }
}

private fun timerText(milliseconds: Long): String {
    val seconds = (milliseconds.coerceAtLeast(0) + 999) / 1000
    return "%02d:%02d".format(seconds / 60, seconds % 60)
}
@Composable private fun TimerScreen(data: StudyData, point: ClockPoint, model: StudyViewModel, permission: () -> Unit) {
    var subject by rememberSaveable { mutableStateOf(data.subjects.firstOrNull()?.id.orEmpty()) }
    var group by rememberSaveable { mutableStateOf("") }
    var minutes by rememberSaveable { mutableStateOf("25") }
    var finishConfirm by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(data.subjects, data.examGroups) {
        if (data.subjects.none { it.id == subject }) subject = data.subjects.firstOrNull()?.id.orEmpty()
        if (group.isNotEmpty() && data.examGroups.none { it.id == group }) group = ""
    }
    val timer = data.timer
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageTitle("集中タイマー")
        if (timer == null) {
            ChoiceField("教科", subject, data.subjects.map { it.id to it.name }) { subject = it }
            ChoiceField("対象の試験", group, groupChoices(data)) { group = it }
            MinuteField("勉強時間（分）", minutes) { minutes = it }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(15, 25, 45, 60).forEach { preset -> OutlinedButton(onClick = { minutes = preset.toString() }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) { Text("${preset}分") } }
            }
            Button(onClick = { model.startTimer(subject, group, minutes.toIntOrNull() ?: 0) },
                enabled = minutes.toIntOrNull() in 1..1440 && data.subjects.any { it.id == subject }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).guideTarget("timer-start")) { Text("勉強を始める") }
            Text("画面を閉じても時間を計測します。終了時に勉強時間を保存します。", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(data.subject(timer.subjectId).name, style = MaterialTheme.typography.titleLarge)
                    if (timer.examGroupId.isNotEmpty()) Text(data.groupName(timer.examGroupId), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Box(Modifier.padding(vertical = 24.dp).size(224.dp).guideTarget("timer-start"), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(progress = { (timer.elapsed(point).toFloat() / timer.targetMs).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxSize(), strokeWidth = 7.dp, trackColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(timerText(timer.remaining(point)), fontSize = 44.sp, lineHeight = 58.sp, fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.displayMedium.copy(fontFeatureSettings = "tnum"))
                            Text(if (timer.paused) "一時停止中" else "残り時間", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Text("経過 ${hoursText(timer.elapsed(point) / 3_600_000.0)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(16.dp))
                    FilledTonalButton(onClick = model::pauseTimer, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(if (timer.paused) "再開" else "一時停止") }
                    TextButton(onClick = { finishConfirm = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("終了して記録") }
                }
            }
            OutlinedButton(onClick = { context.startActivity(Intent(context, FocusActivity::class.java)) }, modifier = Modifier.fillMaxWidth()) { Text("画面を固定して集中") }
        }
        if (!AlarmScheduler.notificationsAllowed(context)) {
            Text("終了通知は許可されていません。時間の記録は続きます。", fontSize = 13.sp)
            OutlinedButton(onClick = permission) { Text("通知を許可") }
        }
        if (!AlarmScheduler.exactAllowed(context)) {
            Text("正確な時刻のアラームが未許可です。画面を閉じている間の終了通知が遅れる場合があります。", fontSize = 13.sp)
            OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))) }) { Text("正確なアラームを許可") }
        }
    }
    if (finishConfirm) AlertDialog(onDismissRequest = { finishConfirm = false }, title = { Text("勉強を終了しますか？") },
        text = { Text("ここまでの勉強時間を記録します。") }, confirmButton = { TextButton(onClick = { model.stopTimer(); finishConfirm = false }) { Text("終了して記録") } },
        dismissButton = { TextButton(onClick = { finishConfirm = false }) { Text("続ける") } })
}

@Composable private fun MoreScreen(navigate: (String) -> Unit) {
    val icons = mapOf("timer" to Icons.Default.PlayArrow, "wake" to Icons.Default.DateRange, "subjects" to Icons.AutoMirrored.Filled.List, "settings" to Icons.Default.Settings,
        "backup" to Icons.Default.Share, "help" to Icons.Default.Info)
    LazyColumn(contentPadding = PaddingValues(20.dp)) {
        item { PageTitle("その他") }
        items(listOf(Triple("timer", "集中タイマー", "予定を作らず、そのまま勉強を始める"), Triple("wake", "目覚まし", "計算問題とNFCタグで、2段階解除"), Triple("subjects", "教科", "教科の追加・編集・並び替え"),
            Triple("settings", "通知設定", "タイマー終了・学習予定・試験の通知"), Triple("backup", "バックアップ", "別の端末へ引き継ぐ・ファイルに保存"), Triple("help", "使い方", "初めての方へ・操作ガイド"))) { (key, title, description) ->
            Card(onClick = { navigate(key) }, modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                ListItem(headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium) }, supportingContent = { Text(description, style = MaterialTheme.typography.bodySmall) },
                    leadingContent = { Icon(icons.getValue(key), null, tint = MaterialTheme.colorScheme.primary) },
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) })
            }
        }
    }
}
@Composable private fun SubjectsScreen(data: StudyData, model: StudyViewModel, edit: (String) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(16.dp)) {
        item { PageTitle("教科"); OutlinedButton(onClick = { edit("") }) { Text("教科を追加") } }
        itemsIndexed(data.subjects, key = { _, s -> s.id }) { index, subject ->
            ListItem(headlineContent = { Text(subject.name) }, leadingContent = {
                Box(Modifier.size(12.dp).background(Color(android.graphics.Color.parseColor(subject.color)), RoundedCornerShape(6.dp))) },
                trailingContent = { Row {
                    TextButton(onClick = { model.moveSubject(subject.id, -1) }, enabled = index > 0, contentPadding = PaddingValues(4.dp)) { Text("上") }
                    TextButton(onClick = { model.moveSubject(subject.id, 1) }, enabled = index < data.subjects.lastIndex, contentPadding = PaddingValues(4.dp)) { Text("下") }
                    IconButton(onClick = { edit(subject.id) }) { Icon(Icons.Default.Edit, "${subject.name}を編集") }
                } }); HorizontalDivider()
        }
    }
}
@Composable private fun SettingsScreen(data: StudyData, model: StudyViewModel, request: () -> Unit) {
    val p = data.preferences
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PageTitle("通知設定")
        SettingSwitch("タイマー終了", "終了通知と、通知欄のタイマー操作", p.timerNotification) { value -> if (value) request(); model.preferences { it.copy(timerNotification = value) } }
        SettingSwitch("学習予定", "予定の開始前にお知らせ", p.reminders) { value -> if (value) request(); model.preferences { it.copy(reminders = value) } }
        ChoiceField("何分前に通知する？", p.reminderMinutes.toString(), listOf(0, 5, 10, 15, 30, 60).map { it.toString() to if (it == 0) "開始時刻" else "${it}分前" }) { value -> model.preferences { it.copy(reminderMinutes = value.toInt()) } }
        SettingSwitch("試験の前日", "試験開始日の前日18時にお知らせ", p.examReminder) { value -> if (value) request(); model.preferences { it.copy(examReminder = value) } }
        HorizontalDivider()
        OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }) { Text("Androidの通知設定") }
        if (Build.VERSION.SDK_INT >= 31) OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))) }) { Text("正確なアラームの設定") }
    }
}
@Composable private fun SettingSwitch(title: String, description: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold); Text(description, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Switch(checked, change)
    }
}
@Composable private fun BackupScreen(data: StudyData, export: () -> Unit, import: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageTitle("バックアップ")
        Text("教科 ${data.subjects.size}件・試験 ${data.examGroups.size}件\n学習記録 ${data.schedules.size}件・教科別日程 ${data.exams.size}件")
        Button(onClick = export, enabled = data.timer == null, modifier = Modifier.fillMaxWidth()) { Text("ファイルに保存") }
        OutlinedButton(onClick = import, enabled = data.timer == null, modifier = Modifier.fillMaxWidth()) { Text("ファイルから復元") }
        Text("Web版の「バックアップ」で出力したJSONファイルを読み込めます。Android版のバックアップもWeb版で復元できます。", fontSize = 14.sp)
        Text("ブラウザ版とアプリ版のデータは、自動で同期されません。", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (data.timer != null) Text("タイマーを終了してからバックアップしてください。", fontSize = 13.sp)
    }
}

package jp.studycopi

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDateTime

data class Notice(val text: String, val undo: (suspend () -> Unit)? = null)
class StudyViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application as StudyApplication
    private val repository = context.repository
    val data = repository.data
    val ready = MutableStateFlow(false)
    val loadError = MutableStateFlow<String?>(null)
    val now = MutableStateFlow(clockPoint(context))
    val requestedScreen = MutableStateFlow<String?>(null)
    private val eventChannel = Channel<Notice>(Channel.BUFFERED)
    val notices = eventChannel.receiveAsFlow()
    private val operations = Mutex()
    private var ticker: Job? = null
    init { viewModelScope.launch {
        try { repository.load(); repository.update { it.normalizeWakeAlarms(System.currentTimeMillis()) }; ready.value = true; settleTimer(); reconcile() }
        catch (e: Exception) { loadError.value = "保存データを読み込めません。ファイルを保持しています。バックアップから復元してください。" }
    } }
    fun resume() {
        ticker?.cancel()
        ticker = viewModelScope.launch {
            // The boot identifier is constant during this process; avoid a settings IPC on every frame sample.
            val boot = clockPoint(context).boot
            while (isActive) {
                now.value = ClockPoint(System.currentTimeMillis(), android.os.SystemClock.elapsedRealtime(), boot)
                if (ready.value) try { settleTimer() } catch (e: Exception) {
                    eventChannel.send(Notice("勉強時間を保存できませんでした。タイマー画面から再度終了してください")); return@launch
                }
                // Sample from the clock, never add the polling interval to elapsed time.
                // 750ms polling made every third second linger before the next change.
                val interval = if (data.value.timer?.paused == false) 100L else 1000L
                delay(interval - android.os.SystemClock.elapsedRealtime() % interval)
            }
        }
        if (ready.value) run { repository.update { it.normalizeWakeAlarms(System.currentTimeMillis()) }; reconcile() }
    }
    fun suspendTicks() { ticker?.cancel(); ticker = null }
    private suspend fun settleTimer() {
        val timer = data.value.timer ?: return
        if (!timer.paused && timer.remaining(now.value) == 0L) operations.withLock { finish(timer.id, false) }
    }
    private fun run(action: suspend () -> Unit) { viewModelScope.launch {
        operations.withLock { try { action() } catch (e: Exception) { eventChannel.send(Notice(e.message ?: "操作に失敗しました")) } }
    } }
    private suspend fun reconcile() = withContext(Dispatchers.IO) { AlarmScheduler.reconcile(context); WakeScheduler.reconcile(context)
        runCatching { StudyWidget.updateAll(context, data.value) }
    }
    private suspend fun change(transform: (StudyData) -> StudyData) { repository.update(transform); reconcile() }
    private fun protectSchedule(id: String) { check(data.value.timer?.scheduleId != id) { "タイマーを終了してから変更してください" } }
    private suspend fun undoSchedule(previous: Schedule, expected: Schedule?) {
        protectSchedule(previous.id)
        change {
            check(it.schedules.find { s -> s.id == previous.id } == expected) { "その後に記録が変更されたため、取り消せません" }
            it.copy(schedules = it.schedules.filterNot { s -> s.id == previous.id } + previous)
        }
    }
    fun saveSchedule(item: Schedule) = run {
        protectSchedule(item.id)
        require(item.duration > 0) { "予定時間は1分以上にしてください" }
        change { it.copy(schedules = it.schedules.filterNot { s -> s.id == item.id } + item) }
        eventChannel.send(Notice("予定を保存しました"))
    }
    fun complete(id: String) = run {
        protectSchedule(id)
        val previous = data.value.schedules.find { it.id == id } ?: return@run
        val updated = previous.complete()
        change { it.copy(schedules = it.schedules.map { s -> if (s.id == id) updated else s }) }
        eventChannel.send(Notice("記録を更新しました") { undoSchedule(previous, updated) })
    }
    fun record(id: String, status: String, minutes: Double, note: String) = run {
        protectSchedule(id)
        require(minutes.isFinite() && minutes >= 0 && status in statuses) { "実績時間を確認してください" }
        val previous = data.value.schedules.find { it.id == id } ?: return@run
        val updated = previous.copy(status = status, actualDuration = minutes / 60, note = note)
        change { it.copy(schedules = it.schedules.map { s -> if (s.id == id) updated else s }) }
        eventChannel.send(Notice("実績を保存しました") { undoSchedule(previous, updated) })
    }
    fun deleteSchedule(id: String) = run {
        protectSchedule(id)
        val previous = data.value.schedules.find { it.id == id } ?: return@run
        change { it.copy(schedules = it.schedules.filterNot { s -> s.id == id }) }
        eventChannel.send(Notice("予定を削除しました") { undoSchedule(previous, null) })
    }
    fun saveGroup(group: ExamGroup) = run {
        require(group.name.isNotBlank()) { "試験名を入力してください" }
        check(data.value.exams.none { it.examGroupId == group.id && (it.date < group.startDate || it.date > group.endDate) }) { "教科別日程が試験期間から外れています" }
        change { it.copy(examGroups = it.examGroups.filterNot { g -> g.id == group.id } + group) }
        eventChannel.send(Notice("試験を保存しました"))
    }
    fun deleteGroup(id: String) = run {
        check(data.value.timer?.examGroupId != id) { "この試験のタイマーを終了してください" }
        change { it.withoutGroup(id) }; eventChannel.send(Notice("試験を削除しました。学習記録は試験未指定として残しました"))
    }
    fun saveExam(exam: ExamSession) = run {
        require(exam.subject.isNotBlank()) { "教科を入力してください" }
        val group = data.value.examGroups.find { it.id == exam.examGroupId }
        require(group == null || exam.date in group.startDate..group.endDate) { "試験期間内の日付を選んでください" }
        require(exam.startTime.isEmpty() || exam.endTime.isEmpty() || exam.endTime > exam.startTime) { "終了時刻は開始時刻より後にしてください" }
        change { it.copy(exams = it.exams.filterNot { e -> e.id == exam.id } + exam) }
    }
    fun deleteExam(id: String) = run { change { it.copy(exams = it.exams.filterNot { e -> e.id == id }) } }
    fun saveSubject(subject: Subject) = run {
        require(subject.name.isNotBlank()) { "教科名を入力してください" }
        change { data ->
            val previous = data.subjects.find { it.id == subject.id }
            val subjects = if (previous != null) data.subjects.map { if (it.id == subject.id) subject else it } else data.subjects + subject
            data.copy(subjects = subjects, exams = data.exams.map { if (previous != null && it.subject == previous.name) it.copy(subject = subject.name) else it })
        }
    }
    fun moveSubject(id: String, offset: Int) = run { change {
        val list = it.subjects.toMutableList(); val from = list.indexOfFirst { s -> s.id == id }; val to = from + offset
        if (from >= 0 && to in list.indices) java.util.Collections.swap(list, from, to)
        it.copy(subjects = list)
    } }
    fun deleteSubject(id: String) = run {
        check(data.value.timer?.subjectId != id && data.value.schedules.none { it.subjectId == id }) { "学習記録に使われている教科は削除できません" }
        check(data.value.subjects.size > 1) { "教科を1つ以上残してください" }
        change { it.copy(subjects = it.subjects.filterNot { s -> s.id == id }) }
    }
    fun preferences(transform: (Preferences) -> Preferences) = run { change { it.copy(preferences = transform(it.preferences)) } }
    fun saveWakeAlarm(item: WakeAlarm) = run {
        if (item.enabled) check(AlarmScheduler.exactAllowed(context) && WakeScheduler.notificationsAllowed(context)) { "通知と正確なアラームを許可してください" }
        val saved = if (item.enabled) item.arm(System.currentTimeMillis()) else item.copy(nextAt = 0)
        change { it.copy(wakeAlarms = it.wakeAlarms.filterNot { a -> a.id == saved.id } + saved) }
        eventChannel.send(Notice("目覚ましを保存しました"))
    }
    fun toggleWakeAlarm(id: String, enabled: Boolean) = run {
        if (enabled) check(AlarmScheduler.exactAllowed(context) && WakeScheduler.notificationsAllowed(context)) { "通知と正確なアラームを許可してください" }
        change { data -> data.copy(wakeAlarms = data.wakeAlarms.map {
            if (it.id != id) it else if (enabled) it.arm(System.currentTimeMillis()) else it.copy(enabled = false, nextAt = 0)
        }) }
    }
    fun deleteWakeAlarm(id: String) = run {
        check(data.value.wakeRuns.none { it.alarmId == id }) { "先に鳴っている目覚ましを解除してください" }
        change { it.copy(wakeAlarms = it.wakeAlarms.filterNot { a -> a.id == id }) }
    }
    fun testWakeAlarm(id: String) = run {
        val alarm = data.value.wakeAlarms.find { it.id == id } ?: return@run
        check(data.value.wakeRuns.isEmpty()) { "先に鳴っている目覚ましを解除してください" }
        check(WakeScheduler.notificationsAllowed(context)) { "目覚ましの通知を許可してください" }
        val wall = System.currentTimeMillis()
        val trial = WakeRun("test_${newId()}", id, "テスト・${alarm.label}".take(100), alarm.secondStep, alarm.questions, alarm.tagId, alarm.tagName,
            kotlin.random.Random.nextInt(), wall, sound = alarm.sound, questionSubject = alarm.questionSubject)
        change { it.copy(wakeRuns = it.wakeRuns + trial) }
        WakeScheduler.startRinging(context)
        context.startActivity(android.content.Intent(context, WakeActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    fun startTimer(subjectId: String, groupId: String, minutes: Int, scheduleId: String = "") = run {
        check(data.value.timer == null) { "実行中のタイマーがあります" }
        require(minutes in 1..1440 && data.value.subjects.any { it.id == subjectId }) { "教科と時間を確認してください" }
        require(groupId.isEmpty() || data.value.examGroups.any { it.id == groupId }) { "対象の試験を選び直してください" }
        require(scheduleId.isEmpty() || data.value.schedules.any { it.id == scheduleId && !it.finished }) { "予定が変更されています" }
        val point = clockPoint(context)
        change { it.copy(timer = TimerSession(subjectId = subjectId, examGroupId = groupId, scheduleId = scheduleId,
            targetMs = minutes * 60_000L, originalStart = LocalDateTime.now().toString(),
            runWall = point.wall, runMonotonic = point.monotonic, boot = point.boot)) }
    }
    fun pauseTimer() = run {
        val point = clockPoint(context); val timer = data.value.timer ?: return@run
        if (!timer.paused && timer.remaining(point) == 0L) finish(timer.id, false)
        else change { it.copy(timer = it.timer?.togglePause(point)) }
    }
    fun stopTimer() = run { data.value.timer?.let { finish(it.id, true) } }
    private suspend fun finish(id: String, manual: Boolean) {
        if (data.value.timer?.id != id) return
        val point = clockPoint(context)
        val finished = data.value.timer!!
        change { if (it.timer?.id == id) it.finishTimer(point) else it }
        if (!manual && data.value.preferences.timerNotification) AlarmScheduler.notifyFinished(context, finished.subjectId)
        eventChannel.send(Notice("勉強時間を記録しました"))
    }
    fun undo(notice: Notice) = run { notice.undo?.invoke() }
    fun export(uri: Uri) = run {
        withContext(Dispatchers.IO) {
            check(ready.value)
            context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(BackupCodec.encode(data.value)) }
                ?: error("保存先を開けません")
        }
        eventChannel.send(Notice("バックアップを保存しました"))
    }
    fun import(uri: Uri) = run {
        check(data.value.timer == null) { "タイマーを終了してから復元してください" }
        check(data.value.wakeRuns.isEmpty()) { "目覚ましを解除してから復元してください" }
        val restored = withContext(Dispatchers.IO) {
            val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer); if (count < 0) break
                    require(output.size() + count <= 10 * 1024 * 1024) { "バックアップは10MB以内にしてください" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } ?: error("ファイルを開けません")
            val text = bytes.toString(Charsets.UTF_8)
            BackupCodec.decode(text).let { if (org.json.JSONObject(text).has("android")) it else it.copy(wakeAlarms = data.value.wakeAlarms) }
        }
        repository.restore(restored); ready.value = true; loadError.value = null; reconcile()
        eventChannel.send(Notice("データを復元しました"))
    }
}

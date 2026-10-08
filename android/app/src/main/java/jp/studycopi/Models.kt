package jp.studycopi

import java.time.*
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.math.roundToLong

fun newId(): String = UUID.randomUUID().toString()
val examTypes = listOf("定期考査", "模試", "その他")
val statuses = listOf("pending", "partial", "done", "miss")
val subjectColors = listOf("#185fa5", "#3b6d11", "#a32d2d", "#ba7517", "#534ab7", "#5f5e5a")
fun dateTime(value: String): LocalDateTime = runCatching { LocalDateTime.parse(value) }
    .getOrElse { OffsetDateTime.parse(value).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime() }
fun hoursText(hours: Double): String {
    val minutes = (hours.coerceAtLeast(0.0) * 60).roundToLong()
    return if (minutes < 60) "${minutes}分" else "${minutes / 60}時間" + if (minutes % 60 > 0) "${minutes % 60}分" else ""
}
fun shortDate(value: String): String = LocalDate.parse(value).format(DateTimeFormatter.ofPattern("yyyy/M/d"))
fun weekStart(date: LocalDate): LocalDate = date.minusDays((date.dayOfWeek.value - 1).toLong())

data class Subject(val id: String = newId(), val name: String, val color: String = "#185fa5")
data class ExamGroup(val id: String = newId(), val name: String, val type: String = "定期考査",
    val startDate: String, val endDate: String)
data class Schedule(val id: String = newId(), val subjectId: String, val datetime: String,
    val duration: Double, val content: String = "", val note: String = "",
    val status: String = "pending", val actualDuration: Double? = null, val examGroupId: String = "") {
    val actual: Double get() = actualDuration ?: if (status == "done") duration else 0.0
    val finished: Boolean get() = status == "done" || status == "miss"
    val date: LocalDate get() = dateTime(datetime).toLocalDate()
    fun complete(): Schedule = if (status == "done") copy(status = "pending")
        else copy(status = "done", actualDuration = actualDuration ?: duration)
}
val checklistStatuses = listOf("pending" to "未着手", "progress" to "途中", "review" to "要復習", "done" to "完了")
data class ChecklistItem(val id: String = newId(), val title: String, val status: String = "pending")
data class ExamSession(val id: String = newId(), val subject: String, val date: String,
    val startTime: String = "", val endTime: String = "", val range: String = "", val examGroupId: String = "",
    val checklist: List<ChecklistItem> = emptyList())
data class Preferences(val timerNotification: Boolean = true, val reminders: Boolean = false,
    val reminderMinutes: Int = 15, val examReminder: Boolean = false, val scheduledFocus: Boolean = false)
data class ClockPoint(val wall: Long, val monotonic: Long, val boot: Int)
data class TimerSession(val id: String = newId(), val subjectId: String, val examGroupId: String = "",
    val scheduleId: String = "", val targetMs: Long, val originalStart: String,
    val runWall: Long, val runMonotonic: Long, val boot: Int, val accruedMs: Long = 0, val paused: Boolean = false) {
    fun elapsed(now: ClockPoint): Long {
        val current = if (paused) 0 else if (now.boot == boot && now.monotonic >= runMonotonic)
            now.monotonic - runMonotonic else (now.wall - runWall).coerceAtLeast(0)
        return (accruedMs + current).coerceIn(0, targetMs)
    }
    fun remaining(now: ClockPoint): Long = targetMs - elapsed(now)
    fun togglePause(now: ClockPoint): TimerSession = copy(accruedMs = elapsed(now),
        runWall = now.wall, runMonotonic = now.monotonic, boot = now.boot, paused = !paused)
}
data class StudyData(val subjects: List<Subject> = listOf(
    Subject("s1", "数学", "#185fa5"), Subject("s2", "英語", "#3b6d11"), Subject("s3", "国語", "#a32d2d")),
    val schedules: List<Schedule> = emptyList(), val examGroups: List<ExamGroup> = emptyList(),
    val exams: List<ExamSession> = emptyList(), val timer: TimerSession? = null,
    val preferences: Preferences = Preferences(), val wakeAlarms: List<WakeAlarm> = emptyList(),
    val wakeRuns: List<WakeRun> = emptyList()) {
    fun subject(id: String): Subject = subjects.find { it.id == id } ?: Subject(id, "不明", "#71717a")
    fun groupName(id: String): String = examGroups.find { it.id == id }?.name ?: "試験未指定"
    fun forExam(id: String): List<Schedule> = schedules.filter { id.isEmpty() ||
        if (id == "unassigned") it.examGroupId.isEmpty() else it.examGroupId == id }
    fun finishTimer(now: ClockPoint): StudyData {
        val current = timer ?: return this
        val hours = current.elapsed(now) / 3_600_000.0
        val linked = schedules.find { it.id == current.scheduleId }
        val updated = if (linked != null) schedules.map {
            if (it.id != linked.id) it else {
                val actual = it.actual + hours
                it.copy(actualDuration = actual, status = if (actual + 0.00001 >= it.duration) "done" else "partial")
            }
        } else schedules + Schedule(subjectId = current.subjectId,
            examGroupId = current.examGroupId, datetime = current.originalStart,
            duration = current.targetMs / 3_600_000.0, actualDuration = hours,
            content = "集中タイマー", status = "done")
        return copy(schedules = updated, timer = null)
    }
    fun withoutGroup(id: String): StudyData = copy(examGroups = examGroups.filterNot { it.id == id },
        schedules = schedules.map { if (it.examGroupId == id) it.copy(examGroupId = "") else it },
        exams = exams.map { if (it.examGroupId == id) it.copy(examGroupId = "") else it })
}

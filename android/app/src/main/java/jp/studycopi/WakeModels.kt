package jp.studycopi

import java.time.*
import kotlin.random.Random

data class WakeAlarm(val id: String = newId(), val label: String = "目覚まし", val time: String = "07:00",
    val days: Set<Int> = emptySet(), val secondStep: String = "math", val questions: Int = 3,
    val tagId: String = "", val tagName: String = "1階のタグ", val enabled: Boolean = false, val nextAt: Long = 0) {
    fun nextAfter(wall: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val now = Instant.ofEpochMilli(wall).atZone(zone)
        for (offset in 0..7) {
            val date = now.toLocalDate().plusDays(offset.toLong())
            if (days.isNotEmpty() && date.dayOfWeek.value !in days) continue
            val candidate = date.atTime(LocalTime.parse(time)).atZone(zone).toInstant().toEpochMilli()
            if (candidate > wall) return candidate
        }
        error("次の目覚まし時刻を計算できません")
    }
    fun arm(wall: Long, zone: ZoneId = ZoneId.systemDefault()) = copy(enabled = true, nextAt = nextAfter(wall, zone))
}

data class WakeQuestion(val left: Int, val right: Int, val multiply: Boolean) {
    val answer: Int get() = if (multiply) left * right else left + right
    val text: String get() = "$left ${if (multiply) "×" else "+"} $right = ?"
}

/** Snapshot the task and NFC target at fire time; progress survives rotation/process recreation. */
data class WakeRun(val id: String, val alarmId: String, val label: String, val secondStep: String,
    val questions: Int, val tagId: String, val tagName: String, val seed: Int,
    val firedAt: Long, val stage: Int = 1, val solved: Int = 0) {
    val key: String get() = "$id:$stage:$solved"
    val needsTag: Boolean get() = stage == 2 && secondStep == "nfc"
    val question: WakeQuestion get() {
        val random = Random(seed xor (stage shl 24) xor (solved shl 12))
        return if (stage == 1) WakeQuestion(random.nextInt(12, 70), random.nextInt(12, 70), false)
        else WakeQuestion(random.nextInt(3, 10), random.nextInt(3, 10), true)
    }
    fun advance(): WakeRun? = if (solved + 1 < questions) copy(solved = solved + 1)
        else if (stage == 1) copy(stage = 2, solved = 0) else null
}

fun nfcId(bytes: ByteArray): String = bytes.joinToString("") { "%02X".format(it.toInt() and 255) }
fun validNfcId(value: String) = value.matches(Regex("(?:[0-9A-F]{2}){1,32}"))

fun StudyData.normalizeWakeAlarms(wall: Long, timeZoneChanged: Boolean = false): StudyData = copy(wakeAlarms = wakeAlarms.map {
    when {
        !it.enabled -> it
        timeZoneChanged -> it.arm(wall)
        it.nextAt < wall - 15 * 60_000 -> if (it.days.isEmpty()) it.copy(enabled = false, nextAt = 0) else it.arm(wall)
        else -> it
    }
})

fun StudyData.claimWake(id: String, expected: Long, wall: Long, seed: Int = Random.nextInt()): StudyData {
    val alarm = wakeAlarms.find { it.id == id } ?: return this
    if (!alarm.enabled || alarm.nextAt != expected || wall < expected || wall - expected > 15 * 60_000) return this
    val runId = "${alarm.id}_$expected"
    if (wakeRuns.any { it.id == runId }) return this
    val run = WakeRun(runId, id, alarm.label, alarm.secondStep, alarm.questions, alarm.tagId, alarm.tagName, seed, wall)
    return copy(wakeAlarms = wakeAlarms.map { if (it.id != id) it else if (it.days.isEmpty()) it.copy(enabled = false, nextAt = 0) else it.arm(wall) },
        wakeRuns = wakeRuns + run)
}

fun StudyData.answerWake(runId: String, key: String, answer: String): StudyData {
    val run = wakeRuns.find { it.id == runId } ?: return this
    require(run.key == key) { "問題が変わりました。現在の問題に答えてください" }
    require(!run.needsTag && answer.toIntOrNull() == run.question.answer) { "答えが違います" }
    val next = run.advance()
    return copy(wakeRuns = wakeRuns.mapNotNull { if (it.id == runId) next else it })
}
fun StudyData.scanWake(runId: String, scanned: String): StudyData {
    val run = wakeRuns.find { it.id == runId } ?: return this
    require(run.needsTag) { "先に計算問題を解いてください" }
    require(validNfcId(scanned) && scanned == run.tagId) { "登録したタグとは違います" }
    return copy(wakeRuns = wakeRuns.filterNot { it.id == runId })
}

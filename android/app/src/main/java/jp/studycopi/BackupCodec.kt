package jp.studycopi

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime

/** Uses the Web version's v3 schema (durations are hours); older unassigned records stay unassigned. */
object BackupCodec {
    private fun <T> JSONArray.mapObjects(block: (JSONObject) -> T): List<T> = (0 until length()).map { block(getJSONObject(it)) }
    private fun array(items: List<JSONObject>) = JSONArray(items)
    private fun obj(vararg pairs: Pair<String, Any?>): JSONObject = JSONObject().apply {
        pairs.forEach { (key, value) -> if (value != null) put(key, value) }
    }
    fun encode(data: StudyData, internal: Boolean = false): String {
        val root = obj("version" to 3,
            "subjects" to array(data.subjects.map { obj("id" to it.id, "name" to it.name, "color" to it.color) }),
            "examGroups" to array(data.examGroups.map { obj("id" to it.id, "name" to it.name, "type" to it.type, "startDate" to it.startDate, "endDate" to it.endDate) }),
            "schedules" to array(data.schedules.map { obj("id" to it.id, "subjectId" to it.subjectId,
                "datetime" to it.datetime, "duration" to it.duration, "content" to it.content,
                "note" to it.note, "status" to it.status, "actualDuration" to it.actualDuration, "examGroupId" to it.examGroupId) }),
            "exams" to array(data.exams.map { obj("id" to it.id, "subject" to it.subject, "date" to it.date,
                "startTime" to it.startTime, "endTime" to it.endTime, "note" to it.range, "examGroupId" to it.examGroupId) }))
        if (internal) {
            root.put("preferences", obj("timerNotification" to data.preferences.timerNotification,
                "reminders" to data.preferences.reminders, "reminderMinutes" to data.preferences.reminderMinutes,
                "examReminder" to data.preferences.examReminder, "scheduledFocus" to data.preferences.scheduledFocus))
            data.timer?.let { root.put("timer", obj("id" to it.id, "subjectId" to it.subjectId,
                "examGroupId" to it.examGroupId, "scheduleId" to it.scheduleId, "targetMs" to it.targetMs,
                "originalStart" to it.originalStart, "runWall" to it.runWall, "runMonotonic" to it.runMonotonic,
                "boot" to it.boot, "accruedMs" to it.accruedMs, "paused" to it.paused)) }
        }
        WakeBackup.write(root, data, internal)
        return root.toString(2)
    }
    fun decode(text: String, internal: Boolean = false): StudyData {
        require(text.toByteArray().size <= 10 * 1024 * 1024) { "バックアップは10MB以内にしてください" }
        val root = JSONObject(text)
        require(!root.has("version") || root.getInt("version") in 1..3) { "未対応のバックアップ形式です" }
        val subjects = root.getJSONArray("subjects").mapObjects { Subject(it.getString("id"), it.getString("name"), it.getString("color")) }
        val groups = (root.optJSONArray("examGroups") ?: JSONArray()).mapObjects {
            ExamGroup(it.getString("id"), it.getString("name"), it.getString("type"), it.getString("startDate"), it.getString("endDate")) }
        val schedules = root.getJSONArray("schedules").mapObjects {
            Schedule(it.getString("id"), it.optString("subjectId"), it.getString("datetime"), it.getDouble("duration"),
                it.optString("content"), it.optString("note"), it.optString("status", "pending").ifEmpty { "pending" },
                if (!it.has("actualDuration") || it.opt("actualDuration") == "") null else it.getDouble("actualDuration"), it.optString("examGroupId")) }
        val exams = root.getJSONArray("exams").mapObjects {
            ExamSession(it.getString("id"), it.getString("subject"), it.getString("date"), it.optString("startTime"),
                it.optString("endTime"), it.optString("note", it.optString("range")), it.optString("examGroupId")) }
        val (wakeAlarms, wakeRuns) = WakeBackup.read(root, internal)
        val data = StudyData(subjects, schedules, groups, exams, wakeAlarms = wakeAlarms, wakeRuns = wakeRuns)
        validate(data)
        if (!internal) return data
        val p = root.optJSONObject("preferences") ?: JSONObject()
        val t = root.optJSONObject("timer")
        val timer = t?.let { TimerSession(it.getString("id"), it.getString("subjectId"), it.optString("examGroupId"),
            it.optString("scheduleId"), it.getLong("targetMs"), it.getString("originalStart"), it.getLong("runWall"),
            it.getLong("runMonotonic"), it.getInt("boot"), it.getLong("accruedMs"), it.getBoolean("paused")) }
        timer?.let { require(it.targetMs in 60_000..86_400_000 && it.accruedMs in 0..it.targetMs); dateTime(it.originalStart) }
        return data.copy(timer = timer, preferences = Preferences(p.optBoolean("timerNotification", true),
            p.optBoolean("reminders", false), p.optInt("reminderMinutes", 15).coerceIn(0, 1440), p.optBoolean("examReminder", false), p.optBoolean("scheduledFocus", false)))
    }
    fun validate(data: StudyData) {
        WakeBackup.validate(data)
        val ids = listOf(data.subjects.map { it.id }, data.schedules.map { it.id }, data.examGroups.map { it.id }, data.exams.map { it.id })
        require(ids.all { list -> list.size == list.toSet().size && list.all { it.matches(Regex("[a-zA-Z0-9_-]+")) } }) { "IDが不正または重複しています" }
        require(data.subjects.all { it.name.isNotBlank() && it.color.matches(Regex("#[0-9a-fA-F]{6}")) }) { "教科が不正です" }
        require(data.examGroups.all { it.name.isNotBlank() && it.type in examTypes &&
            LocalDate.parse(it.startDate) <= LocalDate.parse(it.endDate) }) { "試験の期間が不正です" }
        val groupIds = data.examGroups.map { it.id }.toSet()
        fun linked(id: String) = id.isEmpty() || id in groupIds
        require(data.schedules.all { s -> dateTime(s.datetime); s.duration.isFinite() && s.duration in 0.0..24.0 &&
            (s.actualDuration == null || s.actualDuration.isFinite() && s.actualDuration >= 0) &&
            s.status in statuses && linked(s.examGroupId) }) { "学習記録が不正です" }
        require(data.exams.all { e -> LocalDate.parse(e.date); listOf(e.startTime, e.endTime).forEach { if (it.isNotEmpty()) LocalTime.parse(it) }; linked(e.examGroupId) }) { "教科別日程が不正です" }
    }
}

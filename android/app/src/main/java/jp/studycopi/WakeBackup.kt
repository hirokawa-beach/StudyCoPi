package jp.studycopi

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalTime

object WakeBackup {
    private fun obj(vararg pairs: Pair<String, Any>) = JSONObject().apply { pairs.forEach { put(it.first, it.second) } }
    fun write(root: JSONObject, data: StudyData, internal: Boolean) {
        val section = obj("wakeAlarms" to JSONArray(data.wakeAlarms.map { alarm -> obj(
            "id" to alarm.id, "label" to alarm.label, "time" to alarm.time, "days" to JSONArray(alarm.days.sorted()),
            "secondStep" to alarm.secondStep, "questions" to alarm.questions, "tagId" to alarm.tagId,
            "tagName" to alarm.tagName, "enabled" to alarm.enabled, "nextAt" to alarm.nextAt) }))
        if (internal) section.put("wakeRuns", JSONArray(data.wakeRuns.map { run -> obj(
            "id" to run.id, "alarmId" to run.alarmId, "label" to run.label, "secondStep" to run.secondStep,
            "questions" to run.questions, "tagId" to run.tagId, "tagName" to run.tagName, "seed" to run.seed,
            "firedAt" to run.firedAt, "stage" to run.stage, "solved" to run.solved) }))
        root.put("android", section)
    }
    fun read(root: JSONObject, internal: Boolean): Pair<List<WakeAlarm>, List<WakeRun>> {
        val section = root.optJSONObject("android") ?: return emptyList<WakeAlarm>() to emptyList()
        val alarms = section.optJSONArray("wakeAlarms") ?: JSONArray()
        val runs = if (internal) section.optJSONArray("wakeRuns") ?: JSONArray() else JSONArray()
        return (0 until alarms.length()).map { index ->
            val a = alarms.getJSONObject(index); val days = a.getJSONArray("days")
            WakeAlarm(a.getString("id"), a.getString("label"), a.getString("time"),
                (0 until days.length()).map { days.getInt(it) }.toSet(), a.getString("secondStep"), a.getInt("questions"),
                a.getString("tagId"), a.getString("tagName"), internal && a.getBoolean("enabled"), if (internal) a.getLong("nextAt") else 0)
        } to (0 until runs.length()).map { index ->
            val r = runs.getJSONObject(index)
            WakeRun(r.getString("id"), r.getString("alarmId"), r.getString("label"), r.getString("secondStep"),
                r.getInt("questions"), r.getString("tagId"), r.getString("tagName"), r.getInt("seed"),
                r.getLong("firedAt"), r.getInt("stage"), r.getInt("solved"))
        }
    }
    fun validate(data: StudyData) {
        require(data.wakeAlarms.size <= 30 && data.wakeRuns.size <= 30) { "目覚ましは30件以内にしてください" }
        val id = Regex("[a-zA-Z0-9_-]{1,160}")
        require(data.wakeAlarms.map { it.id }.distinct().size == data.wakeAlarms.size && data.wakeAlarms.all { it.id.matches(id) }) { "目覚ましのIDが不正です" }
        require(data.wakeRuns.map { it.id }.distinct().size == data.wakeRuns.size && data.wakeRuns.all { it.id.matches(Regex("[a-zA-Z0-9_-]{1,192}")) }) { "解除状態のIDが不正です" }
        data.wakeAlarms.forEach {
            require(it.label.isNotBlank() && it.label.length <= 100) { "目覚ましの名前を入力してください（100文字以内）" }
            require(runCatching { LocalTime.parse(it.time) }.isSuccess) { "時刻を確認してください" }
            require(it.time.matches(Regex("[0-2][0-9]:[0-5][0-9]")) && it.days.all { day -> day in 1..7 }) { "時刻と曜日を確認してください" }
            require(it.questions in 1..5 && it.secondStep in setOf("math", "nfc")) { "解除方法を確認してください" }
            require(it.tagName.length <= 100 && (it.secondStep != "nfc" || (validNfcId(it.tagId) && it.tagName.isNotBlank()))) { "NFCタグと置く場所を登録してください" }
            require(it.nextAt >= 0 && (!it.enabled || it.nextAt > 0)) { "次の目覚まし時刻が不正です" }
        }
        data.wakeRuns.forEach {
            require(it.alarmId.matches(id) && it.label.isNotBlank() && it.label.length <= 100 && it.tagName.length <= 100) { "解除状態が不正です" }
            require(it.stage in 1..2 && it.questions in 1..5 && it.solved in 0 until it.questions && it.firedAt > 0)
            require(it.secondStep in setOf("math", "nfc") && (it.secondStep != "nfc" || validNfcId(it.tagId)))
            require(!it.needsTag || it.solved == 0 && it.tagName.isNotBlank())
        }
    }
}

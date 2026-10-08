package jp.studycopi

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class ChecklistModelTest {
    private val exam = ExamSession("e1", "数学", "2026-10-20", range = "問題集 p.20〜40", examGroupId = "g1",
        checklist = listOf(ChecklistItem("c1", "p.20〜25", "done"), ChecklistItem("c2", "p.26〜30", "review")))
    private fun data() = StudyData(examGroups = listOf(ExamGroup("g1", "中間考査", startDate = "2026-10-20", endDate = "2026-10-20")), exams = listOf(exam))
    @Test fun checklistRoundTripsAndKeepsTheOriginalExamRange() {
        assertEquals(4, JSONObject(BackupCodec.encode(data())).getInt("version"))
        assertEquals(data(), BackupCodec.decode(BackupCodec.encode(data())))
        assertEquals(data(), BackupCodec.decode(BackupCodec.encode(data(), true), true))
        assertEquals(3, JSONObject(BackupCodec.encode(data().copy(exams = listOf(exam.copy(checklist = emptyList()))))).getInt("version"))
    }
    @Test fun unknownStatesAndDuplicateIdsAreRejected() {
        for (items in listOf(listOf(exam.checklist[0], exam.checklist[0]), listOf(ChecklistItem("bad", "p.20", "unknown")), listOf(ChecklistItem("bad", "  ")))) {
            try { BackupCodec.validate(data().copy(exams = listOf(exam.copy(checklist = items)))); fail("accepted invalid checklist") } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun unsupportedSchemasAndNonStringTitlesAreRejected() {
        val json = JSONObject(BackupCodec.encode(data()))
        json.getJSONArray("exams").getJSONObject(0).getJSONArray("checklist").getJSONObject(0).put("title", 42)
        try { BackupCodec.decode(json.toString()); fail("accepted non-string title") } catch (_: IllegalArgumentException) { }
        json.put("version", 5)
        try { BackupCodec.decode(json.toString()); fail("accepted unknown schema") } catch (_: IllegalArgumentException) { }
    }
    @Test fun standaloneTimersAlwaysFinishDoneWhileLinkedTimersRetainPartialProgress() {
        val timer = TimerSession(subjectId = "s1", targetMs = 25 * 60_000L, originalStart = "2026-10-08T18:00", runWall = 100_000, runMonotonic = 100_000, boot = 1)
        for (elapsed in listOf(0L, 1_000L, 60_000L, 25 * 60_000L)) {
            val point = ClockPoint(100_000 + elapsed, 100_000 + elapsed, 1)
            val result = StudyData(timer = timer).finishTimer(point)
            assertEquals("done", result.schedules.single().status)
            assertEquals(elapsed / 3_600_000.0, result.schedules.single().actual, .00000001)
            assertEquals(result, result.finishTimer(point))
        }
        val plan = Schedule("p1", "s1", "2026-10-08T18:00", 1.0)
        val linked = StudyData(timer = timer.copy(scheduleId = plan.id), schedules = listOf(plan)).finishTimer(ClockPoint(160_000,160_000,1))
        assertEquals("partial", linked.schedules.single().status)
    }
}

package jp.studycopi

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class StudyModelTest {
    private val group = ExamGroup("g1", "第2回模試", "模試", "2026-10-20", "2026-10-20")
    private val schedule = Schedule("p1", "s1", "2026-10-03T17:00", 0.75, examGroupId = "g1")
    private val timer = TimerSession("t1", "s1", "g1", "p1", 1_500_000, "2026-10-03T17:00",
        10_000_000, 1_000_000, 1)
    @Test fun explicitZeroAndPartialTimeAreKept() {
        assertEquals(0.0, schedule.copy(status = "done", actualDuration = 0.0).actual, 0.0)
        assertEquals(0.75, schedule.copy(status = "done").actual, 0.0)
        val partial = schedule.copy(status = "partial", actualDuration = 1.0 / 3)
        assertEquals(1.0 / 3, partial.complete().actual, 0.00001)
        assertEquals("done", partial.complete().status)
    }
    @Test fun timersAccumulateAndFinishExactlyOnce() {
        val data = StudyData(schedules = listOf(schedule.copy(actualDuration = 1.0 / 3, status = "partial")), examGroups = listOf(group), timer = timer)
        val now = ClockPoint(10_600_000, 1_600_000, 1)
        val result = data.finishTimer(now)
        assertEquals(0.5, result.schedules.single().actual, 0.00001)
        assertEquals("partial", result.schedules.single().status)
        assertEquals(result, result.finishTimer(now))
        assertEquals("g1", result.schedules.single().examGroupId)
    }
    @Test fun pauseExcludesIdleTimeAndResumeUsesNewAnchor() {
        val paused = timer.togglePause(ClockPoint(10_600_000, 1_600_000, 1))
        assertEquals(600_000, paused.elapsed(ClockPoint(10_900_000, 1_900_000, 1)))
        val resumed = paused.togglePause(ClockPoint(10_900_000, 1_900_000, 1))
        assertEquals(900_000, resumed.elapsed(ClockPoint(11_200_000, 2_200_000, 1)))
    }
    @Test fun wallClockChangesDoNotChangeElapsedOnTheSameBoot() {
        assertEquals(60_000, timer.elapsed(ClockPoint(99_999_999, 1_060_000, 1)))
        assertEquals(120_000, timer.elapsed(ClockPoint(10_120_000, 500, 2)))
        assertEquals(timer.targetMs, timer.elapsed(ClockPoint(20_000_000, 9_000_000, 1)))
    }
    @Test fun standaloneSessionKeepsOriginalDateAndGroup() {
        val standalone = timer.copy(scheduleId = "")
        val result = StudyData(examGroups = listOf(group), timer = standalone).finishTimer(ClockPoint(11_500_000, 2_500_000, 1))
        assertEquals("2026-10-03T17:00", result.schedules.single().datetime)
        assertEquals("g1", result.schedules.single().examGroupId)
        assertEquals("done", result.schedules.single().status)
        assertEquals(25.0 / 60, result.schedules.single().actual, 0.00001)
    }
    @Test fun groupsRemainSeparateEvenOnTheSameDay() {
        val second = group.copy(id = "g2", name = "中間考査", type = "定期考査")
        val data = StudyData(examGroups = listOf(group, second), schedules = listOf(schedule, schedule.copy(id = "p2", examGroupId = "g2"), schedule.copy(id = "p3", examGroupId = "")))
        assertEquals(1, data.forExam("g1").size); assertEquals(1, data.forExam("unassigned").size)
        val deleted = data.withoutGroup("g1")
        assertEquals(3, deleted.schedules.size); assertEquals(2, deleted.forExam("unassigned").size)
    }
    @Test fun backupRoundTripsAndInternalTimerDoesNotLeak() {
        val original = StudyData(examGroups = listOf(group), schedules = listOf(schedule.copy(actualDuration = 0.0)), timer = timer)
        val external = BackupCodec.decode(BackupCodec.encode(original))
        assertEquals(original.copy(timer = null), external)
        assertEquals(original, BackupCodec.decode(BackupCodec.encode(original, true), true))
    }
    @Test fun legacyBackupRetainsUnassignedRecordsAndDoneFallback() {
        val json = """{"subjects":[{"id":"s1","name":"数学","color":"#185fa5"}],"schedules":[{"id":"p1","subjectId":"s1","datetime":"2026-10-03T17:00","duration":1,"status":"done"}],"exams":[]}"""
        val data = BackupCodec.decode(json)
        assertTrue(data.examGroups.isEmpty()); assertEquals("", data.schedules.single().examGroupId)
        assertEquals(1.0, data.schedules.single().actual, 0.0)
    }
    @Test fun webExamNotesAndTimeZonesArePreserved() {
        val json = """{"version":3,"subjects":[{"id":"s1","name":"数学","color":"#185fa5"}],"schedules":[{"id":"p1","subjectId":"s1","datetime":"2026-10-03T08:00:00.000Z","duration":1,"actualDuration":0,"status":"done"}],"exams":[{"id":"e1","subject":"数学","date":"2026-10-20","startTime":"09:00","endTime":"10:00","note":"問題集p.20〜45"}]}"""
        val restored = BackupCodec.decode(json)
        assertEquals("問題集p.20〜45", restored.exams.single().range)
        val exported = org.json.JSONObject(BackupCodec.encode(restored))
        assertEquals("問題集p.20〜45", exported.getJSONArray("exams").getJSONObject(0).getString("note"))
        assertEquals("2026-10-03T08:00:00.000Z", restored.schedules.single().datetime)
        assertEquals(0.0, restored.schedules.single().actual, 0.0)
    }
    @Test fun corruptOrDanglingBackupsAreRejectedBeforeReplacingAnything() {
        val valid = StudyData(examGroups = listOf(group), schedules = listOf(schedule))
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.validate(valid.copy(examGroups = emptyList())) }
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.validate(valid.copy(schedules = listOf(schedule.copy(duration = Double.NaN)))) }
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.validate(valid.copy(examGroups = listOf(group.copy(endDate = "2026-10-19")))) }
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.validate(valid.copy(schedules = listOf(schedule, schedule))) }
    }
    @Test fun weekBeginsOnMondayIncludingSunday() {
        assertEquals(LocalDate.parse("2026-09-28"), weekStart(LocalDate.parse("2026-10-04")))
    }
}

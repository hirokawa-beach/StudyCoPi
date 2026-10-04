package jp.studycopi

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime

class ReleaseModelTest {
    @Test fun newSubjectsAreDeterministicPersistProgressAndAvoidRepeatsAcrossBothStages() {
        for (subject in listOf("english", "history", "mixed")) {
            val alarm = WakeAlarm(id = "morning", questionSubject = subject, questions = 10).arm(System.currentTimeMillis())
            var data = StudyData(wakeAlarms = listOf(alarm)).claimWake(alarm.id, alarm.nextAt, alarm.nextAt, 42)
            val initial = data.wakeRuns.single()
            assertEquals(subject, initial.questionSubject)
            val prompts = mutableSetOf<String>()
            repeat(20) {
                val run = data.wakeRuns.single()
                assertEquals(run.quiz, BackupCodec.decode(BackupCodec.encode(data, true), true).wakeRuns.single().quiz)
                if (subject != "mixed") assertTrue("question repeated", prompts.add(run.quiz.text))
                if (run.quiz.choices.isNotEmpty()) {
                    assertEquals(4, run.quiz.choices.toSet().size)
                    assertTrue(run.quiz.answer in run.quiz.choices)
                    try { data.answerWake(run.id, run.key, run.quiz.choices.first { it != run.quiz.answer }); fail() } catch (_: IllegalArgumentException) { }
                }
                data = data.answerWake(run.id, run.key, run.quiz.answer)
                if (data.wakeRuns.isNotEmpty()) try { data.answerWake(run.id, run.key, run.quiz.answer); fail() } catch (_: IllegalArgumentException) { }
            }
            assertTrue(data.wakeRuns.isEmpty())
        }
    }
    @Test fun subjectSnapshotsSurviveChangesAndOlderBackupsDefaultToMath() {
        val alarm = WakeAlarm(id = "morning", questionSubject = "history").arm(System.currentTimeMillis())
        val fired = StudyData(wakeAlarms = listOf(alarm)).claimWake(alarm.id, alarm.nextAt, alarm.nextAt)
        assertEquals("history", fired.copy(wakeAlarms = listOf(alarm.copy(questionSubject = "english"))).wakeRuns.single().questionSubject)
        val old = JSONObject(BackupCodec.encode(StudyData(wakeAlarms = listOf(alarm))))
        old.getJSONObject("android").getJSONArray("wakeAlarms").getJSONObject(0).remove("questionSubject")
        assertEquals("math", BackupCodec.decode(old.toString()).wakeAlarms.single().questionSubject)
        try { BackupCodec.validate(StudyData(wakeAlarms = listOf(alarm.copy(questionSubject = "unknown")))); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun waitingFocusRequiresAnOptInDueUnfinishedScheduleAndNoRunningTimer() {
        val now = LocalDateTime.now().withNano(0)
        val plan = Schedule("due", "s1", now.toString(), 1.0)
        val data = StudyData(schedules = listOf(plan), preferences = Preferences(scheduledFocus = true))
        val wall = plan.startWall()
        assertEquals(listOf(plan), focusDue(data, wall))
        assertTrue(focusDue(data, wall - 1).isEmpty())
        assertTrue(focusDue(data, wall + 300000).isEmpty())
        assertTrue(focusDue(data.copy(preferences = Preferences()), wall).isEmpty())
        assertTrue(focusDue(data.copy(schedules = listOf(plan.copy(status = "done"))), wall).isEmpty())
        assertTrue(focusDue(data.copy(schedules = listOf(plan.copy(status = "miss"))), wall).isEmpty())
        val timer = TimerSession(subjectId = "s1", targetMs = 60_000, originalStart = now.toString(), runWall = wall, runMonotonic = 0, boot = 0)
        assertTrue(focusDue(data.copy(timer = timer), wall).isEmpty())
        assertNull(data.timer)
        assertEquals(0.0, data.schedules.single().actual, 0.0)
        assertTrue(BackupCodec.decode(BackupCodec.encode(data, true), true).preferences.scheduledFocus)
        assertFalse(BackupCodec.decode(BackupCodec.encode(data)).preferences.scheduledFocus)
        assertNotEquals(focusKey(plan), focusKey(plan.copy(datetime = now.plusMinutes(1).toString())))
    }
}

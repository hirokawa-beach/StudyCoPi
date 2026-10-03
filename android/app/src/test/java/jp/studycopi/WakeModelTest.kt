package jp.studycopi

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.time.*

class WakeModelTest {
    private val zone = ZoneId.of("Asia/Tokyo")
    private val morning = ZonedDateTime.of(2026, 10, 3, 6, 0, 0, 0, zone).toInstant().toEpochMilli()
    private fun alarm(mode: String = "math", days: Set<Int> = emptySet()) = WakeAlarm("wake", "朝の勉強", "07:00", days, mode,
        questions = 2, tagId = if (mode == "nfc") "04A1B2C3D4E5F6" else "").arm(morning, zone)
    private fun started(mode: String = "math") = alarm(mode).let { StudyData(wakeAlarms = listOf(it)).claimWake(it.id, it.nextAt, it.nextAt, 1234) }
    @Test fun oneShotChoosesTodayOrTomorrowAndRepeatsUseWeekdays() {
        val a = alarm()
        assertEquals(morning + 3_600_000, a.nextAt)
        assertEquals(a.nextAt + 86_400_000, a.nextAfter(a.nextAt, zone))
        val monday = alarm(days = setOf(1))
        assertEquals(DayOfWeek.MONDAY, Instant.ofEpochMilli(monday.nextAt).atZone(zone).dayOfWeek)
        assertEquals(7 * 86_400_000L, monday.nextAfter(monday.nextAt, zone) - monday.nextAt)
    }
    @Test fun daylightSavingGapProducesARealFutureInstant() {
        val newYork = ZoneId.of("America/New_York")
        val before = ZonedDateTime.of(2026, 3, 8, 0, 0, 0, 0, newYork).toInstant().toEpochMilli()
        val next = WakeAlarm(time = "02:30", days = setOf(7)).nextAfter(before, newYork)
        assertTrue(next > before)
        assertEquals(3, Instant.ofEpochMilli(next).atZone(newYork).hour)
    }
    @Test fun duplicatesStaleAndEarlyBroadcastsCannotStartOrRepeatAnAlarm() {
        val a = alarm()
        val data = StudyData(wakeAlarms = listOf(a))
        assertEquals(data, data.claimWake(a.id, a.nextAt, a.nextAt - 1))
        assertEquals(data, data.claimWake(a.id, a.nextAt - 1000, a.nextAt))
        val fired = data.claimWake(a.id, a.nextAt, a.nextAt)
        assertEquals(1, fired.wakeRuns.size)
        assertFalse(fired.wakeAlarms.single().enabled)
        assertEquals(fired, fired.claimWake(a.id, a.nextAt, a.nextAt))
    }
    @Test fun repeatingAlarmAdvancesAndMultipleAlarmsQueueTheirOwnChallenges() {
        val first = alarm(days = setOf(1,2,3,4,5,6,7))
        val second = first.copy(id = "two")
        var data = StudyData(wakeAlarms = listOf(first, second))
        data = data.claimWake(first.id, first.nextAt, first.nextAt).claimWake(second.id, second.nextAt, second.nextAt)
        assertEquals(2, data.wakeRuns.size)
        assertTrue(data.wakeAlarms.all { it.enabled && it.nextAt > first.nextAt })
        assertEquals(2, data.wakeRuns.map { it.id }.toSet().size)
    }
    @Test fun mathNeedsBothStagesAndWrongOrRepeatedAnswersDoNotAdvance() {
        var data = started()
        val old = data.wakeRuns.single()
        try { data.answerWake(old.id, old.key, "-1"); fail() } catch (_: IllegalArgumentException) { }
        assertEquals(0, data.wakeRuns.single().solved)
        data = data.answerWake(old.id, old.key, old.question.answer.toString())
        try { data.answerWake(old.id, old.key, old.question.answer.toString()); fail() } catch (_: IllegalArgumentException) { }
        repeat(3) { val run = data.wakeRuns.single(); data = data.answerWake(run.id, run.key, run.question.answer.toString()) }
        assertTrue(data.wakeRuns.isEmpty())
    }
    @Test fun nfcCannotSkipMathAndOnlyTheRegisteredTagCompletesSecondStage() {
        var data = started("nfc"); var run = data.wakeRuns.single()
        try { data.scanWake(run.id, run.tagId); fail() } catch (_: IllegalArgumentException) { }
        repeat(2) { run = data.wakeRuns.single(); data = data.answerWake(run.id, run.key, run.question.answer.toString()) }
        run = data.wakeRuns.single(); assertTrue(run.needsTag)
        try { data.scanWake(run.id, "04FFFFFFFFFFFF"); fail() } catch (_: IllegalArgumentException) { }
        try { data.answerWake(run.id, run.key, run.question.answer.toString()); fail() } catch (_: IllegalArgumentException) { }
        assertTrue(data.scanWake(run.id, run.tagId).wakeRuns.isEmpty())
    }
    @Test fun nfcIdPreservesLeadingZerosAndUnsignedBytes() {
        assertEquals("0004A1FF", nfcId(byteArrayOf(0, 4, 0xA1.toByte(), 0xFF.toByte())))
        assertTrue(validNfcId("0004A1FF")); assertFalse(validNfcId("")); assertFalse(validNfcId("04:A1"))
    }
    @Test fun runningChallengeRetainsRegisteredTagEvenWhenFutureConfigChanges() {
        val data = started("nfc")
        val changed = data.copy(wakeAlarms = data.wakeAlarms.map { it.copy(tagId = "041234", label = "変更") })
        assertEquals("04A1B2C3D4E5F6", changed.wakeRuns.single().tagId)
        assertEquals("朝の勉強", changed.wakeRuns.single().label)
    }
    @Test fun oldMissedAlarmsDoNotRingLateAndTimezoneChangesRecalculateWallTime() {
        val a = alarm()
        val data = StudyData(wakeAlarms = listOf(a))
        assertFalse(data.normalizeWakeAlarms(a.nextAt + 16 * 60_000).wakeAlarms.single().enabled)
        val repeat = a.copy(days = setOf(1,2,3,4,5,6,7))
        val rearmed = data.copy(wakeAlarms = listOf(repeat)).normalizeWakeAlarms(a.nextAt + 16 * 60_000)
        assertTrue(rearmed.wakeAlarms.single().nextAt > a.nextAt + 16 * 60_000)
    }
    @Test fun internalBackupRetainsProgressExternalBackupRestoresDisabledAlarms() {
        val data = started("nfc")
        val run = data.wakeRuns.single()
        val partial = data.answerWake(run.id, run.key, run.question.answer.toString())
        assertEquals(partial, BackupCodec.decode(BackupCodec.encode(partial, true), true))
        val enabled = partial.copy(wakeAlarms = listOf(alarm("nfc")))
        val restored = BackupCodec.decode(BackupCodec.encode(enabled))
        assertTrue(restored.wakeRuns.isEmpty()); assertFalse(restored.wakeAlarms.single().enabled)
        assertEquals("04A1B2C3D4E5F6", restored.wakeAlarms.single().tagId)
        val legacy = JSONObject(BackupCodec.encode(StudyData())).apply { remove("android") }.toString()
        assertEquals(StudyData(), BackupCodec.decode(legacy))
    }
    @Test fun validationRejectsMissingTagsInvalidDaysAndDamagedProgress() {
        listOf(WakeAlarm(secondStep = "nfc"), WakeAlarm(days = setOf(8)), WakeAlarm(time = "25:00"), WakeAlarm(questions = 0)).forEach {
            try { BackupCodec.validate(StudyData(wakeAlarms = listOf(it))); fail("accepted invalid alarm") } catch (_: Exception) { }
        }
        val bad = started().let { it.copy(wakeRuns = listOf(it.wakeRuns.single().copy(stage = 3))) }
        try { BackupCodec.validate(bad); fail() } catch (_: IllegalArgumentException) { }
    }
}

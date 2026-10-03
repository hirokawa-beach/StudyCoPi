package jp.studycopi

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35], application = StudyApplication::class)
class RepositoryTest {
    @Test fun savedDataAndPausedTimerSurviveRepositoryRecreation() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = StudyRepository(context); repo.load()
        val point = clockPoint(context)
        val timer = TimerSession(subjectId = "s1", targetMs = 60_000, originalStart = "2026-10-03T17:00",
            runWall = point.wall, runMonotonic = point.monotonic, boot = point.boot, accruedMs = 25_000, paused = true)
        val expected = repo.update { it.copy(timer = timer, schedules = listOf(Schedule(subjectId = "s1", datetime = "2026-10-03T17:00", duration = 1.0, actualDuration = 0.0))) }
        assertEquals(expected, StudyRepository(context).load())
    }
    @Test fun invalidRestoreDoesNotReplaceSavedData() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = StudyRepository(context); repo.load()
        val saved = repo.update { it.copy(schedules = listOf(Schedule(subjectId = "s1", datetime = "2026-10-03T17:00", duration = 1.0))) }
        try { repo.restore(saved.copy(schedules = listOf(saved.schedules.single().copy(duration = -1.0)))); fail("accepted invalid data") }
        catch (_: IllegalArgumentException) { }
        assertEquals(saved, StudyRepository(context).load())
    }
    @Test fun corruptDocumentIsNotSilentlyOverwritten() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.filesDir, "study-data.json"); file.writeText("broken JSON")
        val repo = StudyRepository(context)
        try { repo.load(); fail("accepted corrupt file") } catch (_: org.json.JSONException) { }
        try { repo.update { StudyData() }; fail("overwrote corrupt file") } catch (_: IllegalStateException) { }
        assertEquals("broken JSON", file.readText())
        repo.restore(StudyData()); assertEquals(StudyData(), StudyRepository(context).load())
    }
}

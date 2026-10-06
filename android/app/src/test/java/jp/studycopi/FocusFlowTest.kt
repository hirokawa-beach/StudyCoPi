package jp.studycopi

import android.app.ActivityManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h740dp", application = StudyApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FocusFlowTest {
    val compose = createAndroidComposeRule<FocusActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<StudyApplication>()
    private val seed = object : ExternalResource() {
        override fun before() {
            val now = clockPoint(app)
            runBlocking { app.repository.restore(StudyData(timer = TimerSession(subjectId = "s1", targetMs = 30 * 60_000L,
                originalStart = java.time.LocalDateTime.now().minusMinutes(1).toString(), accruedMs = 60_000,
                runWall = now.wall, runMonotonic = now.monotonic, boot = now.boot))) }
        }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(seed).around(compose)
    @Test fun asynchronousPinAndUnpinRefreshWithoutAWindowFocusEvent() {
        compose.waitUntil(15_000) { compose.onAllNodesWithText("画面固定を開始").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle {
            shadowOf(compose.activity.getSystemService(ActivityManager::class.java)).setLockTaskModeState(ActivityManager.LOCK_TASK_MODE_PINNED)
            assertTrue("OS pin state", compose.activity.isScreenPinned())
            shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(300))
        }
        compose.mainClock.advanceTimeBy(500)
        compose.waitUntil(15_000) { compose.onAllNodesWithText("画面固定中").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("画面固定を開始").assertDoesNotExist()
        compose.runOnIdle {
            shadowOf(compose.activity.getSystemService(ActivityManager::class.java)).setLockTaskModeState(ActivityManager.LOCK_TASK_MODE_NONE)
            shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(300))
        }
        compose.waitUntil(15_000) { compose.onAllNodesWithText("画面固定を開始").fetchSemanticsNodes().isNotEmpty() }
    }
    @Test fun pinPauseAndFinishRecordElapsedTimeAndReleasePin() {
        compose.waitUntil(15_000) { compose.onAllNodesWithText("画面固定を開始").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("画面固定を開始").performClick()
        assertTrue(shadowOf(compose.activity).isLockTask)
        // Model the OS accepting its confirmation dialog.
        compose.runOnIdle {
            shadowOf(app.getSystemService(ActivityManager::class.java)).setLockTaskModeState(ActivityManager.LOCK_TASK_MODE_PINNED)
            compose.activity.onWindowFocusChanged(true)
        }
        compose.onNodeWithText("画面固定中").assertIsDisplayed()
        compose.onNodeWithText("一時停止").performClick()
        compose.waitUntil(15_000) { app.repository.data.value.timer?.paused == true }
        val bitmap = compose.runOnIdle {
            val view = compose.activity.window.decorView
            android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888).also { view.draw(android.graphics.Canvas(it)) }
        }
        val file = File(requireNotNull(System.getProperty("studycopi.artifacts")), "focus-320.png"); file.parentFile.mkdirs()
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        compose.onNodeWithText("終了して記録").performScrollTo().performClick()
        compose.onNodeWithText("勉強を続ける").performClick()
        assertNotNull(app.repository.data.value.timer)
        compose.onNodeWithText("終了して記録").performClick()
        compose.onAllNodesWithText("終了して記録").onLast().performClick()
        compose.waitUntil(15_000) { app.repository.data.value.timer == null }
        compose.waitUntil(15_000) { !shadowOf(compose.activity).isLockTask }
        assertTrue(app.repository.data.value.schedules.single().actual >= 1.0 / 60)
    }
}

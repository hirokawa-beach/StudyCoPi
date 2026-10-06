package jp.studycopi

import android.app.ActivityManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
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
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h740dp", application = StudyApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScheduledFocusFlowTest {
    private val app get() = ApplicationProvider.getApplicationContext<StudyApplication>()
    private val plan = Schedule("due", "s1", LocalDateTime.now().minusSeconds(1).toString(), 1.0, "問題集 p.12")
    private val activityRule = ActivityScenarioRule<ScheduledFocusActivity>(ScheduledFocus.intent(app, plan))
    val compose = AndroidComposeTestRule(activityRule) { rule ->
        var activity: ScheduledFocusActivity? = null
        rule.scenario.onActivity { activity = it }; requireNotNull(activity)
    }
    private val seed = object : ExternalResource() {
        override fun before() {
            runBlocking {
                app.repository.restore(StudyData(schedules = listOf(plan)))
                app.repository.update { it.copy(preferences = it.preferences.copy(scheduledFocus = true)) }
            }
        }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(seed).around(compose)
    @Test fun timerStartsOnlyWhenTheUserPressesStartAndStaysLinkedToThePlan() {
        compose.waitUntil(15000) { compose.onAllNodesWithText("勉強を始める").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(15000) { ScheduledFocus.seen(app, plan) }
        assertNull(app.repository.data.value.timer)
        compose.onNodeWithText("勉強を始める").performScrollTo().performClick()
        compose.waitUntil(15000) { app.repository.data.value.timer != null }
        assertEquals(plan.id, app.repository.data.value.timer?.scheduleId)
        assertEquals(3600000L, app.repository.data.value.timer?.targetMs)
        assertEquals(0.0, app.repository.data.value.schedules.single().actual, 0.0)
        compose.waitUntil(15000) { compose.activity.isFinishing }
        assertEquals(FocusActivity::class.java.name, shadowOf(compose.activity).nextStartedActivity.component?.className)
    }
    @Test fun waitingScreenDoesNotRunTimerSurvivesRecreationAndReleasesWithoutARecord() {
        compose.waitUntil(15000) { compose.onAllNodesWithText("タイマーはまだ開始していません。勉強時間も記録されません。").fetchSemanticsNodes().isNotEmpty() }
        assertNull(app.repository.data.value.timer)
        assertEquals(0.0, app.repository.data.value.schedules.single().actual, 0.0)
        assertTrue(ScheduledFocus.seen(app, plan))
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("勉強の時間です").assertIsDisplayed()
        assertNull(app.repository.data.value.timer)
        compose.onNodeWithText("画面固定を開始").performScrollTo().performClick()
        compose.runOnIdle {
            shadowOf(app.getSystemService(ActivityManager::class.java)).setLockTaskModeState(ActivityManager.LOCK_TASK_MODE_PINNED)
        }
        compose.waitUntil(15000) { compose.onAllNodesWithText("画面固定中").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("画面固定中").performScrollTo().assertIsDisplayed()
        val bitmap = compose.runOnIdle {
            val view = compose.activity.window.decorView
            android.graphics.Bitmap.createBitmap(view.width,view.height,android.graphics.Bitmap.Config.ARGB_8888).also { view.draw(android.graphics.Canvas(it)) }
        }
        val file = File(requireNotNull(System.getProperty("studycopi.artifacts")), "scheduled-focus-320.png")
        file.parentFile?.mkdirs(); file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
        compose.onNodeWithText("今回は解除する").performScrollTo().performClick()
        compose.onNodeWithText("解除して戻る").performClick()
        compose.waitUntil(15000) { compose.activity.isFinishing }
        assertNull(app.repository.data.value.timer)
        assertEquals(0.0, app.repository.data.value.schedules.single().actual, 0.0)
    }
}

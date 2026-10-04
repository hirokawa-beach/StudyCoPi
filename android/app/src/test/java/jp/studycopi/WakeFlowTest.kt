package jp.studycopi

import android.Manifest
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
@Config(sdk = [35], qualifiers = "w320dp-h740dp-night", application = StudyApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WakeFlowTest {
    val compose = createAndroidComposeRule<WakeActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<StudyApplication>()
    private val seed = object : ExternalResource() {
        override fun before() {
            shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
            runBlocking { app.repository.restore(StudyData(wakeRuns = listOf(
                WakeRun("run", "morning", "学校の日", "math", 1, "", "", 4, System.currentTimeMillis())))) }
        }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(seed).around(compose)
    private fun ready() { compose.waitUntil(15_000) { compose.onAllNodesWithText("回答").fetchSemanticsNodes().isNotEmpty() } }
    private fun capture(name: String) {
        val bitmap = compose.runOnIdle {
            val view = compose.activity.window.decorView
            android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888).also { view.draw(android.graphics.Canvas(it)) }
        }
        val file = File(requireNotNull(System.getProperty("studycopi.artifacts")), name); file.parentFile.mkdirs()
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun wrongAnswersDoNotDismissAndSecondStepSurvivesActivityRecreation() {
        ready()
        assertTrue(shadowOf(compose.activity).showWhenLocked); assertTrue(shadowOf(compose.activity).turnScreenOn)
        compose.onNodeWithText("答え").performTextInput("0")
        compose.onNodeWithText("回答").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("答えが違います").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, app.repository.data.value.wakeRuns.single().stage)
        compose.onNodeWithText("答え").performTextClearance()
        compose.onNodeWithText("答え").performTextInput(app.repository.data.value.wakeRuns.single().question.answer.toString())
        compose.onNodeWithText("回答").performClick()
        compose.waitUntil(15_000) { app.repository.data.value.wakeRuns.single().stage == 2 }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("ステップ 2 / 2").assertIsDisplayed()
        compose.onNodeWithText("答え").assert(SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
        capture("wake-math-dark-320.png")
        compose.onNodeWithText("答え").performTextInput(app.repository.data.value.wakeRuns.single().question.answer.toString())
        compose.onNodeWithText("回答").performClick()
        compose.waitUntil(15_000) { app.repository.data.value.wakeRuns.isEmpty() }
    }
    @Test fun englishMultipleChoiceRequiresCorrectAnswersAndSurvivesRotation() {
        ready()
        runBlocking { app.repository.update { it.copy(wakeRuns = it.wakeRuns.map { run -> run.copy(questionSubject = "english") }) } }
        compose.waitUntil(15000) { compose.onAllNodesWithText("まずは英語").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("答え").assertDoesNotExist()
        val first = app.repository.data.value.wakeRuns.single()
        compose.onNodeWithText(first.quiz.choices.first { it != first.quiz.answer }).performScrollTo().performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithText("答えが違います").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, app.repository.data.value.wakeRuns.single().stage)
        compose.onNodeWithText(first.quiz.answer).performScrollTo().performClick()
        compose.waitUntil(15000) { app.repository.data.value.wakeRuns.single().stage == 2 }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("最後の英語").assertExists()
        val second = app.repository.data.value.wakeRuns.single()
        assertNotEquals(first.quiz.text, second.quiz.text)
        capture("wake-english-dark-320.png")
        compose.onNodeWithText(second.quiz.answer).performScrollTo().performClick()
        compose.waitUntil(15000) { app.repository.data.value.wakeRuns.isEmpty() }
    }
    @Test fun nfcStageShowsRegisteredDestinationAndEmergencyStopRequiresConfirmation() {
        ready()
        runBlocking { app.repository.update { it.copy(wakeRuns = it.wakeRuns.map { run ->
            run.copy(secondStep = "nfc", stage = 2, tagId = "0012AB34", tagName = "1階のタグ") }) } }
        compose.onNodeWithText("1階のタグを読み取る").assertIsDisplayed()
        compose.onNodeWithText("答え").assertDoesNotExist()
        capture("wake-nfc-dark-320.png")
        compose.onNodeWithContentDescription("緊急停止、10秒長押し").performScrollTo()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("続ける").performClick()
        assertEquals(1, app.repository.data.value.wakeRuns.size)
        compose.onNodeWithContentDescription("緊急停止、10秒長押し")
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("緊急停止", substring = false).performClick()
        compose.waitUntil(15_000) { app.repository.data.value.wakeRuns.isEmpty() }
    }
}

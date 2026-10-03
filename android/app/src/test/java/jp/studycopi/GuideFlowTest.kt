package jp.studycopi

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h740dp", application = StudyApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GuideFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<StudyApplication>()
    private fun ready() { compose.waitUntil(15_000) { compose.onAllNodesWithTag("study-guide").fetchSemanticsNodes().isNotEmpty() } }
    private fun capture(name: String) {
        val bitmap = compose.runOnIdle {
            val views = android.view.inspector.WindowInspector.getGlobalWindowViews()
            val view = views.last()
            android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888).also { bitmap ->
                val canvas = android.graphics.Canvas(bitmap)
                views.forEach { it.draw(canvas) }
            }
        }
        val file = File(requireNotNull(System.getProperty("studycopi.artifacts")), name); file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
    }
    @Test fun skipIsRememberedAndGuideCanBeReplayedFromHelp() {
        ready(); compose.onNodeWithText("戻る").assertIsNotEnabled()
        compose.onNodeWithText("あとで").performClick()
        assertTrue(GuideContent.seen(app,1))
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("study-guide").assertDoesNotExist()
        compose.onNodeWithText("その他",useUnmergedTree=true).performClick()
        compose.onNodeWithText("使い方").performScrollTo().performClick()
        compose.onNodeWithText("チュートリアルを見る").performClick()
        compose.onNodeWithText("1 / 5").assertExists()
        compose.onNodeWithText("次へ").performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("2 / 5").assertExists()
        compose.onNodeWithText("あとで").performClick()
        compose.onNodeWithText("予定を追加・編集する").performScrollTo().performClick()
        compose.onNodeWithText("予定を開く").performScrollTo().performClick()
        compose.onNodeWithText("予定一覧").assertExists()
    }
    @Test @Config(qualifiers = "w320dp-h740dp-night") fun allStepsFitLargeJapaneseTextAndDoNotModifyStudyData() {
        ready(); RuntimeEnvironment.setFontScale(1.3f)
        val before = app.repository.data.value
        compose.onNodeWithTag("guide-target-add", useUnmergedTree = true).assertExists()
        capture("guide-dark-320.png")
        repeat(4) {
            compose.onNodeWithText("次へ").assertIsDisplayed().performClick()
            if (it == 2) {
                compose.onNodeWithTag("guide-target-record", useUnmergedTree = true).assertExists()
                compose.onNodeWithText("次へ").assertIsDisplayed()
                compose.onNodeWithText("戻る").assertIsDisplayed()
                val target = compose.onNodeWithTag("guide-target-record", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                val next = compose.onNodeWithText("次へ").fetchSemanticsNode().boundsInRoot
                assertTrue("The guide actions must leave the example card visible", next.bottom < target.top || next.top > target.bottom)
                capture("guide-record-dark-320.png")
            }
        }
        compose.onNodeWithText("5 / 5").assertExists()
        compose.onNodeWithText("使い始める").assertIsDisplayed().performClick()
        assertEquals(before, app.repository.data.value)
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("study-guide").assertDoesNotExist()
    }
}

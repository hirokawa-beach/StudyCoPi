package jp.studycopi

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h740dp-night", application = StudyApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SoundFormFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun selectedAlarmSoundSurvivesSavingAndActivityRecreation() {
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("study-guide").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("あとで").performClick()
        compose.onNodeWithText("その他", useUnmergedTree = true).performClick()
        compose.onNodeWithText("目覚まし").performClick()
        compose.onNodeWithText("追加").performClick()
        compose.onNodeWithContentDescription("アラーム音").performScrollTo().performClick()
        compose.onNodeWithText("チャイム").performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithContentDescription("アラーム音").performScrollTo().assertTextEquals("チャイム")
        compose.onNodeWithText("保存").performClick()
        val app = ApplicationProvider.getApplicationContext<StudyApplication>()
        compose.waitUntil(15_000) { app.repository.data.value.wakeAlarms.isNotEmpty() }
        assertEquals("chime", app.repository.data.value.wakeAlarms.single().sound)
        compose.activityRule.scenario.recreate()
        compose.onNodeWithContentDescription("目覚ましを編集").performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        compose.onNodeWithContentDescription("アラーム音").performScrollTo().assertTextEquals("チャイム")
    }
}

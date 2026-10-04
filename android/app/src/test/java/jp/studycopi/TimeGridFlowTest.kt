package jp.studycopi

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h740dp-night", application = StudyApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TimeGridFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun timeGridHasReadableShortPlansAndOpensTheActualPlan() {
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("study-guide").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("あとで").performClick()
        RuntimeEnvironment.setFontScale(1.3f)
        val today = weekStart(LocalDate.now())
        val name = "数学・場合の数と確率の間違えた問題の復習"
        val app = ApplicationProvider.getApplicationContext<StudyApplication>()
        runBlocking { app.repository.restore(StudyData(subjects = listOf(Subject("jp", name)), schedules = listOf(
            Schedule("one", "jp", "${today}T06:00", 1.0 / 60), Schedule("five", "jp", "${today}T06:01", 5.0 / 60),
            Schedule("late", "jp", "${today}T23:59", 1.0 / 60)))) }
        compose.onNodeWithText("予定", useUnmergedTree = true).performClick()
        compose.onNodeWithText("時間割").performClick()
        compose.onNodeWithTag("week-time-grid").performScrollTo()
        for (id in listOf("one", "five")) {
            val node = compose.onNodeWithTag("time-name-plan-$id", useUnmergedTree = true)
            node.assertExists().assertTextEquals(name)
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertFalse(layouts.single().didOverflowHeight)
            assertFalse(layouts.single().didOverflowWidth)
            val bounds = node.fetchSemanticsNode().boundsInRoot
            val card = compose.onNodeWithTag("time-plan-$id", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.bottom <= card.bottom)
        }
        val first = compose.onNodeWithTag("time-plan-one", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithTag("time-plan-five", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(first.right <= second.left || second.right <= first.left)
        val bitmap = compose.runOnIdle {
            val view = compose.activity.window.decorView
            android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888).also { view.draw(android.graphics.Canvas(it)) }
        }
        File(requireNotNull(System.getProperty("studycopi.artifacts")), "time-grid-dark-320.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
        compose.onNodeWithTag("time-plan-one").performClick()
        compose.onNodeWithText("学習予定を編集").assertExists()
    }
}

package jp.studycopi

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
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
@Config(sdk = [35], qualifiers = "w390dp-h844dp", application = StudyApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComposeFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun ready() { compose.waitUntil(15_000) { compose.onAllNodesWithText("今日の学習").fetchSemanticsNodes().isNotEmpty() || compose.onAllNodesWithTag("study-guide").fetchSemanticsNodes().isNotEmpty() } }
    @Before fun resetData() {
        ready()
        compose.waitForIdle()
        if (compose.onAllNodesWithText("あとで").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithText("あとで").performClick()
        runBlocking { ApplicationProvider.getApplicationContext<StudyApplication>().repository.restore(StudyData()) }
        compose.waitForIdle()
    }
    private fun capture(name: String) {
        val directory = File(requireNotNull(System.getProperty("studycopi.artifacts"))); directory.mkdirs()
        val bitmap = compose.runOnIdle {
            val view = android.view.inspector.WindowInspector.getGlobalWindowViews().lastOrNull() ?: compose.activity.window.decorView
            android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888).also {
                view.draw(android.graphics.Canvas(it))
            }
        }
        File(directory, name).outputStream().use { output ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
        }
    }
    @Test fun createExamThenPlanAndCompleteWithUndo() {
        ready()
        val app = ApplicationProvider.getApplicationContext<StudyApplication>()
        compose.onNodeWithText("試験").performClick()
        compose.onNodeWithText("追加", useUnmergedTree = true).performClick()
        compose.onNodeWithText("試験名").performTextInput("第2回模試")
        compose.onNodeWithText("保存").performClick()
        try { compose.waitUntil(15_000) { app.repository.data.value.examGroups.size == 1 } }
        catch (error: Throwable) {
            runCatching { capture("group-error.png") }
            println(compose.onAllNodes(isRoot()).onLast().printToString()); throw error
        }
        compose.onAllNodes(hasText("学習予定") and hasClickAction()).onFirst().performClick()
        compose.onNodeWithText("追加", useUnmergedTree = true).performClick()
        compose.onNodeWithText("学習内容（任意）").performTextInput("問題集 p.24〜28")
        compose.onNodeWithText("予定時間（分）").performTextClearance()
        compose.onNodeWithText("予定時間（分）").performTextInput("45")
        compose.onNodeWithText("保存").performClick()
        try { compose.waitUntil(15_000) { app.repository.data.value.schedules.size == 1 } }
        catch (error: Throwable) { runCatching { capture("form-error.png") }; println(compose.onAllNodes(isRoot()).onLast().printToString()); throw error }
        compose.onNodeWithText("今日", useUnmergedTree = true).performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("問題集 p.24〜28").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(app.repository.data.value.examGroups.single().id, app.repository.data.value.schedules.single().examGroupId)
        compose.onNodeWithContentDescription("数学の完了を切り替え").performClick()
        compose.waitUntil(15_000) { app.repository.data.value.schedules.single().status == "done" }
        // Earlier save notices can precede the undo notice; dismiss them deterministically.
        compose.waitUntil(15_000) { compose.onAllNodesWithText("元に戻す").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("元に戻す").performClick()
        compose.waitUntil(15_000) { app.repository.data.value.schedules.single().status == "pending" }
        capture("today.png")
        compose.onNodeWithText("試験").performClick()
        capture("exams.png")
    }
    @Test fun wakeAlarmCanBeSavedOfflineAndNfcRequiresRegistration() {
        ready()
        val app = ApplicationProvider.getApplicationContext<StudyApplication>()
        compose.onNodeWithText("その他", useUnmergedTree = true).performClick()
        compose.onNodeWithText("目覚まし").performClick()
        compose.onNodeWithText("追加", useUnmergedTree = true).performClick()
        compose.onNodeWithText("目覚ましの名前").performTextClearance()
        compose.onNodeWithText("保存").performClick()
        compose.onNodeWithText("目覚ましの名前を入力してください（100文字以内）").assertExists()
        compose.onNodeWithText("目覚ましの名前").performTextInput("学校の日")
        compose.onNodeWithContentDescription("問題の教科").performScrollTo().performClick()
        compose.onNodeWithText("世界史", useUnmergedTree = true).performClick()
        compose.onNodeWithContentDescription("ステップ2の解除方法").performScrollTo().performClick()
        compose.onNodeWithText("登録したNFCタグ").performClick()
        compose.onNodeWithText("保存").performClick()
        compose.onNodeWithText("NFCタグと置く場所を登録してください").assertExists()
        compose.onNodeWithContentDescription("ステップ2の解除方法").performScrollTo().performClick()
        compose.onNodeWithText("追加の問題").performClick()
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil(15_000) { app.repository.data.value.wakeAlarms.size == 1 }
        assertEquals("学校の日", app.repository.data.value.wakeAlarms.single().label)
        assertEquals("history", app.repository.data.value.wakeAlarms.single().questionSubject)
        capture("wake-alarms.png")
    }
    @Test fun aboutShowsInstalledVersionAndWidgetChooserIncludesSixTypes() {
        ready()
        compose.onNodeWithText("その他", useUnmergedTree = true).performClick()
        compose.onNodeWithText("StudyCoPiについて").performScrollTo().performClick()
        compose.onNodeWithText("バージョン 1.4.1 · Android版").assertIsDisplayed()
        compose.onNodeWithText("ひろかわびーち（hirokawa-beach）").assertExists()
        capture("about-native.png")
        compose.onNodeWithContentDescription("戻る").performClick()
        compose.onNodeWithText("ウィジェット").performScrollTo().performClick()
        compose.onNodeWithText("ホーム画面ウィジェット").assertIsDisplayed()
        capture("widget-chooser.png")
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("次の学習予定"))
        compose.onNodeWithText("次の学習予定").assertIsDisplayed()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("予定時間と累積勉強時間"))
        compose.onNodeWithText("予定時間と累積勉強時間").assertIsDisplayed()
    }
    @Test fun nativeScreensAndTimerWorkWithRestoredWebData() {
        ready()
        val app = ApplicationProvider.getApplicationContext<StudyApplication>()
        val date = LocalDate.now().toString()
        runBlocking { app.repository.restore(StudyData(examGroups = listOf(ExamGroup("g1", "中間考査", startDate = date, endDate = date)),
            schedules = listOf(Schedule("p1", "s1", "${date}T17:00", 0.75, "問題集", status = "partial", actualDuration = 1.0 / 3, examGroupId = "g1")))) }
        compose.waitForIdle()
        compose.onNodeWithText("開始").performClick()
        compose.waitUntil(15_000) { app.repository.data.value.timer != null }
        compose.onNodeWithText("一時停止").performClick()
        compose.waitUntil(15_000) { app.repository.data.value.timer?.paused == true }
        capture("timer.png")
        compose.onNodeWithText("予定", useUnmergedTree = true).performClick()
        compose.onNodeWithContentDescription("タイマーに戻る").assertExists().performClick()
        compose.onNodeWithText("終了して記録").performClick()
        compose.onAllNodesWithText("終了して記録").onLast().performClick()
        compose.waitUntil(15_000) { app.repository.data.value.timer == null }
        assertTrue(app.repository.data.value.schedules.single().actual >= 1.0 / 3)
        compose.onNodeWithText("統計", useUnmergedTree = true).performClick()
        compose.onNodeWithText("学習の統計").assertExists()
        capture("stats.png")
        compose.onNodeWithText("その他", useUnmergedTree = true).performClick()
        compose.onNodeWithText("バックアップ").performClick()
        compose.onNodeWithText("ファイルから復元").assertExists()
    }
    @Test @Config(qualifiers = "w320dp-h740dp-night") fun narrowDarkScreenAndValidationStayUsable() {
        ready()
        RuntimeEnvironment.setFontScale(1.2f)
        val app = ApplicationProvider.getApplicationContext<StudyApplication>()
        val date = LocalDate.now().toString()
        runBlocking { app.repository.restore(StudyData(examGroups = listOf(ExamGroup("g1", "2学期中間考査", startDate = date, endDate = date)),
            schedules = listOf(Schedule("p1", "s1", "${date}T17:00", 1.5, "問題集の間違えた問題をもう一度解いて解説を確認する", examGroupId = "g1")))) }
        compose.waitForIdle()
        compose.onNodeWithText("開始").assertIsDisplayed()
        capture("today-dark-320.png")
        compose.onNodeWithText("試験").performClick()
        compose.onNodeWithText("追加", useUnmergedTree = true).performClick()
        compose.onNodeWithText("保存").performClick()
        compose.onNodeWithText("試験名を入力してください").assertExists()
        compose.onNodeWithText("試験名").performTextInput("第3回模試")
        capture("exam-form-dark-320.png")
        compose.onNodeWithText("閉じる").performClick()
        compose.onAllNodes(hasText("学習予定") and hasClickAction()).onFirst().performClick()
        compose.onNodeWithText("週間表示").performClick()
        compose.onNodeWithText("週間の予定").assertExists()
    }

    @Test @Config(qualifiers = "en-rUS-w320dp-h740dp-night") fun shortWeeklyPlansKeepJapaneseNamesAndTouchTargets() {
        ready()
        RuntimeEnvironment.setFontScale(1.3f)
        val app = ApplicationProvider.getApplicationContext<StudyApplication>()
        val date = LocalDate.now().toString()
        val name = "数学・場合の数と確率の復習"
        runBlocking { app.repository.restore(StudyData(
            subjects = listOf(Subject("jp", name)),
            schedules = listOf(Schedule("one", "jp", "${date}T17:00", 1.0 / 60),
                Schedule("five", "jp", "${date}T17:01", 5.0 / 60)))) }
        compose.onNodeWithText("予定", useUnmergedTree = true).performClick()
        compose.onNodeWithText("週間表示").performClick()
        val firstName = compose.onNodeWithTag("subject-one", useUnmergedTree = true)
        firstName.performScrollTo().assertIsDisplayed()
        firstName.assertTextEquals(name)
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        firstName.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertFalse(layouts.single().didOverflowHeight)
        assertFalse(layouts.single().didOverflowWidth)
        assertEquals(JapaneseFont, layouts.single().layoutInput.style.fontFamily)
        assertEquals("ja", compose.activity.resources.configuration.locales[0].language)
        capture("week-short-dark-320.png")
        compose.onNodeWithTag("plans-list").performScrollToNode(hasTestTag("schedule-five"))
        compose.onNodeWithTag("subject-five", useUnmergedTree = true).performScrollTo().assertIsDisplayed().assertTextEquals(name)
        val record = compose.onAllNodesWithText("記録").onLast()
        record.performScrollTo().assertIsDisplayed()
        record.performClick()
        compose.onNodeWithText("実績を記録").assertExists()
        compose.onNodeWithText("予定 5分", substring = true).assertExists()
    }
}

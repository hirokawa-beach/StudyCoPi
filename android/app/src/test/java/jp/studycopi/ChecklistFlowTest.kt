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
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp", application = StudyApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChecklistFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<StudyApplication>()
    @Before fun prepare() {
        compose.waitUntil(15_000) { compose.onAllNodesWithText("今日の学習").fetchSemanticsNodes().isNotEmpty() || compose.onAllNodesWithText("あとで").fetchSemanticsNodes().isNotEmpty() }
        if (compose.onAllNodesWithText("あとで").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithText("あとで").performClick()
        val date=LocalDate.now().plusDays(7).toString()
        runBlocking { app.repository.restore(StudyData(examGroups=listOf(ExamGroup("g1","中間考査A",startDate=date,endDate=date),ExamGroup("g2","模試B",startDate=LocalDate.now().plusDays(14).toString(),endDate=LocalDate.now().plusDays(14).toString())),
            exams=listOf(ExamSession("e1","数学",date,range="問題集 p.20〜40",examGroupId="g1"),ExamSession("e2","数学",LocalDate.now().plusDays(14).toString(),examGroupId="g2")))) }
        compose.waitForIdle()
    }
    @Test fun checklistStatesAreSavedPerExamAndExamEditingPreservesThem() {
        compose.onNodeWithText("試験",useUnmergedTree=true).performClick()
        compose.onAllNodesWithText("教科別の日程 1件").onFirst().performScrollTo().performClick()
        compose.onNodeWithText("範囲チェックリスト · 0 / 0件 完了").performScrollTo().performClick()
        compose.onNodeWithText("範囲を追加（1行1項目）").performTextInput("問題集 p.20〜25\n問題集 p.26〜30")
        compose.onNodeWithText("追加する",substring=false).performScrollTo().performClick()
        compose.waitUntil(15_000) { app.repository.data.value.exams.find { it.id=="e1" }?.checklist?.size==2 }
        val first=app.repository.data.value.exams.find { it.id=="e1" }!!.checklist.first().id
        compose.onNodeWithTag("checklist-list").performScrollToNode(hasTestTag("check-$first"))
        compose.onNodeWithTag("check-$first").performClick()
        compose.waitUntil(15_000) { app.repository.data.value.exams.find { it.id=="e1" }!!.checklist.first().status=="done" }
        val second=app.repository.data.value.exams.find { it.id=="e1" }!!.checklist.last().id
        compose.onNodeWithText("未着手").performScrollTo().performClick(); compose.onNodeWithTag("checklist-option-$second-review").performClick()
        compose.waitUntil(15_000) { app.repository.data.value.exams.find { it.id=="e1" }!!.checklist.last().status=="review" }
        assertTrue(app.repository.data.value.exams.find { it.id=="e2" }!!.checklist.isEmpty())
        compose.onNodeWithTag("checklist-list").performScrollToNode(hasTestTag("check-$first"))
        compose.onNodeWithText("問題集 p.20〜25").performClick()
        compose.onNodeWithText("範囲の名前").performScrollTo().performTextClearance();compose.onNodeWithText("範囲の名前").performTextInput("二次関数・前半")
        compose.onNodeWithText("保存").performScrollTo().performClick()
        compose.waitUntil(15_000) { app.repository.data.value.exams.find { it.id=="e1" }!!.checklist.first().title=="二次関数・前半" }
        compose.waitForIdle()
        val bitmap=compose.runOnIdle { val view=android.view.inspector.WindowInspector.getGlobalWindowViews().lastOrNull() ?: compose.activity.window.decorView; android.graphics.Bitmap.createBitmap(view.width,view.height,android.graphics.Bitmap.Config.ARGB_8888).also { view.draw(android.graphics.Canvas(it)) } }
        val file=File(requireNotNull(System.getProperty("studycopi.artifacts")),"checklist-390.png");file.parentFile?.mkdirs();file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
        compose.onNodeWithText("閉じる").performClick()
        compose.onNodeWithContentDescription("数学の日程を編集").performClick()
        compose.onNodeWithText("教科名").performTextClearance();compose.onNodeWithText("教科名").performTextInput("数学I")
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil(15_000) { app.repository.data.value.exams.find { it.id=="e1" }!!.subject=="数学I" }
        assertEquals(listOf("done","review"),app.repository.data.value.exams.find { it.id=="e1" }!!.checklist.map { it.status })
    }
    @Test fun startingFromTimerAndStoppingEarlyRecordsDone() {
        compose.onNodeWithText("その他",useUnmergedTree=true).performClick();compose.onNodeWithText("集中タイマー").performClick()
        compose.onNodeWithText("勉強を始める").performScrollTo().performClick()
        compose.waitUntil(15_000) { app.repository.data.value.timer!=null }
        compose.onNodeWithText("終了して記録").performScrollTo().performClick();compose.onAllNodesWithText("終了して記録").onLast().performClick()
        compose.waitUntil(15_000) { app.repository.data.value.timer==null }
        val record=app.repository.data.value.schedules.single();assertEquals("done",record.status);assertTrue(record.actual<25.0/60)
    }
    @Test fun compactRowsKeepTheOverallProgressVisibleDuringScrollingAndFiltering() {
        val items=(0 until 20).map { index -> ChecklistItem("dense-$index","問題集 p.${20+index*2}〜${21+index*2}",if(index<8) "done" else if(index<11) "review" else "pending") }
        runBlocking { app.repository.update { it.copy(exams=it.exams.map { exam -> if(exam.id=="e1") exam.copy(checklist=items) else exam }) } }
        compose.onNodeWithText("試験",useUnmergedTree=true).performClick()
        compose.onAllNodesWithText("教科別の日程 1件").onFirst().performScrollTo().performClick()
        compose.onNodeWithText("範囲チェックリスト · 8 / 20件 完了").performScrollTo().performClick()
        compose.onNodeWithText("全20件 · 完了8件 · 未完了12件 · 要復習3件").assertIsDisplayed()
        compose.onAllNodesWithText("範囲の名前").assertCountEquals(0)
        val list=compose.onNodeWithTag("checklist-list").fetchSemanticsNode().boundsInRoot
        val fullyVisible=(0 until 20).count { index -> compose.onAllNodesWithTag("checklist-item-dense-$index").fetchSemanticsNodes().firstOrNull()?.boundsInRoot?.let { it.top>=list.top && it.bottom<=list.bottom }==true }
        assertTrue("only $fullyVisible rows fit",fullyVisible>=6)
        val overview=compose.onNodeWithTag("checklist-overview").fetchSemanticsNode().boundsInRoot
        val bitmap=compose.runOnIdle { val view=android.view.inspector.WindowInspector.getGlobalWindowViews().last();android.graphics.Bitmap.createBitmap(view.width,view.height,android.graphics.Bitmap.Config.ARGB_8888).also { view.draw(android.graphics.Canvas(it)) } }
        val file=File(requireNotNull(System.getProperty("studycopi.artifacts")),"checklist-compact-390.png");file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
        compose.onNodeWithTag("checklist-list").performScrollToNode(hasTestTag("check-dense-19"))
        assertEquals(overview.top,compose.onNodeWithTag("checklist-overview").fetchSemanticsNode().boundsInRoot.top,.1f)
        compose.onNodeWithTag("checklist-filter-remaining").performClick()
        compose.onNodeWithTag("check-dense-0").assertDoesNotExist()
        compose.onNodeWithTag("checklist-filter-review").performClick()
        compose.onNodeWithTag("check-dense-8").assertIsDisplayed()
        compose.onNodeWithTag("check-dense-10").assertIsDisplayed()
        compose.onNodeWithText("全20件 · 完了8件 · 未完了12件 · 要復習3件").assertIsDisplayed()
    }
}

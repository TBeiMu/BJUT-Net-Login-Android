package io.github.tbeimu.gongxiaozhu

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import io.github.tbeimu.gongxiaozhu.network.*
import io.github.tbeimu.gongxiaozhu.schedule.*
import io.github.tbeimu.gongxiaozhu.ui.*
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlinx.coroutines.cancel
import androidx.lifecycle.Lifecycle

@OptIn(ExperimentalCoroutinesApi::class)
class ScheduleInteractionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val monday = LocalDate.of(2026,11,2)
    private fun state(lastLesson: Int = 12): ScheduleUiState {
        val identity=ScheduleIdentity("fake-ui-student")
        val weeks=(1..3).map { n ->
            val dates=(0L..6L).map { monday.plusWeeks(n-1L).plusDays(it).toString() }
            ScheduleWeek(n,dates,listOf(ScheduleCourse("late","晚间课程",dates[1],listOf(lastLesson))))
        }
        return ScheduleUiState(ScheduleData(identity=identity,complete=SemesterSchedule(identity,ScheduleTerm("2026-2027","1",3,1),weeks,1)),loading=false)
    }
    @Test fun twelveLessonsFitOnOnePageAndEverySwipeChangesWeek() {
        compose.setContent { GongXiaoZhuTheme { ScheduleScreen(state(),Modifier.height(350.dp),{},{},{},{},todayProvider={monday}) } }
        compose.onNodeWithTag("lesson_1").assertIsDisplayed()
        compose.onNodeWithTag("lesson_12").assertIsDisplayed()
        compose.onNodeWithTag("course_1_1_12").assertIsDisplayed()
        compose.onNodeWithTag("week_grid_1").performTouchInput { swipeUp() }
        compose.onNodeWithTag("schedule_week_title").assertTextContains("第 2 周")
        compose.onNodeWithTag("week_grid_2").performTouchInput { swipeDown() }
        compose.onNodeWithTag("schedule_week_title").assertTextContains("第 1 周")
        compose.onNodeWithTag("week_grid_1").performTouchInput { swipeDown() }
        compose.onNodeWithTag("schedule_week_title").assertTextContains("第 1 周")
    }
    @Test fun twelveSlotsRemainVisibleWhenAllCoursesEndBeforeNoon() {
        compose.setContent { GongXiaoZhuTheme { ScheduleScreen(state(4),Modifier.height(350.dp),{},{},{},{},todayProvider={monday}) } }
        compose.onNodeWithTag("lesson_12").assertIsDisplayed()
    }
    @Test fun cleanScheduleHidesNormalMessagesButRetainsActionableStatus() {
        val displayed=mutableStateOf(state(2).copy(message="整学期导入完成",notice=ScheduleNotice.SUCCESS))
        compose.setContent { GongXiaoZhuTheme { ScheduleScreen(displayed.value,Modifier,{},{},{},{},todayProvider={monday}) } }
        compose.onNodeWithTag("schedule_status").assertDoesNotExist()
        compose.onNodeWithText("整学期导入完成").assertDoesNotExist()
        compose.onNodeWithText("课表网络",substring=true).assertDoesNotExist()
        compose.runOnUiThread { displayed.value=displayed.value.copy(notice=ScheduleNotice.ERROR,message="虚构查询失败") }
        compose.onNodeWithTag("schedule_status").assertExists()
        compose.onNodeWithText("重试").assertExists()
        compose.runOnUiThread { displayed.value=displayed.value.copy(notice=ScheduleNotice.IDLE) }
        compose.onNodeWithTag("schedule_status").assertDoesNotExist()
    }
    @Test fun titleShowsConnectingSuccessThenTheSelectedWeekAndDoesNotReplay() {
        val testScope=TestScope()
        val notice=CampusConnectionNotice(testScope.backgroundScope)
        val show=mutableStateOf(true)
        val running=LoginUiState(running=true,wifiConnected=true,taskId="fake-task")
        val success=running.copy(running=false,completed=true)
        try {
            compose.setContent { GongXiaoZhuTheme {
                if(show.value) ScheduleScreen(state(),Modifier,{},{},{},{},todayProvider={monday},connectionNotice=notice)
            } }
            compose.runOnUiThread { notice.update(running) }
            compose.onNodeWithTag("schedule_week_title").assertTextContains("正在登录校园网")
            compose.onNodeWithTag("lesson_12").assertIsDisplayed()
            compose.onNodeWithTag("week_grid_1").performTouchInput { swipeUp() }
            compose.runOnUiThread { notice.update(success) };testScope.runCurrent()
            compose.onNodeWithTag("schedule_week_title").assertTextContains("校园网已连接")
            testScope.advanceTimeBy(2000);testScope.runCurrent();compose.waitForIdle()
            compose.onNodeWithTag("schedule_week_title").assertTextContains("第 2 周")
            compose.onNodeWithTag("campus_connection_title", useUnmergedTree = true).assertDoesNotExist()
            compose.runOnUiThread { show.value=false };compose.waitForIdle()
            compose.runOnUiThread { show.value=true;notice.update(success) }
            compose.onNodeWithTag("campus_connection_title", useUnmergedTree = true).assertDoesNotExist()
        } finally { testScope.backgroundScope.cancel() }
    }
    @Test fun fastSuccessBeforeCompositionAndObscuredPageIsNotLost() {
        val testScope=TestScope()
        val notice=CampusConnectionNotice(testScope.backgroundScope)
        val unobstructed=mutableStateOf(false)
        val success=LoginUiState(completed=true,wifiConnected=true,taskId="fast-task")
        notice.update(success)
        try {
            compose.setContent { GongXiaoZhuTheme {
                ScheduleScreen(state(),Modifier,{},{},{},{},todayProvider={monday},connectionNotice=notice,titleUnobstructed=unobstructed.value)
            } }
            testScope.advanceTimeBy(5000);testScope.runCurrent()
            compose.onNodeWithTag("schedule_week_title").assertTextContains("第 1 周")
            compose.runOnUiThread { unobstructed.value=true };compose.waitForIdle();testScope.runCurrent()
            compose.onNodeWithTag("schedule_week_title").assertTextContains("校园网已连接")
            testScope.advanceTimeBy(2000);testScope.runCurrent();compose.waitForIdle()
            compose.onNodeWithTag("schedule_week_title").assertTextContains("第 1 周")
        } finally { testScope.backgroundScope.cancel() }
    }

    @Test fun importCancelConfirmationDoesNotConsumeCampusSuccessBeforeDismissal() {
        val testScope = TestScope()
        val notice = CampusConnectionNotice(testScope.backgroundScope)
        val paused = state().let { original -> original.copy(notice = ScheduleNotice.PAUSED,
            data = original.data.copy(pending = PendingSchedule(original.data.identity!!,
                original.data.complete!!.term, listOf(original.data.complete.weeks.first())))) }
        val running = LoginUiState(running = true, wifiConnected = true, taskId = "fake-dialog-task")
        try {
            compose.setContent { GongXiaoZhuTheme {
                ScheduleScreen(paused, Modifier, {}, {}, {}, {}, todayProvider = { monday }, connectionNotice = notice)
            } }
            compose.runOnUiThread { notice.update(running) }
            compose.onNodeWithTag("cancel_schedule_import").performClick()
            compose.onNodeWithText("取消本次课表导入？").assertIsDisplayed()
            compose.runOnUiThread { notice.update(running.copy(running = false, completed = true)) }
            testScope.runCurrent(); testScope.advanceTimeBy(5000); testScope.runCurrent()
            compose.onNodeWithText("保留进度").performClick(); compose.waitForIdle(); testScope.runCurrent()
            compose.onNodeWithTag("schedule_week_title").assertTextContains("校园网已连接")
            testScope.advanceTimeBy(2000); testScope.runCurrent(); compose.waitForIdle()
            compose.onNodeWithTag("schedule_week_title").assertTextContains("第 1 周")
        } finally { testScope.backgroundScope.cancel() }
    }

    @Test fun slowResultsUseOneSecondWarningAndFailureWithoutMovingGridAndSlowSuccessIsVisible() {
        val testScope = TestScope()
        val notice = CampusConnectionNotice(testScope.backgroundScope)
        val running = LoginUiState(running = true, wifiConnected = true, taskId = "fake-slow-task")
        try {
            compose.setContent { GongXiaoZhuTheme {
                ScheduleScreen(state(), Modifier, {}, {}, {}, {}, todayProvider = { monday }, connectionNotice = notice)
            } }
            val original = compose.onNodeWithTag("week_grid_1").fetchSemanticsNode().boundsInRoot
            compose.runOnUiThread { notice.update(running) }; testScope.runCurrent()
            testScope.advanceTimeBy(1499); testScope.runCurrent(); compose.waitForIdle()
            compose.onNodeWithTag("schedule_week_title").assertTextContains("正在登录校园网")
            testScope.advanceTimeBy(1); testScope.runCurrent(); compose.waitForIdle()
            compose.onNodeWithTag("schedule_week_title").assertTextContains("登录超时")
            assertEquals(original, compose.onNodeWithTag("week_grid_1").fetchSemanticsNode().boundsInRoot)
            testScope.advanceTimeBy(2000); testScope.runCurrent(); compose.waitForIdle()
            compose.onNodeWithTag("schedule_week_title").assertTextContains("第 1 周")
            compose.runOnUiThread { notice.update(running.copy(running = false, completed = true)) }
            testScope.runCurrent(); compose.onNodeWithTag("schedule_week_title").assertTextContains("校园网已连接")
            compose.runOnUiThread { notice.update(running.copy(taskId = "fake-failure-task")) }
            testScope.runCurrent(); testScope.advanceTimeBy(1500); testScope.runCurrent(); compose.waitForIdle()
            compose.runOnUiThread { notice.update(running.copy(taskId = "fake-failure-task", running = false,
                outcome = LoginTaskOutcome.FAILED)) }
            testScope.runCurrent(); compose.onNodeWithTag("schedule_week_title").assertTextContains("登录超时")
            assertEquals(original, compose.onNodeWithTag("week_grid_1").fetchSemanticsNode().boundsInRoot)
            testScope.advanceTimeBy(999); testScope.runCurrent(); compose.waitForIdle()
            compose.onNodeWithTag("schedule_week_title").assertTextContains("登录超时")
            testScope.advanceTimeBy(1); testScope.runCurrent(); compose.waitForIdle()
            compose.onNodeWithTag("schedule_week_title").assertTextContains("校园网登录失败")
            testScope.advanceTimeBy(1000); testScope.runCurrent(); compose.waitForIdle()
            compose.onNodeWithTag("schedule_week_title").assertTextContains("第 1 周")
        } finally { testScope.backgroundScope.cancel() }
    }

    @Test fun weekdayAndLessonLabelsStayFixedDuringWeekAnimationWhileDatesAndCoursesChange() {
        compose.setContent { GongXiaoZhuTheme {
            ScheduleScreen(state(), Modifier, {}, {}, {}, {}, todayProvider = { monday })
        } }
        val tags = (0..6).map { "weekday_$it" } + (1..12).flatMap { listOf("lesson_$it", "lesson_time_$it") }
        val original = tags.associateWith { tag -> compose.onNodeWithTag(tag).fetchSemanticsNode().let {
            it.id to it.boundsInRoot.center
        } }
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("week_grid_1").performTouchInput { swipeUp() }
            repeat(4) {
                compose.mainClock.advanceTimeBy(48)
                compose.waitForIdle()
                tags.forEach { tag ->
                    val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
                    assertEquals("Fixed label must not be duplicated or recreated: $tag", original.getValue(tag).first, node.id)
                    assertEquals("Fixed label must not slide: $tag", original.getValue(tag).second, node.boundsInRoot.center)
                }
                if (it == 1) screenshot("mid-animation")
            }
            compose.mainClock.advanceTimeBy(300); compose.waitForIdle()
            compose.onNodeWithTag("date_2_0").assertTextEquals("11/9")
            compose.onNodeWithTag("course_2_1_12").assertIsDisplayed()
            compose.onNodeWithTag("course_1_1_12").assertDoesNotExist()
            screenshot("next-week")
            compose.onNodeWithTag("week_grid_2").performTouchInput { swipeDown() }
            compose.mainClock.advanceTimeBy(300); compose.waitForIdle()
            compose.onNodeWithTag("date_1_0").assertTextEquals("11/2")
            tags.forEach { tag -> assertEquals(original.getValue(tag).first, compose.onNodeWithTag(tag).fetchSemanticsNode().id) }
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun bjutTimesRemainVisibleWithoutOverflowOnSmallScreenWithLargeFont() {
        compose.setContent { GongXiaoZhuTheme {
            val density = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides
                androidx.compose.ui.unit.Density(density.density, 2f)) {
                ScheduleScreen(state(), Modifier.height(350.dp), {}, {}, {}, {}, todayProvider = { monday })
            }
        } }
        val expected = listOf("08:00\n08:45", "08:50\n09:35", "09:55\n10:40", "10:45\n11:30",
            "13:30\n14:15", "14:20\n15:05", "15:25\n16:10", "16:15\n17:00",
            "18:00\n18:45", "18:50\n19:35", "19:55\n20:40", "20:45\n21:30")
        screenshot("small-font-time")
        expected.forEachIndexed { index, value ->
            val node = compose.onNodeWithTag("lesson_time_${index+1}")
            node.assertTextEquals(value).assertIsDisplayed()
            node.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { get ->
                val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                assertTrue(get(results))
                val layout = results.single()
                assertFalse("Time ${index+1}: size=${layout.size}, lines=${layout.lineCount}, paragraph=${layout.multiParagraph.height}, width=${layout.multiParagraph.width}", layout.hasVisualOverflow)
            }
        }
        compose.onNodeWithTag("week_grid_1").performTouchInput { swipeUp() }
        compose.onNodeWithTag("lesson_time_1").assertTextEquals("08:00\n08:45")
        compose.onNodeWithTag("lesson_time_12").assertTextEquals("20:45\n21:30")
    }

    @Test fun laterSchoolLessonsKeepTheirNumberWithoutInventingTimes() {
        compose.setContent { GongXiaoZhuTheme {
            ScheduleScreen(state(13), Modifier, {}, {}, {}, {}, todayProvider = { monday })
        } }
        compose.onNodeWithTag("lesson_13").assertIsDisplayed()
        compose.onNodeWithTag("lesson_time_13").assertDoesNotExist()
        compose.onNodeWithTag("course_1_1_13").assertIsDisplayed()
        compose.onNodeWithTag("lesson_time_12").assertTextEquals("20:45\n21:30")
    }

    private fun screenshot(name: String) {
        val inst = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val bitmap = inst.uiAutomation.takeScreenshot() ?: return
        val file = java.io.File(inst.targetContext.getExternalFilesDir(null), "fixed-week-$name.png")
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        android.os.ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand(
            "cp ${file.absolutePath} /sdcard/Download/gxz-fixed-week-$name.png")).use { it.readBytes() }
    }
}

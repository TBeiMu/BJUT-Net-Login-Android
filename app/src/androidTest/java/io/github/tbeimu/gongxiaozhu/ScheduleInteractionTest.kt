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
        compose.onNodeWithTag("lesson_1_1").assertIsDisplayed()
        compose.onNodeWithTag("lesson_1_12").assertIsDisplayed()
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
        compose.onNodeWithTag("lesson_1_12").assertIsDisplayed()
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
            compose.onNodeWithTag("lesson_1_12").assertIsDisplayed()
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
}

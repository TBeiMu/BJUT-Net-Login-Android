package io.github.tbeimu.gongxiaozhu

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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
        compose.onNodeWithTag("week_grid_1").performTouchInput { swipeDown() }
        compose.onNodeWithTag("schedule_week_title").assertTextContains("第 2 周")
        compose.onNodeWithTag("week_grid_2").performTouchInput { swipeUp() }
        compose.onNodeWithTag("schedule_week_title").assertTextContains("第 1 周")
        compose.onNodeWithTag("week_grid_1").performTouchInput { swipeUp() }
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
    @Test fun successBannerExpiresAndCannotReplayWhenDestinationReturns() {
        val notice=CampusConnectionNotice()
        val login=mutableStateOf(LoginUiState(completed=true,wifiConnected=true,taskId="fake-task"))
        val show=mutableStateOf(true)
        compose.setContent { GongXiaoZhuTheme { Box(Modifier.fillMaxSize()) {
            if(show.value)CampusConnectedBanner(notice,login.value,true)
        } } }
        compose.runOnUiThread { notice.update(login.value) }
        compose.onNodeWithTag("campus_connected_banner").assertExists()
        compose.mainClock.advanceTimeBy(2300)
        compose.onNodeWithTag("campus_connected_banner").assertDoesNotExist()
        compose.runOnUiThread { show.value=false };compose.waitForIdle()
        compose.runOnUiThread { show.value=true;notice.update(login.value) }
        compose.onNodeWithTag("campus_connected_banner").assertDoesNotExist()
        compose.runOnUiThread { login.value=login.value.copy(taskId="next-task");notice.update(login.value) }
        compose.onNodeWithTag("campus_connected_banner").assertExists()
        compose.runOnUiThread { login.value=LoginUiState();notice.update(login.value) }
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithTag("campus_connected_banner").assertDoesNotExist()
    }
}

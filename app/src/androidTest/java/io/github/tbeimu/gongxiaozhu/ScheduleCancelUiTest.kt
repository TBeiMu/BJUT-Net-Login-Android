package io.github.tbeimu.gongxiaozhu

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tbeimu.gongxiaozhu.data.*
import io.github.tbeimu.gongxiaozhu.schedule.*
import io.github.tbeimu.gongxiaozhu.ui.AppViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import java.time.LocalDate

class ScheduleCancelUiTest {
    private val compose=createAndroidComposeRule<MainActivity>()
    private lateinit var previous:SemesterSchedule
    private val fixture=object:ExternalResource(){
        override fun before(){
            val context=InstrumentationRegistry.getInstrumentation().targetContext
            check(context.packageName.endsWith(".debug"))
            createAccountRepository(context).apply{reset();load();settings(AppSettings(autoLogin=false))}
            val term=ScheduleTerm("2026-2027","1",2,1)
            val identity=ScheduleIdentity("fake-cancel","测试同学")
            fun week(n:Int)=ScheduleWeek(n,(0L..6L).map { LocalDate.of(2026,11,2).plusWeeks(n-1L).plusDays(it).toString() },emptyList())
            createScheduleRepository(context).apply {
                clear();begin(identity,term);append(week(1));append(week(2));previous=finish(1).complete!!
                begin(identity,term);append(week(1))
            }
        }
        override fun after(){
            val context=InstrumentationRegistry.getInstrumentation().targetContext
            createAccountRepository(context).reset();createScheduleRepository(context).clear()
        }
    }
    @get:Rule val rules:RuleChain=RuleChain.outerRule(fixture).around(compose)

    private fun model()=ViewModelProvider(compose.activity).get(AppViewModel::class.java)
    private fun ready(){compose.waitUntil(5000){!model().state.value.schedule.loading}}
    private fun assertCancelled(){
        compose.waitUntil(5000){!model().state.value.schedule.busy && model().state.value.schedule.data.pending==null}
        assertEquals(previous,model().state.value.schedule.data.complete)
        assertEquals(previous,createScheduleRepository(compose.activity).load().complete)
        assertEquals(ScheduleNotice.IDLE,model().state.value.schedule.notice)
    }
    @Test fun pausedImportCanKeepProgressOrCancelFromSchedule(){
        ready()
        compose.onNodeWithTag("cancel_schedule_import").assertIsDisplayed().performClick()
        compose.onNodeWithText("保留进度").performClick()
        assertNotNull(model().state.value.schedule.data.pending)
        compose.onNodeWithTag("cancel_schedule_import").performClick()
        compose.onNodeWithTag("confirm_cancel_schedule_import").performClick()
        assertCancelled()
        compose.onNodeWithTag("schedule_status").assertDoesNotExist()
        compose.onNodeWithTag("schedule_pager").assertExists()
        compose.activityRule.scenario.recreate()
        compose.waitUntil(5000){!model().state.value.schedule.loading}
        assertNull(model().state.value.schedule.data.pending)
        compose.onNodeWithTag("cancel_schedule_import").assertDoesNotExist()
    }
    @Test fun failedImportCanBeCancelledFromManagementWithoutDeletingCompleteSchedule(){
        ready()
        compose.runOnUiThread { model().scheduleController.message("虚构重试错误") }
        compose.onNodeWithText("我的",substring=false).performClick()
        compose.onNodeWithTag("my_SCHEDULE").performClick()
        compose.onNodeWithTag("cancel_schedule_import").performScrollTo().performClick()
        compose.onNodeWithTag("confirm_cancel_schedule_import").performClick()
        assertCancelled()
        compose.onNodeWithTag("cancel_schedule_import").assertDoesNotExist()
    }
}

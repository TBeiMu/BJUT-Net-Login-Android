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

class CourseEditorAppTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val fixture = object : ExternalResource() {
        override fun before() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            check(context.packageName.endsWith(".debug"))
            createAccountRepository(context).apply { reset(); load(); settings(AppSettings(autoLogin = false)) }
            createScheduleRepository(context).apply { clear(); load() }
            reloadScheduleFixture(context)
        }
        override fun after() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            createScheduleRepository(context).clear(); createAccountRepository(context).reset()
        }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(fixture).around(compose)
    private fun model() = ViewModelProvider(compose.activity).get(AppViewModel::class.java)
    @Test fun draftSurvivesActualRotationAndLocalCalendarIsManagedAfterSave() {
        compose.waitUntil(5000) { !model().state.value.schedule.loading }
        compose.onNodeWithTag("import_schedule").performClick()
        compose.onNodeWithTag("add_custom_course").performClick()
        compose.onNodeWithTag("course_name").performTextInput("本机测试课程")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("course_name").assertTextContains("本机测试课程")
        compose.onNodeWithTag("save_course").performClick()
        compose.waitUntil(5000) { model().state.value.courseEditor == null && !model().state.value.courseSaving }
        val data = createScheduleRepository(compose.activity).load()
        assertNull(data.identity); assertEquals("本机测试课程", data.localTerms.single().courses.single().name)
        compose.onNodeWithText("我的", substring = false).performClick()
        compose.onNodeWithTag("my_SCHEDULE").performClick()
        compose.onNodeWithText("新建本地学期").performScrollTo().assertExists()
        compose.onNodeWithText("本机测试课程").performScrollTo().assertExists()
        compose.onNodeWithText("查看课表").performScrollTo().performClick()
        compose.waitUntil(5000) { !model().state.value.schedule.busy }
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("课表", substring = false).performClick()
        compose.onNodeWithTag("schedule_mode").assertExists()
    }
}

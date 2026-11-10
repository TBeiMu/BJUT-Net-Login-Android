package io.github.tbeimu.gongxiaozhu

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tbeimu.gongxiaozhu.data.*
import io.github.tbeimu.gongxiaozhu.schedule.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.time.DayOfWeek
import java.io.File

class ScheduleUiTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val fixture = object : ExternalResource() {
        override fun before() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            check(context.packageName.endsWith(".debug"))
            createAccountRepository(context).apply {
                reset(); load(); save(AccountDraft(label = "测试校园网", username = "fake-campus-user", password = "fake-only"))
                settings(AppSettings(autoLogin = false))
            }
            val monday = LocalDate.now(ZoneId.of("Asia/Shanghai")).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            createScheduleRepository(context).apply {
                clear(); begin(ScheduleIdentity("fake-graduate", "示例同学"), ScheduleTerm("2026-2027", "1", 2, 2))
                for (week in 1..2) {
                    val dates = (0L..6L).map { monday.plusWeeks(week - 2L).plusDays(it).toString() }
                    val names = listOf("高级人工智能", "科研方法", "学术英语", "算法分析", "数据科学")
                    val courses = names.mapIndexed { day, name -> ScheduleCourse("course-$day", name, dates[day], listOf(1, 2), "一教 ${day + 2}01", "示例教师") } +
                        listOf(ScheduleCourse("course-6", "学术写作", dates[1], listOf(5, 6), "二教 308", "示例教师"),
                            ScheduleCourse("course-7", "文献研讨", dates[3], listOf(8, 9, 10), "线上", "示例教师"),
                            ScheduleCourse("course-8", "项目讨论", dates[0], listOf(2, 3), "实验室", "示例教师"))
                    append(ScheduleWeek(week, dates, if (week == 1) emptyList() else courses))
                }
                finish(System.currentTimeMillis())
            }
        }
        override fun after() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            createScheduleRepository(context).clear(); createAccountRepository(context).reset()
        }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(fixture).around(compose)

    @Test fun nativeWeekTableAndMyPageKeepSeparateAccounts() {
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("schedule_pager")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("第 2 周 ▾").assertExists()
        screenshot("schedule-preview.png")
        compose.onNodeWithText("2 门重叠").performClick()
        compose.onNodeWithText("重叠课程 · 2 门").assertExists()
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithText("第 2 周 ▾").performClick()
        compose.onNodeWithText("第 1 周", substring = false).performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("本周无课 · 学校已返回空课表")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("schedule_pager").performTouchInput { swipeLeft() }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("第 2 周 ▾")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("本周", substring = false).performClick()
        compose.onNodeWithText("我的", substring = false).performClick()
        compose.onNodeWithText("你好，示例同学").assertExists()
        compose.onNodeWithText("测试校园网").assertExists()
        screenshot("my-preview.png")
        compose.onNodeWithText("更换 / 解绑课表账号").performScrollTo().performClick()
        compose.onNodeWithText("取消", substring = false).performClick()
        compose.onNodeWithText("资费", substring = false).performClick()
        compose.onNodeWithText("校园网账号：测试校园网 · fa••••er").assertExists()
    }

    private fun screenshot(name: String) {
        val inst = InstrumentationRegistry.getInstrumentation()
        inst.waitForIdleSync()
        val bitmap = inst.uiAutomation.takeScreenshot() ?: return
        File(inst.targetContext.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}

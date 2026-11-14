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
            if (InstrumentationRegistry.getArguments().getString("preview_wallpaper") == "true") {
                val bitmap = android.graphics.Bitmap.createBitmap(240, 480, android.graphics.Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bitmap)
                val paint = android.graphics.Paint().apply {
                    shader = android.graphics.LinearGradient(0f, 0f, 240f, 480f,
                        intArrayOf(0xFF12516D.toInt(), 0xFFDDD3AD.toInt(), 0xFF366477.toInt()), null, android.graphics.Shader.TileMode.CLAMP)
                }
                canvas.drawRect(0f, 0f, 240f, 480f, paint)
                File(context.noBackupFilesDir, "wallpaper.jpg").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
                bitmap.recycle()
            }
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
            reloadScheduleFixture(context)
        }
        override fun after() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            createScheduleRepository(context).clear(); createAccountRepository(context).reset()
            if (InstrumentationRegistry.getArguments().getString("preview_wallpaper") == "true") File(context.noBackupFilesDir, "wallpaper.jpg").delete()
        }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(fixture).around(compose)

    @Test fun nativeWeekTableAndMyPageKeepSeparateAccounts() {
        // The tab opens on today; the week table is one tap away and stays chosen across tabs and recreation.
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("schedule_mode")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("schedule_mode").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("schedule_pager")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("第 2 周").assertExists()
        screenshot("schedule-preview.png")
        compose.onNodeWithText("重叠课").performClick()
        compose.onNodeWithTag("course_detail_0").assertExists()
        compose.onNodeWithTag("course_detail_1").assertExists()
        compose.onNodeWithText("课程详情").assertDoesNotExist()
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithText("第 2 周").performClick()
        compose.onNodeWithTag("week_choice_1").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("本周无课")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("我的", substring = false).performClick()
        compose.onNodeWithTag("my_SCHEDULE").performClick()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("课表", substring = false).performClick()
        compose.onNodeWithTag("schedule_week_title").assertTextContains("第 1 周")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("schedule_week_title").assertTextContains("第 1 周")
        compose.onNodeWithText("回到本周").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("第 2 周")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("schedule_status").assertDoesNotExist()
        compose.onNodeWithText("我的", substring = false).performClick()
        compose.onNodeWithText("示例同学").assertExists()
        compose.onNodeWithTag("my_ACCOUNTS").assertExists()
        screenshot("my-preview.png")
        compose.onNodeWithTag("my_SCHEDULE").performClick()
        compose.onNodeWithText("更换 / 解绑课表账号").performScrollTo().performClick()
        compose.onNodeWithText("取消", substring = false).performClick()
        compose.onNodeWithText("资费", substring = false).performClick()
        compose.onNodeWithTag("billing_account").assertTextEquals("测试校园网 · fa••••er")
    }

    private fun screenshot(name: String) {
        val inst = InstrumentationRegistry.getInstrumentation()
        inst.waitForIdleSync()
        val bitmap = inst.uiAutomation.takeScreenshot() ?: return
        val file = File(inst.targetContext.getExternalFilesDir(null), name)
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        android.os.ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand("cp ${file.absolutePath} /sdcard/Download/gxz-v042-$name")).use { it.readBytes() }
    }
}

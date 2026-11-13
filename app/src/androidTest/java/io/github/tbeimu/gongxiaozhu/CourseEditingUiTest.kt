package io.github.tbeimu.gongxiaozhu

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tbeimu.gongxiaozhu.schedule.*
import io.github.tbeimu.gongxiaozhu.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

/** The real UI and Keystore, with fictional courses and no school transport. */
class CourseEditingUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val monday = LocalDate.of(2026, 11, 9)
    private val identity = ScheduleIdentity("fictional-course-editor")
    private fun schoolData() = ScheduleData(identity = identity, complete = SemesterSchedule(identity,
        ScheduleTerm("2026-2027", "1", 2, 1), (1..2).map { w ->
            val dates = (0L..6L).map { monday.plusWeeks(w - 1L).plusDays(it).toString() }
            ScheduleWeek(w, dates, listOf(ScheduleCourse("fictional-course", "测试课程", dates[0], listOf(1, 2))))
        }, 1))
    @Test fun plusOpensOnlyMenuAndGraduateOptionUsesExistingCallback() {
        var requests = 0
        var addedWeek = 0
        var blocked = false
        compose.setContent { GongXiaoZhuTheme {
            ScheduleHome(ScheduleUiState(data = schoolData(), loading = false), Modifier, { requests++ }, {}, {}, {},
                todayProvider = { monday }, onDialogVisibilityChanged = { blocked = it },
                onAddCourse = { week, _ -> addedWeek = week })
        } }
        compose.onNodeWithTag("import_schedule").performClick()
        compose.onNodeWithTag("import_undergraduate").assertIsNotEnabled()
        assertEquals(0, requests); assertTrue(blocked)
        val anchor = compose.onNodeWithTag("import_schedule").fetchSemanticsNode()
        val menu = compose.onNodeWithTag("schedule_add_menu").fetchSemanticsNode()
        assertTrue("菜单应位于添加按钮下方", menu.layoutInfo.coordinates.localToScreen(Offset.Zero).y >=
            anchor.layoutInfo.coordinates.localToScreen(Offset.Zero).y + anchor.size.height - 2f)
        capture("schedule_add_menu", "add-menu")
        compose.onNodeWithTag("import_graduate").performClick()
        assertEquals(1, requests)
        compose.onNodeWithTag("schedule_add_menu").assertDoesNotExist()
        compose.onNodeWithTag("schedule_mode").performClick()
        compose.onNodeWithTag("import_schedule").performClick()
        compose.onNodeWithTag("add_custom_course").performClick()
        assertEquals(1, addedWeek); assertEquals(1, requests)
    }
    @Test fun emptyScreenMenuCanCreateLocalCourseWithoutSchoolIdentityAndRestoreDraft() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = createScheduleRepository(context)
        repository.clear(); repository.load()
        val data = mutableStateOf(repository.snapshot())
        val editor = mutableStateOf<CourseEditContext?>(null)
        val restoration = StateRestorationTester(compose)
        try {
            restoration.setContent { GongXiaoZhuTheme {
                ScheduleHome(ScheduleUiState(data = data.value, loading = false), Modifier, { error("No school request") }, {}, {}, {},
                    todayProvider = { monday }, titleUnobstructed = editor.value == null,
                    onAddCourse = { week, day -> editor.value = data.value.courseEditor(week = week, weekday = day) })
                editor.value?.let { edit -> CourseEditor(edit, false, null, { editor.value = null }, {
                    data.value = repository.edit(edit, it); editor.value = null
                }) }
            } }
            compose.onNodeWithText("添加课表", substring = false).performClick()
            compose.onNodeWithTag("add_custom_course").performClick()
            compose.onNodeWithTag("save_course").assertIsNotEnabled()
            compose.onNodeWithTag("course_name").performTextInput("离线实验")
            compose.onNodeWithTag("course_location").performTextInput("测试教室")
            restoration.emulateSavedInstanceStateRestore()
            compose.onNodeWithTag("course_name").assertTextContains("离线实验")
            compose.onNodeWithTag("course_location").assertTextContains("测试教室")
            compose.onNodeWithTag("term_weeks").performScrollTo().performTextReplacement("61")
            compose.onNodeWithTag("save_course").assertIsNotEnabled()
            compose.onNodeWithTag("term_weeks").performTextReplacement("20")
            capture("course_editor", "editor")
            compose.onNodeWithTag("save_course").performClick()
            assertNull(repository.load().identity)
            assertEquals("离线实验", repository.snapshot().localTerms.single().courses.single().name)
            compose.onNodeWithTag("schedule_mode").assertExists().performClick()
            compose.onNodeWithTag("schedule_pager").assertExists()
        } finally { repository.clear() }
    }
    @Test fun detailsAllowEditingSchoolCourseAndDeletingSelectedOccurrence() {
        val data = mutableStateOf(schoolData())
        val editor = mutableStateOf<CourseEditContext?>(null)
        compose.setContent { GongXiaoZhuTheme {
            ScheduleHome(ScheduleUiState(data = data.value, loading = false), Modifier, {}, {}, {}, {}, todayProvider = { monday },
                titleUnobstructed = editor.value == null,
                onEditCourse = { course, delete -> editor.value = data.value.courseEditor(course, deleteOnly = delete) })
            editor.value?.let { edit -> CourseEditor(edit, false, null, { editor.value = null }, {
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                val repo = createScheduleRepository(context)
                repo.clear(); repo.load(); repo.begin(identity, data.value.complete!!.term)
                data.value.complete!!.weeks.forEach(repo::append); repo.finish(1)
                val current = repo.snapshot().courseEditor(edit.course, deleteOnly = edit.deleteOnly)
                data.value = repo.edit(current, it); editor.value = null; repo.clear()
            }) }
        } }
        compose.onNodeWithTag("agenda_course_0_1").performClick()
        compose.onNodeWithText("编辑", substring = false).performClick()
        compose.onNodeWithTag("course_name").performTextReplacement("临时调课")
        compose.onNodeWithTag("course_day_3").performScrollTo().performClick()
        compose.onNodeWithTag("save_course").performClick()
        assertEquals("临时调课", data.value.complete!!.weeks[0].courses.single().name)
        assertEquals("测试课程", data.value.complete!!.weeks[1].courses.single().name)
        compose.onNodeWithTag("agenda_day_2").performClick()
        compose.onNodeWithTag("agenda_course_2_1").performClick()
        compose.onNodeWithText("删除", substring = false).performClick()
        compose.onNodeWithTag("save_course").performClick()
        assertTrue(data.value.complete!!.weeks[0].courses.isEmpty())
        assertEquals(1, data.value.complete!!.weeks[1].courses.size)
    }
    @Test fun largeFontEditorKeepsSaveVisibleAndInvalidRangeDisabled() {
        val context = ScheduleData().courseEditor()
        compose.setContent { GongXiaoZhuTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.8f)) {
                Box(Modifier.width(360.dp).height(580.dp)) { CourseEditor(context, false, null, {}, {}) }
            }
        } }
        compose.onNodeWithTag("course_name").performTextInput("大字课程")
        compose.onNodeWithTag("save_course").assertIsDisplayed()
        compose.onNodeWithTag("course_first").performScrollTo().performTextReplacement("20")
        compose.onNodeWithTag("course_last").performTextReplacement("1")
        compose.onNodeWithTag("save_course").assertIsNotEnabled()
        compose.onNodeWithTag("course_last").performTextReplacement("20")
        compose.onNodeWithTag("save_course").assertIsEnabled()
        capture("course_editor", "editor-large")
    }
    private fun capture(tag: String, name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val target = context.getExternalFilesDir(null)!!.resolve("course-$name.png")
        target.outputStream().use { compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

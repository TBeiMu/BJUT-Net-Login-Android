package io.github.tbeimu.gongxiaozhu.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.tbeimu.gongxiaozhu.schedule.*
import io.github.tbeimu.gongxiaozhu.network.CampusConnectionNotice
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
internal fun ScheduleScreen(state: ScheduleUiState, modifier: Modifier, onImport: () -> Unit,
    onPause: () -> Unit, onRetry: () -> Unit, onMine: () -> Unit, todayProvider: () -> LocalDate = ::scheduleToday,
    connectionNotice: CampusConnectionNotice? = null, titleUnobstructed: Boolean = true, onCancelImport: () -> Unit = {}) {
    val today = rememberScheduleDate(todayProvider)
    val schedule = state.data.complete
    var selected by rememberSaveable(schedule?.identity?.studentId, schedule?.term?.key) {
        mutableIntStateOf(schedule?.let { currentScheduleWeek(it, today.value) ?: it.term.currentWeek } ?: 1)
    }
    var showWeeks by rememberSaveable { mutableStateOf(false) }
    var details by remember(schedule) { mutableStateOf<List<ScheduleCourse>?>(null) }
    val weekNumber = selected.coerceIn(1, schedule?.term?.weekCount ?: 1)
    val currentWeek = schedule?.let { currentScheduleWeek(it, today.value) }
    val count = remember(schedule) { schedule?.weeks?.flatMap { it.courses }?.flatMap { it.lessons }?.maxOrNull() ?: 0 }
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onImport, enabled = !state.busy && !state.blocked && !state.loading,
                modifier = Modifier.testTag("import_schedule").semantics { contentDescription = if (schedule == null) "导入课表" else "刷新课表" }) { LineGlyph(AppGlyph.DOWNLOAD) }
            TextButton(onClick = { showWeeks = !showWeeks }, enabled = schedule != null,
                modifier = Modifier.weight(1f).testTag("schedule_week_title")) {
                CampusConnectionTitle(if (schedule == null) "我的课表" else "第 $weekNumber 周", connectionNotice,
                    titleUnobstructed && details == null)
            }
            IconButton(onClick = { showWeeks = !showWeeks }, enabled = schedule != null,
                modifier = Modifier.semantics { contentDescription = "选择周次" }) { LineGlyph(AppGlyph.WEEKS) }
        }
        ScheduleStatus(state, onPause, onImport, onCancelImport)
        when {
            state.blocked -> ScheduleEmpty("课表存储暂不可用", "可以重试读取，或在课表管理中确认清除缓存。", "重试读取课表", onRetry,
                modifier = Modifier.weight(1f), secondary = onMine)
            schedule == null -> ScheduleEmpty(if (state.data.pending == null) "把这一学期放进口袋" else "整学期尚未导入完成",
                "通过 WiFi 或移动数据登录一次，自动导入整个学期。", if (state.data.pending == null) "导入研究生课表" else "登录后继续导入",
                onImport, modifier = Modifier.weight(1f), enabled = !state.busy && !state.loading)
            else -> Row(Modifier.weight(1f).fillMaxWidth().padding(start = 4.dp)) {
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    AnimatedContent(targetState = weekNumber, modifier = Modifier.fillMaxSize().testTag("schedule_pager"),
                        transitionSpec = {
                            val sign = if (targetState > initialState) 1 else -1
                            slideInVertically(tween(220)) { it * sign } togetherWith slideOutVertically(tween(220)) { -it * sign }
                        }, label = "切换周次") { number ->
                        WeekGrid(schedule.weeks[(number - 1).coerceIn(schedule.weeks.indices)], count, today.value, onCourse = { details = it },
                            onWeek = { delta -> selected = (number + delta).coerceIn(1, schedule.term.weekCount) })
                    }
                    if (currentWeek != null && weekNumber != currentWeek) Surface(
                        Modifier.align(Alignment.CenterEnd).padding(bottom = 36.dp), shape = RoundedCornerShape(topStart = 22.dp, bottomStart = 22.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = .94f), shadowElevation = 2.dp) {
                        TextButton(onClick = {
                            today.value = todayProvider()
                            currentScheduleWeek(schedule, today.value)?.let { selected = it }
                        }, modifier = Modifier.testTag("back_current_week")) { Text("回到本周", fontSize = 12.sp) }
                    }
                }
                WeekSelector(schedule, weekNumber, showWeeks, count, onExpand = { showWeeks = true }, onSelect = { selected = it; showWeeks = false })
            }
        }
    }
    details?.let { CourseDetails(it) { details = null } }
}

@Composable
private fun ScheduleStatus(state: ScheduleUiState, onPause: () -> Unit, onImport: () -> Unit, onCancelImport: () -> Unit) {
    if (state.notice !in setOf(ScheduleNotice.PROGRESS, ScheduleNotice.PAUSED, ScheduleNotice.ERROR) || state.page != null || state.blocked) return
    Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp).testTag("schedule_status"),
        shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            if (state.busy && state.total > 0) LinearProgressIndicator(progress = { state.imported.toFloat() / state.total }, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (state.busy && state.total > 0) "正在导入 ${state.imported}/${state.total} 周" else state.message,
                    Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = if (state.busy) onPause else onImport) { Text(if (state.busy) "暂停" else if (state.data.pending != null) "继续" else "重试") }
            }
            CancelScheduleImportButton(state, onCancelImport)
        }
    }
}

@Composable
private fun ScheduleEmpty(title: String, subtitle: String, action: String, onClick: () -> Unit,
    modifier: Modifier, enabled: Boolean = true, secondary: (() -> Unit)? = null) {
    Box(modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                LineGlyph(AppGlyph.CALENDAR, Modifier.size(36.dp), MaterialTheme.colorScheme.primary)
                Text(title, style = MaterialTheme.typography.headlineSmall); Text(subtitle, style = MaterialTheme.typography.bodyMedium)
                Button(onClick = onClick, enabled = enabled) { Text(action) }
                secondary?.let { TextButton(onClick = it) { Text("课表管理") } }
            }
        }
    }
}

/** An upward finger swipe advances one week; downward goes to the previous week. */
internal fun weekDragDirection(delta: Float): Int = when {
    delta < 0 -> 1
    delta > 0 -> -1
    else -> 0
}

@Composable
private fun WeekGrid(week: ScheduleWeek, count: Int, today: LocalDate, onCourse: (List<ScheduleCourse>) -> Unit, onWeek: (Int) -> Unit) {
    val blocks = remember(week) { courseBlocks(week) }
    val density = LocalDensity.current
    val turnThreshold = with(density) { 56.dp.toPx() }
    val latestTurn by rememberUpdatedState(onWeek)
    val gesture = Modifier.pointerInput(week.number) {
        var distance = 0f
        detectVerticalDragGestures(onDragStart = { distance = 0f }, onVerticalDrag = { change, delta ->
            change.consume(); distance += delta
        }, onDragEnd = {
            if (kotlin.math.abs(distance) >= turnThreshold) latestTurn(weekDragDirection(distance))
        }, onDragCancel = { distance = 0f })
    }

    Column(Modifier.fillMaxSize()) {
        ScheduleDates(week, today)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().then(gesture).testTag("week_grid_${week.number}")) {
            run {
                // Twelve slots remain visible even when this semester has no late classes.
                // Unexpected later slots are retained rather than silently dropping school data.
                val rowCount = maxOf(12, count)
                val cell = maxHeight / rowCount
                Row(Modifier.fillMaxSize()) {
                    Column(Modifier.width(38.dp)) { repeat(rowCount) { n ->
                        Box(Modifier.height(cell).fillMaxWidth(), contentAlignment = Alignment.Center) { Text("${n+1}", Modifier.testTag("lesson_${week.number}_${n+1}"), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp) }
                    } }
                    repeat(7) { day -> Box(Modifier.weight(1f).height(cell * rowCount)) {
                        blocks.filter { it.day == day }.forEach { block ->
                            val course = block.courses.first(); val overlap = block.courses.size > 1
                            val blockHeight = cell * (block.last - block.first + 1)
                            val showLocation = blockHeight >= 34.dp * density.fontScale
                            val titleLines = ((blockHeight.value - 8 - (if (showLocation) 16 * density.fontScale else 0f)) / (14 * density.fontScale)).toInt().coerceIn(1, 6)
                            val color = if (overlap) Color(0xFF667B90) else courseColor(course)
                            Column(Modifier.offset(y = cell * (block.first - 1)).padding(1.dp).fillMaxWidth()
                                .height((blockHeight - 2.dp).coerceAtLeast(1.dp)).clip(RoundedCornerShape(5.dp))
                                .background(color.copy(alpha = .92f)).clickable { onCourse(block.courses) }
                                .testTag("course_${week.number}_${block.day}_${block.first}").padding(horizontal = 2.dp, vertical = 3.dp),
                                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(if (overlap) "重叠课" else course.name, color = Color.White, textAlign = TextAlign.Center,
                                    fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp,
                                    maxLines = titleLines, overflow = TextOverflow.Ellipsis)
                                if (showLocation && (overlap || course.location.isNotBlank())) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(if (overlap) "点击查看" else course.location, color = Color.White.copy(alpha = .96f), textAlign = TextAlign.Center,
                                        fontSize = 9.sp, lineHeight = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    } }
                }
                if (week.courses.isEmpty()) Text(if (count == 0) "本学期暂无课程" else "本周无课", Modifier.align(Alignment.Center), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun ScheduleDates(week: ScheduleWeek, today: LocalDate) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Box(Modifier.width(38.dp))
        week.dates.forEachIndexed { day, date ->
            val isToday = date == today.toString()
            Column(Modifier.weight(1f).background(if (isToday) MaterialTheme.colorScheme.primary.copy(alpha = .09f) else Color.Transparent), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.fillMaxWidth().height(3.dp).background(if (isToday) MaterialTheme.colorScheme.primary else Color.Transparent))
                Text("周${listOf("一","二","三","四","五","六","日")[day]}", fontSize = 11.sp, modifier = Modifier.padding(top = 5.dp), fontWeight = if (isToday) FontWeight.SemiBold else FontWeight.Normal)
                Text(LocalDate.parse(date).format(DateTimeFormatter.ofPattern("M/d")), fontSize = 10.sp, modifier = Modifier.padding(bottom = 6.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun WeekSelector(schedule: SemesterSchedule, selected: Int, expanded: Boolean, count: Int, onExpand: () -> Unit, onSelect: (Int) -> Unit) {
    val color = MaterialTheme.colorScheme.primary
    if (!expanded) Canvas(Modifier.width(18.dp).fillMaxHeight().clickable(onClick = onExpand)
        .semantics { contentDescription = "展开周次列表" }.testTag("week_dots")) {
        val span = minOf(size.height * .55f, schedule.term.weekCount * 10.dp.toPx()); val gap = span / schedule.term.weekCount
        val start = (size.height - span) / 2
        repeat(schedule.term.weekCount) { index -> drawCircle(color.copy(alpha = if (index+1 == selected) 1f else .22f),
            if(index+1 == selected) 2.6.dp.toPx() else 1.6.dp.toPx(), Offset(size.width/2, start+gap*(index+.5f))) }
    } else {
        val list = rememberLazyListState()
        LaunchedEffect(selected) { list.scrollToItem((selected-2).coerceAtLeast(0)) }
        LazyColumn(Modifier.width(70.dp).fillMaxHeight().testTag("week_list"), state = list, contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(schedule.weeks, key = { it.number }) { week ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (week.number == selected) color.copy(alpha = .12f) else Color.Transparent)
                    .clickable { onSelect(week.number) }.testTag("week_choice_${week.number}").padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("第 ${week.number} 周", fontSize = 11.sp, color = if(week.number == selected) color else MaterialTheme.colorScheme.onSurface)
                    CourseMiniature(week, count, color)
                }
            }
        }
    }
}

@Composable
private fun CourseMiniature(week: ScheduleWeek, count: Int, color: Color) {
    val rows = (maxOf(12, count)+1)/2
    val occupied = remember(week) { week.courses.flatMap { course -> course.lessons.map { week.dates.indexOf(course.date) to (it-1)/2 } }.toSet() }
    Canvas(Modifier.size(48.dp, 38.dp).padding(top = 5.dp)) {
        for(day in 0..6) for(row in 0 until rows) drawCircle(color.copy(alpha = if(day to row in occupied) .8f else .12f), 1.5.dp.toPx(),
            Offset(size.width*(day+.5f)/7, size.height*(row+.5f)/rows))
    }
}

internal fun courseColor(course: ScheduleCourse): Color {
    val palette = listOf(0xFF439F80, 0xFF527ECE, 0xFFD77686, 0xFF9272C9, 0xFF3499AA, 0xFFBD804C, 0xFF477E95, 0xFFB96697)
    return Color(palette[Math.floorMod(course.id.ifBlank { course.name }.hashCode(), palette.size)])
}

@Composable
private fun CourseDetails(courses: List<ScheduleCourse>, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if(courses.size>1) "重叠课程 · ${courses.size} 门" else "课程详情") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            courses.forEach { course -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(4.dp, 28.dp).background(courseColor(course), RoundedCornerShape(2.dp)))
                    Text(course.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                Text("日期   ${course.date}"); Text("节次   ${course.lessons.joinToString("、")}")
                Text("地点   ${course.location.ifBlank { "未提供" }}"); Text("教师   ${course.teacher.ifBlank { "未提供" }}")
                if(course.timeText.isNotBlank())Text("时间   ${course.timeText}")
                if(course.weeksText.isNotBlank())Text("周次   ${course.weeksText}")
            } }
        } }, confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}

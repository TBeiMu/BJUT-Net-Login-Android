package io.github.tbeimu.gongxiaozhu.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import io.github.tbeimu.gongxiaozhu.schedule.*
import io.github.tbeimu.gongxiaozhu.network.CampusConnectionNotice
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner

private val LessonColumnWidth = 42.dp

@Composable
internal fun ScheduleScreen(state: ScheduleUiState, modifier: Modifier, onImport: () -> Unit,
    onPause: () -> Unit, onRetry: () -> Unit, onMine: () -> Unit, todayProvider: () -> LocalDate = ::scheduleToday,
    connectionNotice: CampusConnectionNotice? = null, titleUnobstructed: Boolean = true, onCancelImport: () -> Unit = {},
    onDialogVisibilityChanged: (Boolean) -> Unit = {}) {
    val today = rememberScheduleDate(todayProvider)
    val schedule = state.data.complete
    var selected by rememberSaveable(schedule?.identity?.studentId, schedule?.term?.key) {
        mutableIntStateOf(schedule?.let { currentScheduleWeek(it, today.value) ?: it.term.currentWeek } ?: 1)
    }
    var showWeeks by rememberSaveable { mutableStateOf(false) }
    var details by remember(schedule) { mutableStateOf<List<ScheduleCourse>?>(null) }
    var cancelImportConfirmation by remember { mutableStateOf(false) }
    LaunchedEffect(state.canCancelImport) { if (!state.canCancelImport) cancelImportConfirmation = false }
    ReportDialogVisibility(details != null || (state.canCancelImport && cancelImportConfirmation), onDialogVisibilityChanged)
    val latestSelected by rememberUpdatedState<(Int) -> Unit>({ selected = it })
    val pagerScope = rememberCoroutineScope()
    val pager = remember(schedule) { schedule?.let {
        WeekPagerState(selected, it.term.weekCount, pagerScope) { number -> latestSelected(number) }
    } }
    DisposableEffect(pager) { onDispose { pager?.dispose() } }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val interactive = lifecycle.isAtLeast(Lifecycle.State.RESUMED) && titleUnobstructed && !showWeeks &&
        details == null && !(state.canCancelImport && cancelImportConfirmation) && state.page == null && !state.blocked
    LaunchedEffect(pager, interactive) { if (!interactive) pager?.cancel() }
    val weekNumber = pager?.week ?: 1
    val currentWeek = schedule?.let { currentScheduleWeek(it, today.value) }
    val count = remember(schedule) { schedule?.weeks?.flatMap { it.courses }?.flatMap { it.lessons }?.maxOrNull() ?: 0 }
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { showWeeks = !showWeeks }, enabled = schedule != null,
                modifier = Modifier.weight(1f).testTag("schedule_week_title")) {
                CampusConnectionTitle(if (schedule == null) "我的课表" else "第 $weekNumber 周", connectionNotice,
                    titleUnobstructed && details == null && !(state.canCancelImport && cancelImportConfirmation))
            }
            IconButton(onClick = onImport, enabled = !state.busy && !state.blocked && !state.loading,
                modifier = Modifier.testTag("import_schedule").semantics { contentDescription = if (schedule == null) "导入课表" else "刷新课表" }) { LineGlyph(AppGlyph.DOWNLOAD) }
            IconButton(onClick = { showWeeks = !showWeeks }, enabled = schedule != null,
                modifier = Modifier.semantics { contentDescription = "选择周次" }) { LineGlyph(AppGlyph.WEEKS) }
        }
        ScheduleStatus(state, onPause, onImport, onCancelImport, cancelImportConfirmation) {
            cancelImportConfirmation = it
            onDialogVisibilityChanged(it || details != null)
        }
        when {
            state.blocked -> ScheduleEmpty("课表存储暂不可用", "可以重试读取，或在课表管理中确认清除缓存。", "重试读取课表", onRetry,
                modifier = Modifier.weight(1f), secondary = onMine)
            schedule == null -> ScheduleEmpty(if (state.data.pending == null) "把这一学期放进口袋" else "整学期尚未导入完成",
                "通过 WiFi 或移动数据登录一次，自动导入整个学期。", if (state.data.pending == null) "导入研究生课表" else "登录后继续导入",
                onImport, modifier = Modifier.weight(1f), enabled = !state.busy && !state.loading)
            else -> Box(Modifier.weight(1f).fillMaxWidth().padding(start = 4.dp).clipToBounds()
                .testTag("schedule_week_area")) {
                val gridBackdrop = rememberScheduleBackdrop()
                Row(Modifier.fillMaxSize().recordScheduleBackdrop(gridBackdrop)) {
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        WeekGrid(schedule, pager!!, count, today.value, interactive, onCourse = {
                            details = it; onDialogVisibilityChanged(true)
                        })
                    }
                    WeekSelector(schedule, weekNumber, showWeeks, count, onExpand = { showWeeks = true }, onSelect = { pager!!.select(it); showWeeks = false })
                }
                BackToWeekOverlay(currentWeek != null && weekNumber != currentWeek && !showWeeks,
                    gridBackdrop, schedule.term.weekCount, onClick = {
                        today.value = todayProvider()
                        currentScheduleWeek(schedule, today.value)?.let { pager!!.select(it) }
                    })
            }
        }
    }
    details?.let { CourseDetails(it) { details = null; onDialogVisibilityChanged(cancelImportConfirmation && state.canCancelImport) } }
}

/** Share the same centered dot geometry with the floating button below it. */
private fun weekDotGroupHeight(height: Dp, weeks: Int): Dp =
    (minOf(height * .55f, 10.dp * weeks) + 6.dp).coerceAtMost(height)

@Composable
private fun BackToWeekOverlay(visible: Boolean, backdrop: ScheduleBackdrop, weeks: Int, onClick: () -> Unit) {
    Layout(modifier = Modifier.fillMaxSize(), content = {
        AnimatedVisibility(visible = visible,
            enter = slideInHorizontally(tween(360, easing = FastOutSlowInEasing)) { it },
            exit = slideOutHorizontally(tween(220, easing = FastOutSlowInEasing)) { it },
            label = "回到本周从右侧滑入") {
            FrostedBackToWeekButton(backdrop, enabled = visible, onClick = onClick)
        }
    }) { measurables, constraints ->
        val button = measurables.singleOrNull()?.measure(constraints.copy(minWidth = 0, minHeight = 0))
        val groupHeight = weekDotGroupHeight(constraints.maxHeight.toDp(), weeks).toPx()
        val top = ((constraints.maxHeight + groupHeight) / 2f + 4.dp.toPx()).roundToInt()
            .coerceIn(0, (constraints.maxHeight - (button?.height ?: 0)).coerceAtLeast(0))
        layout(constraints.maxWidth, constraints.maxHeight) {
            button?.place(constraints.maxWidth - button.width, top)
        }
    }
}

@Composable
private fun ScheduleStatus(state: ScheduleUiState, onPause: () -> Unit, onImport: () -> Unit, onCancelImport: () -> Unit,
    confirming: Boolean, onConfirmingChange: (Boolean) -> Unit) {
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
            CancelScheduleImportButton(state, onCancelImport, confirming, onConfirmingChange)
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
private fun WeekGrid(schedule: SemesterSchedule, pager: WeekPagerState, count: Int, today: LocalDate,
    interactive: Boolean, onCourse: (List<ScheduleCourse>) -> Unit) {
    val week = schedule.weeks[pager.week - 1]
    val velocityThreshold = with(LocalDensity.current) { WeekFlingVelocityDp.dp.toPx() }
    val drag = rememberDraggableState(pager::dragBy)
    val gesture = Modifier.draggable(state = drag, orientation = Orientation.Vertical,
        enabled = interactive, interactionSource = pager.interactions, startDragImmediately = pager.settling,
        onDragStarted = { pager.beginGesture() }, onDragStopped = { pager.endGesture(it) })
    Column(Modifier.fillMaxSize().testTag("schedule_pager")) {
        ScheduleDates(schedule, pager, today)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().onSizeChanged { pager.resize(it.height.toFloat(), velocityThreshold) }
            .testTag("week_grid_${week.number}")) {
            // Labels share fixed geometry across all weeks, including unexpected later lessons.
            val rowCount = maxOf(12, count)
            val cell = maxHeight / rowCount
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.width(LessonColumnWidth)) { repeat(rowCount) { n ->
                    LessonLabel(n + 1, cell)
                } }
                WeekLayers(schedule, pager, Modifier.weight(1f).fillMaxHeight().then(gesture).testTag("week_course_viewport")) { current ->
                    CourseCards(current, count, cell, interactive && !pager.moving && current.number == pager.week, onCourse)
                }
            }
        }
    }
}

/** Fixed left rail: lesson number above two compact time lines, as in the reference timetable. */
@Composable
private fun LessonLabel(lesson: Int, height: Dp) {
    val time = bjutLessonTime(lesson)
    val fontScale = LocalDensity.current.fontScale
    // Keep all twelve rows and both time lines visible on smaller screens and at large font sizes.
    val fit = if (time == null) 1f else minOf(1f,
        (height.value - 4).coerceAtLeast(1f) / (34f * fontScale),
        (LessonColumnWidth.value - 4) / (28f * fontScale))
    Column(Modifier.height(height).fillMaxWidth().clipToBounds(),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("$lesson", Modifier.testTag("lesson_$lesson"), color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = (11 * fit).sp, lineHeight = (12 * fit).sp, fontWeight = FontWeight.Medium, maxLines = 1)
        if (time != null) {
            Spacer(Modifier.height(2.dp * fit))
            Text("${time.start}\n${time.end}", Modifier.fillMaxWidth().testTag("lesson_time_$lesson"),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .85f),
                fontSize = (8.5f * fit).sp, lineHeight = (10 * fit).sp, textAlign = TextAlign.Center,
                maxLines = 2, softWrap = false)
        }
    }
}

@Composable
private fun CourseCards(week: ScheduleWeek, count: Int, cell: Dp, enabled: Boolean, onCourse: (List<ScheduleCourse>) -> Unit) {
    val blocks = remember(week) { courseBlocks(week) }
    val density = LocalDensity.current
    Box(Modifier.fillMaxSize().testTag("week_courses_${week.number}")) {
        Row(Modifier.fillMaxSize()) {
            repeat(7) { day -> Box(Modifier.weight(1f).fillMaxHeight()) {
                blocks.filter { it.day == day }.forEach { block ->
                    val course = block.courses.first(); val overlap = block.courses.size > 1
                    val blockHeight = cell * (block.last - block.first + 1)
                    val showLocation = blockHeight >= 34.dp * density.fontScale
                    val titleLines = ((blockHeight.value - 8 - (if (showLocation) 16 * density.fontScale else 0f)) / (14 * density.fontScale)).toInt().coerceIn(1, 6)
                    val color = if (overlap) Color(0xFF667B90) else courseColor(course)
                    Column(Modifier.offset(y = cell * (block.first - 1)).padding(1.dp).fillMaxWidth()
                        .height((blockHeight - 2.dp).coerceAtLeast(1.dp)).clip(RoundedCornerShape(5.dp))
                        .background(color.copy(alpha = .92f)).clickable(enabled = enabled) { onCourse(block.courses) }
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

@Composable
private fun ScheduleDates(schedule: SemesterSchedule, pager: WeekPagerState, today: LocalDate) {
    val week = schedule.weeks[pager.week - 1]
    Column(Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Box(Modifier.width(LessonColumnWidth))
            repeat(7) { day ->
                val isToday = week.dates[day] == today.toString()
                Column(Modifier.weight(1f).background(if (isToday) MaterialTheme.colorScheme.primary.copy(alpha = .09f) else Color.Transparent), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.fillMaxWidth().height(3.dp).background(if (isToday) MaterialTheme.colorScheme.primary else Color.Transparent))
                    Text("周${listOf("一","二","三","四","五","六","日")[day]}", fontSize = 11.sp,
                        modifier = Modifier.padding(top = 5.dp).testTag("weekday_$day"),
                        lineHeight = 14.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                        fontWeight = if (isToday) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
        val dateHeight = with(LocalDensity.current) { 18.sp.toDp() + 6.dp }
        WeekLayers(schedule, pager, Modifier.fillMaxWidth().padding(start = LessonColumnWidth)
            .height(dateHeight).testTag("week_date_viewport")) { current ->
            Row(Modifier.fillMaxSize()) {
                current.dates.forEachIndexed { day, date ->
                    val isToday = date == today.toString()
                    Box(Modifier.weight(1f).background(if (isToday) MaterialTheme.colorScheme.primary.copy(alpha = .09f) else Color.Transparent), contentAlignment = Alignment.Center) {
                        Text(LocalDate.parse(date).format(DateTimeFormatter.ofPattern("M/d")), fontSize = 10.sp,
                            modifier = Modifier.padding(bottom = 6.dp).testTag("date_${current.number}_$day"),
                            lineHeight = 12.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** Both clipped viewports use this fraction; fixed weekday and time rails are outside the layers. */
@Composable
private fun WeekLayers(schedule: SemesterSchedule, pager: WeekPagerState, modifier: Modifier,
    content: @Composable (ScheduleWeek) -> Unit) {
    val visibleWeeks by remember(pager) { derivedStateOf {
        val position = pager.visualPosition
        kotlin.math.floor(position).toInt().coerceIn(1, pager.weekCount)..
            kotlin.math.ceil(position).toInt().coerceIn(1, pager.weekCount)
    } }
    Box(modifier.clipToBounds()) {
        visibleWeeks.forEach { number ->
            key(number) {
                Box(Modifier.fillMaxSize().graphicsLayer {
                    translationY = (number - pager.visualPosition) * size.height
                }) { content(schedule.weeks[number - 1]) }
            }
        }
    }
}

@Composable
private fun WeekSelector(schedule: SemesterSchedule, selected: Int, expanded: Boolean, count: Int, onExpand: () -> Unit, onSelect: (Int) -> Unit) {
    val color = MaterialTheme.colorScheme.primary
    if (!expanded) BoxWithConstraints(Modifier.width(18.dp).fillMaxHeight()) {
        Canvas(Modifier.fillMaxWidth().height(weekDotGroupHeight(maxHeight, schedule.term.weekCount))
            .align(Alignment.Center).clickable(onClick = onExpand)
            .semantics { contentDescription = "展开周次列表" }.testTag("week_dots")) {
            val gap = (size.height - 6.dp.toPx()).coerceAtLeast(0f) / schedule.term.weekCount
            repeat(schedule.term.weekCount) { index -> drawCircle(color.copy(alpha = if(index+1 == selected) 1f else .22f),
                if(index+1 == selected) 2.6.dp.toPx() else 1.6.dp.toPx(), Offset(size.width/2, 3.dp.toPx()+gap*(index+.5f))) }
        }
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

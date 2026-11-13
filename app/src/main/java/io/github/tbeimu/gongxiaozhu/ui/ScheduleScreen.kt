package io.github.tbeimu.gongxiaozhu.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.togetherWith
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import io.github.tbeimu.gongxiaozhu.schedule.*
import io.github.tbeimu.gongxiaozhu.network.CampusConnectionNotice
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.sin

// Narrow enough that a 360 dp phone still fits three characters per course line.
private val LessonColumnWidth = 38.dp
private val SheetShape = RoundedCornerShape(20.dp)
private val Weekdays = listOf("一", "二", "三", "四", "五", "六", "日")

@Composable
internal fun ScheduleScreen(state: ScheduleUiState, modifier: Modifier, onImport: () -> Unit,
    onPause: () -> Unit, onRetry: () -> Unit, onMine: () -> Unit, todayProvider: () -> LocalDate = ::scheduleToday,
    connectionNotice: CampusConnectionNotice? = null, titleUnobstructed: Boolean = true, onCancelImport: () -> Unit = {},
    onDialogVisibilityChanged: (Boolean) -> Unit = {}, nowProvider: () -> LocalTime = ::scheduleNow,
    onAddCourse: (Int, Int) -> Unit = { _, _ -> }, onEditCourse: (ScheduleCourse, Boolean) -> Unit = { _, _ -> }) {
    val today = rememberScheduleDate(todayProvider)
    val now by rememberScheduleTime(nowProvider)
    val schedule = remember(state.data) { state.data.display() }
    var selected by rememberSaveable(schedule?.key, schedule?.term?.key) {
        mutableIntStateOf(schedule?.let { currentScheduleWeek(it, today.value) ?: it.term.currentWeek } ?: 1)
    }
    var showWeeks by rememberSaveable { mutableStateOf(false) }
    var details by remember(schedule) { mutableStateOf<List<ScheduleCourse>?>(null) }
    var cancelImportConfirmation by remember { mutableStateOf(false) }
    var addMenu by remember { mutableStateOf(false) }
    LaunchedEffect(state.canCancelImport) { if (!state.canCancelImport) cancelImportConfirmation = false }
    ReportDialogVisibility(addMenu || showWeeks || details != null || (state.canCancelImport && cancelImportConfirmation), onDialogVisibilityChanged)
    val latestSelected by rememberUpdatedState<(Int) -> Unit>({ selected = it })
    val pagerScope = rememberCoroutineScope()
    val pager = remember(schedule) { schedule?.let {
        WeekPagerState(selected, it.term.weekCount, pagerScope) { number -> latestSelected(number) }
    } }
    DisposableEffect(pager) { onDispose { pager?.dispose() } }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    LaunchedEffect(titleUnobstructed, state.busy, lifecycle) { if (!titleUnobstructed || state.busy || !lifecycle.isAtLeast(Lifecycle.State.RESUMED)) { addMenu = false; showWeeks = false } }
    val interactive = lifecycle.isAtLeast(Lifecycle.State.RESUMED) && titleUnobstructed && !showWeeks && !addMenu &&
        details == null && !(state.canCancelImport && cancelImportConfirmation) && state.page == null && !state.blocked
    LaunchedEffect(pager, interactive) { if (!interactive) pager?.cancel() }
    val weekNumber = pager?.week ?: 1
    val currentWeek = schedule?.let { currentScheduleWeek(it, today.value) }
    val count = remember(schedule) { schedule?.weeks?.flatMap { it.courses }?.flatMap { it.lessons }?.maxOrNull() ?: 0 }
    val toneOrder = remember(schedule) { schedule?.let(::courseToneOrder).orEmpty() }
    val palette = gxz
    // The first week shown arrives as a wave; later week changes rely on the slide alone.
    var intro by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { delay(1200); intro = false }
    Column(modifier.fillMaxSize()) {
        // A fixed-height header: the campus notice may replace the week title without moving the grid.
        Row(Modifier.fillMaxWidth().heightIn(min = 76.dp).padding(start = 18.dp, end = 6.dp, top = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).align(Alignment.Top)) {
                // In the current week the small line answers "what now?"; elsewhere it gives the dates.
                val live = schedule?.takeIf { weekNumber == currentWeek }?.weeks?.getOrNull(weekNumber - 1)
                    ?.let { todayStatus(it, today.value, now) }
                AnimatedContent(live ?: DayStatus(scheduleOverline(schedule, weekNumber, currentWeek, today.value), DayState.QUIET),
                    transitionSpec = { fadeIn(tween(Motion.MEDIUM, 60)) togetherWith fadeOut(tween(Motion.SHORT)) },
                    contentKey = { it.text }, label = "课表状态") { status -> StatusLine(status) }
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .clickable(enabled = schedule != null, interactionSource = remember { MutableInteractionSource() },
                        indication = null) { showWeeks = !showWeeks }
                    .testTag("schedule_week_title")) {
                    CampusConnectionTitle(if (schedule == null) "我的课表" else "第 $weekNumber 周", connectionNotice,
                        titleUnobstructed && !addMenu && !showWeeks && details == null && !(state.canCancelImport && cancelImportConfirmation))
                }
            }
            ScheduleAddButton(addMenu, !state.busy && !state.blocked && !state.loading && titleUnobstructed,
                { addMenu = it; showWeeks = false; details = null; cancelImportConfirmation = false }, onImport,
                { onAddCourse(weekNumber, today.value.dayOfWeek.value) })
            PlainIconButton(onClick = { addMenu = false; details = null; showWeeks = !showWeeks }, enabled = schedule != null && titleUnobstructed,
                modifier = Modifier.semantics { contentDescription = "选择周次" }) { LineGlyph(AppGlyph.WEEKS, Modifier.size(20.dp)) }
        }
        ScheduleStatus(state, onPause, onImport, onCancelImport, cancelImportConfirmation) {
            cancelImportConfirmation = it
            onDialogVisibilityChanged(it || details != null)
        }
        when {
            state.blocked -> ScheduleEmpty("课表存储暂不可用", "可以重试读取，或在课表管理中确认清除缓存。", "重试读取课表", onRetry,
                modifier = Modifier.weight(1f), secondary = onMine)
            schedule == null -> ScheduleEmpty(if (state.data.pending == null) "把这一学期放进口袋" else "整学期尚未导入完成",
                "导入学校课表，或添加自己的课程。", "添加课表",
                { addMenu = true }, modifier = Modifier.weight(1f), enabled = !state.busy && !state.loading)
            else -> Box(Modifier.weight(1f).fillMaxWidth().padding(start = 14.dp, top = 4.dp, bottom = 10.dp).clipToBounds()
                .testTag("schedule_week_area")) {
                val gridBackdrop = rememberScheduleBackdrop()
                Row(Modifier.fillMaxSize().recordScheduleBackdrop(gridBackdrop)) {
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        WeekGrid(schedule, pager!!, count, today.value, now, toneOrder, intro, interactive, onCourse = {
                            details = it; onDialogVisibilityChanged(true)
                        })
                    }
                    WeekDots(schedule, weekNumber, showWeeks, onExpand = { addMenu = false; details = null; showWeeks = true })
                }
                // The week list floats over the grid, so opening it never reflows the timetable.
                androidx.compose.animation.AnimatedVisibility(visible = showWeeks, modifier = Modifier.align(Alignment.CenterEnd),
                    enter = slideInHorizontally(tween(Motion.MEDIUM + 40, easing = Motion.Decelerate)) { it } + fadeIn(tween(Motion.SHORT)),
                    exit = slideOutHorizontally(tween(Motion.MEDIUM - 60, easing = Motion.Accelerate)) { it } + fadeOut(tween(Motion.SHORT, 60)),
                    label = "周次列表") {
                    WeekList(schedule, weekNumber, count, enabled = showWeeks, onSelect = { pager!!.select(it); showWeeks = false })
                }
                BackToWeekOverlay(currentWeek != null && weekNumber != currentWeek && !showWeeks,
                    gridBackdrop, schedule.term.weekCount, onClick = {
                        today.value = todayProvider()
                        currentScheduleWeek(schedule, today.value)?.let { pager!!.select(it) }
                    })
            }
        }
    }
    details?.let { CourseDetails(it, toneOrder, onDismiss = { details = null }, editable = !state.busy,
        onEdit = { course, delete -> details = null; onEditCourse(course, delete) }) }
}

/** The small line above the title: the visible week's dates, or today's date before anything is imported. */
private fun scheduleOverline(schedule: DisplaySchedule?, weekNumber: Int, currentWeek: Int?, today: LocalDate): String {
    if (schedule == null) return "${today.monthValue}月${today.dayOfMonth}日 · 星期${Weekdays[today.dayOfWeek.value - 1]}"
    val dates = schedule.weeks.getOrNull(weekNumber - 1)?.dates?.mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }
    if (dates == null || dates.size != 7) return schedule.term.title
    val first = dates.first(); val last = dates.last()
    val range = "${first.monthValue}月${first.dayOfMonth}日 – " +
        (if (last.monthValue == first.monthValue) "" else "${last.monthValue}月") + "${last.dayOfMonth}日"
    return if (weekNumber == currentWeek) "本周 · $range" else range
}

/**
 * Breaks an all-Chinese name into lines of near-equal length, so a narrow card reads 机器 / 学习
 * rather than 机器学 / 习. Names with Latin text, or that would not fit [maxLines], are left alone.
 */
internal fun balanceHan(name: String, perLine: Int, maxLines: Int): String {
    if (perLine < 1 || name.isEmpty() || !name.all { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }) return name
    val lines = (name.length + perLine - 1) / perLine
    if (lines <= 1 || lines > maxLines) return name
    val base = name.length / lines; val longer = name.length % lines
    var at = 0
    return List(lines) { index ->
        val take = base + if (index < longer) 1 else 0
        name.substring(at, at + take).also { at += take }
    }.joinToString("\n")
}

internal enum class DayState { QUIET, NEXT, IN_CLASS }
internal data class DayStatus(val text: String, val state: DayState)

/** Start and end of a run of lessons on the BJUT timetable, if both are known. */
internal fun lessonSpan(first: Int, last: Int): Pair<LocalTime, LocalTime>? {
    val start = bjutLessonTime(first) ?: return null
    val end = bjutLessonTime(last) ?: return null
    return LocalTime.parse(start.start) to LocalTime.parse(end.end)
}

/**
 * Where [now] falls on the lesson grid, in rows from the top: inside a lesson it moves through that
 * row; during a break it rests on the line between lessons. Null before the first or after the last.
 */
internal fun nowRow(now: LocalTime): Float? {
    var lesson = 1
    while (true) {
        val (start, end) = lessonSpan(lesson, lesson) ?: return null
        if (now < start) return if (lesson == 1) null else (lesson - 1).toFloat()
        if (now < end) return (lesson - 1) + java.time.Duration.between(start, now).toMinutes().toFloat() /
            java.time.Duration.between(start, end).toMinutes()
        lesson++
    }
}

/** One line about today: the class in progress, the next one, or that the day is done. */
internal fun todayStatus(week: ScheduleWeek, today: LocalDate, now: LocalTime): DayStatus? {
    val day = week.dates.indexOf(today.toString())
    if (day < 0) return null
    val blocks = courseBlocks(week).filter { it.day == day }
    if (blocks.isEmpty()) return DayStatus("今天没有课 · 好好休息", DayState.QUIET)
    for (block in blocks) {
        val (start, end) = lessonSpan(block.first, block.last) ?: continue
        val course = block.courses.first()
        val what = if (block.courses.size > 1) "${block.courses.size} 门重叠课程"
            else course.name + course.location.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
        if (now < start) {
            val minutes = java.time.Duration.between(now, start).toMinutes()
            return if (minutes <= 60) DayStatus("$minutes 分钟后上课 · $what", DayState.NEXT)
                else DayStatus("${start.format(Clock)} 下一节 · $what", DayState.NEXT)
        }
        if (now < end) return DayStatus("上课中 · 还剩 ${java.time.Duration.between(now, end).toMinutes()} 分钟 · $what", DayState.IN_CLASS)
    }
    return DayStatus("今天的课都上完了", DayState.QUIET)
}

internal val Clock = DateTimeFormatter.ofPattern("HH:mm")

@Composable
private fun StatusLine(status: DayStatus) {
    val palette = gxz
    Row(Modifier.testTag("schedule_overline"), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (status.state != DayState.QUIET) Box(Modifier.size(6.dp).clip(CircleShape)
            .background(if (status.state == DayState.IN_CLASS) palette.accent else palette.ink))
        Text(status.text, style = MaterialTheme.typography.labelMedium,
            color = if (status.state == DayState.QUIET) palette.inkFaint else palette.inkSoft,
            fontWeight = if (status.state == DayState.QUIET) FontWeight.Medium else FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
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
internal fun ScheduleStatus(state: ScheduleUiState, onPause: () -> Unit, onImport: () -> Unit, onCancelImport: () -> Unit,
    confirming: Boolean, onConfirmingChange: (Boolean) -> Unit) {
    if (state.notice !in setOf(ScheduleNotice.PROGRESS, ScheduleNotice.PAUSED, ScheduleNotice.ERROR) || state.page != null || state.blocked) return
    val palette = gxz
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).testTag("schedule_status")
        .glass(palette, RoundedCornerShape(16.dp)).padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp)) {
        val progress by animateFloatAsState(state.imported.toFloat() / state.total.coerceAtLeast(1),
            tween(Motion.LONG, easing = Motion.Emphasized), label = "导入进度")
        if (state.busy && state.total > 0) LinearProgressIndicator(progress = { progress },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, end = 8.dp).clip(CircleShape),
            color = palette.ink, trackColor = palette.well)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusDot(when (state.notice) {
                ScheduleNotice.ERROR -> MaterialTheme.colorScheme.error
                ScheduleNotice.PAUSED -> palette.inkFaint
                else -> palette.positive
            })
            Text(if (state.busy && state.total > 0) "正在导入 ${state.imported}/${state.total} 周" else state.message,
                Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = palette.ink,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = if (state.busy) onPause else onImport) { Text(if (state.busy) "暂停" else if (state.data.pending != null) "继续" else "重试") }
        }
        CancelScheduleImportButton(state, onCancelImport, confirming, onConfirmingChange)
    }
}

@Composable
private fun ScheduleEmpty(title: String, subtitle: String, action: String, onClick: () -> Unit,
    modifier: Modifier, enabled: Boolean = true, secondary: (() -> Unit)? = null) {
    Box(modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), contentAlignment = Alignment.Center) {
        GlassCard(Modifier.widthIn(max = 420.dp), padding = PaddingValues(24.dp), spacing = 0.dp) {
            TimetableMotif(Modifier.fillMaxWidth().height(132.dp))
            Spacer(Modifier.height(22.dp))
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = gxz.inkSoft)
            Spacer(Modifier.height(22.dp))
            CtaButton(action, onClick, enabled = enabled)
            secondary?.let { TextButton(onClick = it, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp)) { Text("课表管理") } }
        }
    }
}

/** A week in miniature for the empty state: the timetable the import will bring. */
@Composable
internal fun TimetableMotif(modifier: Modifier, plain: Boolean = false) {
    val palette = gxz
    val tones = List(CourseHues.size) { courseTone(it, palette.dark) }
    // day, first row, rows, tone
    val blocks = listOf(intArrayOf(0, 0, 2, 0), intArrayOf(0, 3, 1, 4), intArrayOf(1, 1, 2, 2), intArrayOf(2, 0, 1, 7),
        intArrayOf(2, 2, 2, 9), intArrayOf(3, 1, 1, 5), intArrayOf(3, 3, 1, 1), intArrayOf(4, 0, 2, 3), intArrayOf(4, 2, 1, 8))
    // The courses settle into the week one by one, left to right, the way an import fills it.
    val reduced = LocalReducedMotion.current
    val clock = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) { clock.animateTo(1f, tween(1500, 150, LinearEasing)) }
    Canvas(if (plain) modifier else modifier.clip(TileShape).background(palette.well)) {
        val pad = if (plain) 0f else 10.dp.toPx(); val gap = 5.dp.toPx(); val columns = 5; val rows = 4
        val cellW = (size.width - pad * 2 - gap * (columns - 1)) / columns
        val cellH = (size.height - pad * 2 - gap * (rows - 1)) / rows
        val radius = CornerRadius(6.dp.toPx())
        val rise = 14.dp.toPx()
        if (!plain) for (day in 0 until columns) for (row in 0 until rows) drawRoundRect(palette.ink.copy(alpha = .04f),
            Offset(pad + day * (cellW + gap), pad + row * (cellH + gap)), Size(cellW, cellH), radius)
        blocks.forEachIndexed { index, (day, row, span, tone) ->
            val local = (clock.value * 1.8f - index * .1f).coerceIn(0f, 1f)
            if (local <= 0f) return@forEachIndexed
            val eased = Motion.Decelerate.transform(local)
            val alpha = (local * 2.5f).coerceAtMost(1f)
            val top = pad + row * (cellH + gap) + (1 - eased) * rise
            drawRoundRect(tones[tone].fill.copy(alpha = tones[tone].fill.alpha * alpha), Offset(pad + day * (cellW + gap), top),
                Size(cellW, cellH * span + gap * (span - 1)), radius)
            drawRoundRect(tones[tone].strong.copy(alpha = .55f * alpha),
                Offset(pad + day * (cellW + gap) + 6.dp.toPx(), top + 7.dp.toPx()),
                Size(cellW * .5f * eased, 3.dp.toPx()), CornerRadius(2.dp.toPx()))
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
private fun WeekGrid(schedule: DisplaySchedule, pager: WeekPagerState, count: Int, today: LocalDate, now: LocalTime,
    toneOrder: Map<String, Int>, intro: Boolean, interactive: Boolean, onCourse: (List<ScheduleCourse>) -> Unit) {
    val week = schedule.weeks[pager.week - 1]
    val palette = gxz
    val velocityThreshold = with(LocalDensity.current) { WeekFlingVelocityDp.dp.toPx() }
    val drag = rememberDraggableState(pager::dragBy)
    val gesture = Modifier.draggable(state = drag, orientation = Orientation.Vertical,
        enabled = interactive, interactionSource = pager.interactions, startDragImmediately = pager.settling,
        onDragStarted = { pager.beginGesture() }, onDragStopped = { pager.endGesture(it) })

    // One sheet of frosted paper carries the dates, the lesson rail and the courses.
    Column(Modifier.fillMaxSize().glass(palette, SheetShape).padding(horizontal = 2.dp).padding(bottom = 3.dp)
        .testTag("schedule_pager")) {
        ScheduleDates(schedule, pager, today, intro)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().onSizeChanged { pager.resize(it.height.toFloat(), velocityThreshold) }.testTag("week_grid_${week.number}")) {
            // Labels share fixed geometry across all weeks, including unexpected later lessons.
            val rowCount = maxOf(12, count)
            val cell = maxHeight / rowCount
            val faint = palette.ink.copy(alpha = if (palette.dark) .04f else .03f)
            val firm = palette.ink.copy(alpha = if (palette.dark) .13f else .1f)
            val weekend = palette.ink.copy(alpha = if (palette.dark) .03f else .02f)
            val todayIndex = week.dates.indexOf(today.toString())
            val nowAt = if (todayIndex >= 0) nowRow(now) else null
            // Quiet paper: the three parts of the day are ruled, single lessons only whispered.
            Row(Modifier.fillMaxSize().drawBehind {
                val rail = LessonColumnWidth.toPx(); val row = size.height / rowCount
                val area = size.width - rail
                // Saturday and Sunday sit a shade back.
                drawRect(weekend, Offset(rail + area * 5 / 7f, 0f), Size(area * 2 / 7f, size.height))
                for (n in 1 until rowCount) {
                    val boundary = n % 4 == 0 && n <= 8
                    if (boundary) drawLine(firm, Offset(10.dp.toPx(), row * n), Offset(size.width - 8.dp.toPx(), row * n), .8.dp.toPx())
                    else drawLine(faint, Offset(rail, row * n), Offset(size.width - 8.dp.toPx(), row * n), .6.dp.toPx())
                }
            }) {
                Box(Modifier.width(LessonColumnWidth).fillMaxHeight()) {
                    Column { repeat(rowCount) { n -> LessonLabel(n + 1, cell) } }
                    NowTag(nowAt, now, cell)
                }
                WeekLayers(schedule, pager, Modifier.weight(1f).fillMaxHeight().then(gesture).testTag("week_course_viewport")) { current ->
                    CourseCards(current, count, cell, today, now, toneOrder, intro,
                        interactive && !pager.moving && current.number == pager.week, onCourse)
                }
            }
        }
    }
}

/** The current time on the rail, a vermilion tag at the height of the now line; fades out off this week. */
@Composable
private fun NowTag(row: Float?, now: LocalTime, cell: Dp) {
    val palette = gxz
    val measurer = rememberTextMeasurer()
    val last = remember { floatArrayOf(row ?: 0f) }
    if (row != null) last[0] = row
    val shown by animateFloatAsState(if (row != null) 1f else 0f, tween(Motion.MEDIUM), label = "此刻标签")
    val style = TextStyle(fontSize = 9.5.sp, fontWeight = FontWeight.Bold, color = palette.onAccent,
        fontFeatureSettings = "tnum", letterSpacing = 0.sp)
    Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = shown }) {
        if (shown <= 0f) return@Canvas
        val text = measurer.measure(now.format(Clock), style)
        val w = text.size.width + 8.dp.toPx(); val h = text.size.height + 3.dp.toPx()
        val y = (last[0] * cell.toPx()).coerceIn(h / 2, size.height - h / 2)
        val x = size.width - w - 1.dp.toPx()
        drawRoundRect(palette.accent, Offset(x, y - h / 2), Size(w, h), CornerRadius(h / 2))
        drawText(text, topLeft = Offset(x + 4.dp.toPx(), y - text.size.height / 2f))
    }
}

/** Fixed left rail: start time, the lesson number between the times, then the end time. */
@Composable
private fun LessonLabel(lesson: Int, height: Dp) {
    val time = bjutLessonTime(lesson)
    val fontScale = LocalDensity.current.fontScale
    val palette = gxz
    // Keep all twelve rows, the number and both time lines visible on smaller screens and at large font sizes.
    val fit = if (time == null) 1f else minOf(1f,
        (height.value - 4).coerceAtLeast(1f) / (38f * fontScale),
        (LessonColumnWidth.value - 4) / (28f * fontScale))
    Column(Modifier.height(height).fillMaxWidth().clipToBounds(),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        if (time == null) {
            Text("$lesson", Modifier.testTag("lesson_$lesson"), color = palette.inkSoft.copy(alpha = .85f),
                fontSize = (11.5f * fit).sp, lineHeight = (12 * fit).sp, fontWeight = FontWeight.Medium, maxLines = 1)
        } else {
            Text("${time.start}", Modifier.fillMaxWidth().testTag("lesson_start_$lesson"),
                color = palette.inkFaint.copy(alpha = .7f),
                fontSize = (8.5f * fit).sp, lineHeight = (10 * fit).sp, textAlign = TextAlign.Center,
                maxLines = 1, softWrap = false)
            Text("$lesson", Modifier.testTag("lesson_$lesson"), color = palette.inkSoft.copy(alpha = .85f),
                fontSize = (11.5f * fit).sp, lineHeight = (12 * fit).sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Text("${time.end}", Modifier.fillMaxWidth().testTag("lesson_end_$lesson"),
                color = palette.inkFaint.copy(alpha = .7f),
                fontSize = (8.5f * fit).sp, lineHeight = (10 * fit).sp, textAlign = TextAlign.Center,
                maxLines = 1, softWrap = false)
        }
    }
}

@Composable
private fun CourseCards(week: ScheduleWeek, count: Int, cell: Dp, today: LocalDate, now: LocalTime,
    toneOrder: Map<String, Int>, intro: Boolean, enabled: Boolean, onCourse: (List<ScheduleCourse>) -> Unit) {
    val blocks = remember(week) { courseBlocks(week) }
    val fontScale = LocalDensity.current.fontScale
    val palette = gxz
    val shape = RoundedCornerShape(7.dp)
    val todayIndex = week.dates.indexOf(today.toString())
    val nowAt = if (todayIndex >= 0) nowRow(now) else null
    BoxWithConstraints(Modifier.fillMaxSize().testTag("week_courses_${week.number}")) {
        // How many Chinese characters fit on one line of a card: used to break names evenly.
        val perLine = ((maxWidth / 7 - 8.5.dp) / (11.dp * fontScale)).toInt()
        // The now line runs beneath the cards, so it never strikes through a course name:
        // a faint thread across the week, drawn firmly through today with a bead at its start.
        if (nowAt != null) Canvas(Modifier.fillMaxSize()) {
            val y = nowAt * cell.toPx()
            val column = size.width / 7
            drawLine(palette.accent.copy(alpha = .22f), Offset(0f, y), Offset(size.width, y), 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())))
            drawLine(palette.accent, Offset(column * todayIndex, y), Offset(column * (todayIndex + 1), y), 2.dp.toPx(), StrokeCap.Round)
            drawCircle(palette.accent, 4.dp.toPx(), Offset(column * todayIndex + 1.dp.toPx(), y))
            drawCircle(palette.onAccent, 1.6.dp.toPx(), Offset(column * todayIndex + 1.dp.toPx(), y))
        }
        Row(Modifier.fillMaxSize()) {
            repeat(7) { day ->
                val isToday = day == todayIndex
                val date = runCatching { LocalDate.parse(week.dates[day]) }.getOrNull()
                Box(Modifier.weight(1f).fillMaxHeight().then(if (isToday) Modifier.padding(horizontal = .5.dp)
                    .background(palette.ink.copy(alpha = if (palette.dark) .04f else .025f), RoundedCornerShape(9.dp)) else Modifier)) {
                    blocks.filter { it.day == day }.forEach { block ->
                        val course = block.courses.first(); val overlap = block.courses.size > 1
                        val blockHeight = cell * (block.last - block.first + 1)
                        val tone = if (overlap) overlapTone(palette.dark) else courseTone(course, palette.dark, toneOrder)
                        // Time gives each block a state: already over, in progress, or still ahead.
                        val span = lessonSpan(block.first, block.last)
                        val over = date != null && (date < today || (date == today && span != null && now >= span.second))
                        val progress = if (isToday && nowAt != null && span != null && now >= span.first && now < span.second)
                            ((nowAt - (block.first - 1)) / (block.last - block.first + 1)).coerceIn(0f, 1f) else null
                        // Whole lines only: reserve the location first, give the name what remains.
                        val room = (blockHeight.value - 10f) / fontScale
                        val hasLocation = overlap || course.location.isNotBlank()
                        val locationLines = when { !hasLocation || room < 34f -> 0; room >= 66f -> 2; else -> 1 }
                        val titleLines = ((room - if (locationLines > 0) 3f + 12f * locationLines else 0f) / 14f).toInt().coerceIn(1, 6)
                        val interaction = remember { MutableInteractionSource() }
                        // Intro wave: Monday first, and down each day from morning to evening.
                        Column(Modifier.offset(y = cell * (block.first - 1)).padding(1.dp).fillMaxWidth()
                            .height((blockHeight - 2.dp).coerceAtLeast(1.dp))
                            .entrance(delayMillis = 120 + day * Motion.STAGGER + (block.first - 1) * 12, distance = 12.dp,
                                fromScale = .9f, enabled = intro)
                            .pressScale(interaction, .93f)
                            // Paper cards lift off the page by a hair; finished ones lie flat and grey.
                            .then(if (over) Modifier else Modifier.shadow(1.dp, shape, ambientColor = tone.strong, spotColor = tone.strong))
                            .clip(shape)
                            .background(if (over) palette.ink.copy(alpha = if (palette.dark) .06f else .045f) else tone.fill)
                            .drawBehind {
                                // The class in progress fills from the top as the minutes pass.
                                if (progress != null) {
                                    drawRect(tone.strong.copy(alpha = if (palette.dark) .22f else .16f), size = Size(size.width, size.height * progress))
                                    // Inside the card the now line continues behind the words.
                                    drawLine(palette.accent, Offset(0f, size.height * progress), Offset(size.width, size.height * progress), 1.5.dp.toPx())
                                }
                            }
                            .then(if (progress != null) Modifier.border(1.5.dp, tone.strong, shape) else Modifier)
                            .clickable(interaction, LocalIndication.current, enabled = enabled) { onCourse(block.courses) }
                            .testTag("course_${week.number}_${block.day}_${block.first}")
                            .padding(start = 4.dp, end = 3.dp, top = 5.dp, bottom = 4.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(if (overlap) "重叠课" else balanceHan(course.name, perLine, titleLines),
                                modifier = Modifier.fillMaxWidth(),
                                color = if (over) palette.inkFaint else tone.text,
                                fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.sp,
                                maxLines = titleLines, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                            if (locationLines > 0) {
                                Spacer(Modifier.height(3.dp))
                                Text(if (overlap) "点击查看" else course.location,
                                    modifier = Modifier.fillMaxWidth(),
                                    color = if (over) palette.inkFaint.copy(alpha = .8f) else tone.text.copy(alpha = .55f),
                                    fontSize = 9.5.sp, lineHeight = 12.sp, letterSpacing = 0.sp,
                                    maxLines = locationLines, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                            }
                        }
                    }
                }
            }
        }
        if (week.courses.isEmpty()) Text(if (count == 0) "本学期暂无课程" else "本周无课", Modifier.align(Alignment.Center),
            style = MaterialTheme.typography.titleMedium, color = palette.inkFaint)
    }
}

@Composable
private fun ScheduleDates(schedule: DisplaySchedule, pager: WeekPagerState, today: LocalDate, intro: Boolean) {
    val week = schedule.weeks[pager.week - 1]
    val palette = gxz
    Column(Modifier.fillMaxWidth().heightIn(min = 50.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Box(Modifier.width(LessonColumnWidth))
            repeat(7) { day ->
                val isToday = week.dates[day] == today.toString()
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text("周${Weekdays[day]}", fontSize = 11.sp,
                        modifier = Modifier.padding(top = 9.dp).testTag("weekday_$day"),
                        lineHeight = 14.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                        color = if (isToday) palette.accent else palette.inkFaint,
                        fontWeight = if (isToday) FontWeight.SemiBold else FontWeight.Medium)
                }
            }
        }
        val dateHeight = with(LocalDensity.current) { 20.sp.toDp() + 10.dp }
        Row(Modifier.fillMaxWidth().height(dateHeight), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(LessonColumnWidth), contentAlignment = Alignment.Center) {
                Text(runCatching { "${LocalDate.parse(week.dates.first()).monthValue}月" }.getOrDefault(""),
                    fontSize = 10.sp, lineHeight = 12.sp, maxLines = 1, softWrap = false, color = palette.inkFaint,
                    fontWeight = FontWeight.Medium)
            }
            WeekLayers(schedule, pager, Modifier.weight(1f).fillMaxHeight().testTag("week_date_viewport")) { current ->
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    current.dates.forEachIndexed { day, date ->
                        val isToday = date == today.toString()
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            Text(LocalDate.parse(date).format(DateTimeFormatter.ofPattern("M/d")), fontSize = 11.sp,
                                modifier = Modifier.then(if (isToday) Modifier.popIn(520, enabled = intro).background(palette.accent, CircleShape) else Modifier)
                                    .padding(horizontal = 5.dp, vertical = 2.dp).testTag("date_${current.number}_$day"),
                                lineHeight = 13.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                                fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp,
                                color = if (isToday) palette.onAccent else palette.inkSoft)
                        }
                    }
                }
            }
        }
    }
}

/** Both clipped viewports use this fraction; fixed weekday and time rails are outside the layers. */
@Composable
private fun WeekLayers(schedule: DisplaySchedule, pager: WeekPagerState, modifier: Modifier,
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
private fun WeekDots(schedule: DisplaySchedule, selected: Int, expanded: Boolean, onExpand: () -> Unit) {
    val color = gxz.ink
    // The marker travels between dots and stretches while it moves, like a bead on a string.
    val position by animateFloatAsState(selected.toFloat(), Motion.snappy(), label = "周次指示位置")
    BoxWithConstraints(Modifier.width(18.dp).fillMaxHeight()) {
        if (!expanded)
        Canvas(Modifier.fillMaxWidth().height(weekDotGroupHeight(maxHeight, schedule.term.weekCount))
            .align(Alignment.Center).clickable(onClick = onExpand)
            .semantics { contentDescription = "展开周次列表" }.testTag("week_dots")) {
            val gap = (size.height - 6.dp.toPx()).coerceAtLeast(0f) / schedule.term.weekCount
            // Accepted targets are marked immediately while the decorative marker travels.
            repeat(schedule.term.weekCount) { index -> drawCircle(color.copy(alpha = if (index + 1 == selected) 1f else .14f), 1.6.dp.toPx(),
                Offset(size.width/2, 3.dp.toPx()+gap*(index+.5f))) }
            val travel = kotlin.math.abs(position - kotlin.math.round(position)) * 2f
            val width = 5.4.dp.toPx()
            val height = width + travel * gap * 1.4f
            val y = 3.dp.toPx() + gap * (position - .5f)
            drawRoundRect(color, Offset(size.width / 2 - width / 2, y - height / 2), Size(width, height), CornerRadius(width / 2))
        }
    }
}

@Composable
private fun WeekList(schedule: DisplaySchedule, selected: Int, count: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
    val palette = gxz
    val list = rememberLazyListState()
    LaunchedEffect(selected) { list.scrollToItem((selected-2).coerceAtLeast(0)) }
    // Opaque, so the timetable underneath never shows through the list.
    LazyColumn(Modifier.padding(end = 2.dp).width(88.dp).fillMaxHeight().clip(SheetShape)
        .background(MaterialTheme.colorScheme.surfaceContainerHigh).border(.8.dp, palette.hairline, SheetShape).testTag("week_list"),
        state = list, contentPadding = PaddingValues(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(schedule.weeks, key = { it.number }) { week ->
            val chosen = week.number == selected
            val fill by animateColorAsState(if (chosen) palette.ink else palette.ink.copy(alpha = 0f), tween(Motion.MEDIUM), label = "周次底色")
            val ink by animateColorAsState(if (chosen) palette.onInk else palette.ink, tween(Motion.MEDIUM), label = "周次文字")
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(fill)
                .springClick(enabled = enabled, pressedScale = .92f) { onSelect(week.number) }.testTag("week_choice_${week.number}").padding(vertical = 9.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("第 ${week.number} 周", fontSize = 12.sp, lineHeight = 15.sp, fontWeight = FontWeight.SemiBold, color = ink)
                CourseMiniature(week, count, ink)
            }
        }
    }
}

@Composable
private fun CourseMiniature(week: ScheduleWeek, count: Int, color: Color) {
    val rows = (maxOf(12, count)+1)/2
    val occupied = remember(week) { week.courses.flatMap { course -> course.lessons.map { week.dates.indexOf(course.date) to (it-1)/2 } }.toSet() }
    Canvas(Modifier.size(46.dp, 38.dp).padding(top = 6.dp)) {
        for(day in 0..6) for(row in 0 until rows) drawCircle(color.copy(alpha = if(day to row in occupied) .85f else .14f), 1.5.dp.toPx(),
            Offset(size.width*(day+.5f)/7, size.height*(row+.5f)/rows))
    }
}

/** Fill, text and accent of one course; lightness and chroma are equal across hues. */
internal class CourseTone(val fill: Color, val text: Color, val strong: Color)

// Ten hues, evenly weighted in OKLCH so that no course shouts louder than another.
// Neighbours in this list are far apart on the wheel: courses met on the same day look different.
private val CourseHues = floatArrayOf(255f, 20f, 160f, 300f, 60f, 200f, 345f, 95f, 228f, 130f)

internal fun oklch(lightness: Float, chroma: Float, hue: Float, alpha: Float = 1f): Color {
    val angle = Math.toRadians(hue.toDouble())
    val a = chroma * cos(angle); val b = chroma * sin(angle)
    val l = (lightness + .3963377774 * a + .2158037573 * b).pow(3)
    val m = (lightness - .1055613458 * a - .0638541728 * b).pow(3)
    val s = (lightness - .0894841775 * a - 1.2914855480 * b).pow(3)
    fun encode(value: Double): Float {
        val v = value.coerceIn(0.0, 1.0)
        return (if (v <= .0031308) 12.92 * v else 1.055 * v.pow(1 / 2.4) - .055).toFloat()
    }
    return Color(encode(4.0767416621 * l - 3.3077115913 * m + .2309699292 * s),
        encode(-1.2684380046 * l + 2.6097574011 * m - .3413193965 * s),
        encode(-.0041960863 * l - .7034186147 * m + 1.7076147010 * s), alpha)
}

internal fun courseTone(index: Int, dark: Boolean): CourseTone {
    val hue = CourseHues[Math.floorMod(index, CourseHues.size)]
    // Morandi tints: soft, slightly grey colours of equal lightness, with ink-coloured words.
    // Ten courses then read as one calm page rather than a box of sweets.
    val warm = hue in 50f..125f
    return if (dark) CourseTone(oklch(.33f, .04f, hue, .97f), oklch(.94f, .012f, hue), oklch(.74f, .12f, hue))
    else CourseTone(oklch(.93f, if (warm) .05f else .042f, hue),
        oklch(.27f, .025f, hue), oklch(if (warm) .72f else .62f, if (warm) .14f else .13f, hue))
}

private fun courseKey(course: ScheduleCourse) = course.id.ifBlank { course.name }

/** Hues follow first appearance in the term: one course keeps its colour, neighbours rarely share one. */
internal fun courseToneOrder(schedule: DisplaySchedule): Map<String, Int> {
    val order = LinkedHashMap<String, Int>()
    schedule.weeks.forEach { week ->
        week.courses.sortedWith(compareBy({ it.date }, { it.lessons.minOrNull() ?: 0 }))
            .forEach { course -> order.getOrPut(courseKey(course)) { order.size } }
    }
    return order
}

internal fun courseTone(course: ScheduleCourse, dark: Boolean, order: Map<String, Int> = emptyMap()): CourseTone =
    courseTone(order[courseKey(course)] ?: courseKey(course).hashCode(), dark)

internal fun overlapTone(dark: Boolean): CourseTone =
    if (dark) CourseTone(Color(0xFF2C3440).copy(alpha = .97f), Color(0xFFE6EAF0), Color(0xFF9AA6B6))
    else CourseTone(Color(0xFFF1F3F6).copy(alpha = .97f), Color(0xFF2A3342), Color(0xFF8A96A8))

/** The saturated accent of a course, stable across weeks. */
internal fun courseColor(course: ScheduleCourse): Color = courseTone(course, false).strong

@Composable
internal fun CourseDetails(courses: List<ScheduleCourse>, toneOrder: Map<String, Int>, editable: Boolean = false,
    onEdit: (ScheduleCourse, Boolean) -> Unit = { _, _ -> }, onDismiss: () -> Unit) {
    val palette = gxz
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if(courses.size>1) "重叠课程 · ${courses.size} 门" else "课程详情") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            courses.forEach { course ->
                val tone = courseTone(course, palette.dark, toneOrder)
                Column(Modifier.fillMaxWidth().clip(TileShape).background(tone.fill).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(course.name, style = MaterialTheme.typography.titleMedium, color = tone.text)
                    Spacer(Modifier.height(2.dp))
                    DetailRow("日期", course.date, tone)
                    DetailRow("节次", course.lessons.joinToString("、"), tone)
                    DetailRow("地点", course.location.ifBlank { "未提供" }, tone)
                    DetailRow("教师", course.teacher.ifBlank { "未提供" }, tone)
                    if(course.timeText.isNotBlank()) DetailRow("时间", course.timeText, tone)
                    if(course.weeksText.isNotBlank()) DetailRow("周次", course.weeksText, tone)
                    if (editable) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { onEdit(course, false) }) { Text("编辑") }
                        TextButton(onClick = { onEdit(course, true) }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        } }, confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}

@Composable
private fun DetailRow(label: String, value: String, tone: CourseTone) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = tone.text.copy(alpha = .62f))
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = tone.text)
    }
}

internal fun courseToneOrder(schedule: SemesterSchedule): Map<String, Int> = courseToneOrder(schedule.display())

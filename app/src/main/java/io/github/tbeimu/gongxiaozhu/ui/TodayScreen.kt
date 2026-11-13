package io.github.tbeimu.gongxiaozhu.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.tbeimu.gongxiaozhu.network.CampusConnectionNotice
import io.github.tbeimu.gongxiaozhu.schedule.CourseBlock
import io.github.tbeimu.gongxiaozhu.schedule.ScheduleCourse
import io.github.tbeimu.gongxiaozhu.schedule.ScheduleUiState
import io.github.tbeimu.gongxiaozhu.schedule.SemesterSchedule
import io.github.tbeimu.gongxiaozhu.schedule.courseBlocks
import io.github.tbeimu.gongxiaozhu.schedule.scheduleToday
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.abs

private val DayNames = listOf("一", "二", "三", "四", "五", "六", "日")
private val AgendaShape = RoundedCornerShape(20.dp)

/**
 * The schedule tab. What a student opens it for is today: what is on now, what is next, where.
 * So today comes first as a timeline; the full week grid is one tap away under 整周.
 */
@Composable
internal fun ScheduleHome(state: ScheduleUiState, modifier: Modifier, onImport: () -> Unit, onPause: () -> Unit,
    onRetry: () -> Unit, onMine: () -> Unit, todayProvider: () -> LocalDate = ::scheduleToday,
    connectionNotice: CampusConnectionNotice? = null, titleUnobstructed: Boolean = true, onCancelImport: () -> Unit = {},
    onDialogVisibilityChanged: (Boolean) -> Unit = {}, nowProvider: () -> LocalTime = ::scheduleNow) {
    var week by rememberSaveable { mutableStateOf(false) }
    val schedule = state.data.complete
    // Switched in one frame, never cross-faded: only one view may own the campus notice title.
    Box(modifier.fillMaxSize()) {
        // While the encrypted timetable is being read: the mark, not an empty page that flickers.
        if (state.loading) { BrandLoader(Modifier.size(64.dp).align(Alignment.Center)); return@Box }
        if (!week && schedule != null && !state.blocked) TodayScreen(state, schedule, onImport, onPause, onCancelImport,
            todayProvider, nowProvider, connectionNotice, titleUnobstructed, onDialogVisibilityChanged)
        else ScheduleScreen(state, Modifier.fillMaxSize(), onImport, onPause, onRetry, onMine, todayProvider, connectionNotice,
            titleUnobstructed, onCancelImport, onDialogVisibilityChanged, nowProvider)
        if (schedule != null && !state.blocked) ModeSwitch(week, Modifier.align(Alignment.BottomEnd)
            .padding(end = 20.dp, bottom = 18.dp)) { week = !week }
    }
}

@Composable
private fun ModeSwitch(week: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val palette = gxz
    val interaction = remember { MutableInteractionSource() }
    Row(modifier.pressScale(interaction, .94f).glass(palette, CircleShape, strong = true)
        .clickable(interaction, LocalIndication.current, role = Role.Button, onClick = onClick)
        .heightIn(min = 44.dp).padding(start = 14.dp, end = 18.dp).testTag("schedule_mode")
        .semantics { contentDescription = if (week) "切换到今日" else "查看整周课表" },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        LineGlyph(if (week) AppGlyph.WEEKS else AppGlyph.GRID, Modifier.size(17.dp), palette.ink)
        Text(if (week) "今日" else "整周", style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp), color = palette.ink)
    }
}

/** An icon with no disc behind it; presses with a small spring. */
@Composable
internal fun PlainIconButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    content: @Composable () -> Unit) {
    val palette = gxz
    val interaction = remember { MutableInteractionSource() }
    IconButton(onClick = onClick, enabled = enabled, modifier = modifier, interactionSource = interaction) {
        Box(Modifier.pressScale(interaction, .86f), contentAlignment = Alignment.Center) {
            CompositionLocalProvider(LocalContentColor provides if (enabled) palette.ink else palette.inkFaint.copy(alpha = .5f)) { content() }
        }
    }
}

private enum class CardState { PAST, LIVE, NEXT, AHEAD }

@Composable
private fun TodayScreen(state: ScheduleUiState, schedule: SemesterSchedule, onImport: () -> Unit, onPause: () -> Unit,
    onCancelImport: () -> Unit, todayProvider: () -> LocalDate, nowProvider: () -> LocalTime,
    notice: CampusConnectionNotice?, titleUnobstructed: Boolean, onDialogVisibilityChanged: (Boolean) -> Unit) {
    val today = rememberScheduleDate(todayProvider)
    val now by rememberScheduleTime(nowProvider)
    val palette = gxz
    val toneOrder = remember(schedule) { courseToneOrder(schedule) }
    val termDays = remember(schedule) { schedule.weeks.flatMap { it.dates }.toSet() }
    // Today when it is in the term; otherwise the Monday of the school's current week.
    val home = today.value.toString().takeIf { it in termDays }
        ?: schedule.weeks.getOrNull(schedule.term.currentWeek - 1)?.dates?.firstOrNull() ?: schedule.weeks.first().dates.first()
    // null follows today's clock, including after restoration; explicit browsing keeps its date.
    var chosen by rememberSaveable(schedule.identity.studentId, schedule.term.key) { mutableStateOf<String?>(null) }
    val selected = LocalDate.parse(chosen?.takeIf { it in termDays } ?: home)
    val week = schedule.weeks.firstOrNull { selected.toString() in it.dates } ?: schedule.weeks.first()
    var details by remember(schedule) { mutableStateOf<List<ScheduleCourse>?>(null) }
    var cancelConfirmation by remember { mutableStateOf(false) }
    LaunchedEffect(state.canCancelImport) { if (!state.canCancelImport) cancelConfirmation = false }
    ReportDialogVisibility(details != null || (state.canCancelImport && cancelConfirmation), onDialogVisibilityChanged)
    val spin = rememberSpin(state.busy)
    val haptic = LocalHapticFeedback.current
    fun select(date: LocalDate) {
        if (date.toString() !in termDays) return
        chosen = date.toString().takeUnless { date == today.value }
        if (date != selected) haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
    }
    val blocks = remember(week, selected) { dayBlocks(schedule, selected) }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp, top = 10.dp)) {
            val isToday = selected == today.value
            Text((if (isToday) "今天 · " else "") + "${selected.monthValue}月${selected.dayOfMonth}日 · 第 ${week.number} 周",
                modifier = Modifier.testTag("schedule_overline"),
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp), color = palette.inkFaint, maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).testTag("schedule_week_title")) {
                    CampusConnectionTitle("周${DayNames[selected.dayOfWeek.value - 1]}", notice,
                        titleUnobstructed && details == null && !(state.canCancelImport && cancelConfirmation),
                        headline = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.8).sp))
                }
                val lessons = blocks.sumOf { it.last - it.first + 1 }
                Text(if (lessons == 0) "没有课" else "共 $lessons 节", style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
                    color = palette.inkFaint)
                PlainIconButton(onClick = onImport, enabled = !state.busy && !state.loading,
                    modifier = Modifier.testTag("import_schedule").semantics { contentDescription = "刷新课表" }) {
                    LineGlyph(AppGlyph.REFRESH, Modifier.size(20.dp).graphicsLayer { rotationZ = spin })
                }
            }
        }
        WeekStrip(week.dates.map(LocalDate::parse), selected, today.value, schedule, toneOrder, ::select)
        ScheduleStatus(state, onPause, onImport, onCancelImport, cancelConfirmation) {
            cancelConfirmation = it
            onDialogVisibilityChanged(it || details != null)
        }
        // A sideways swipe moves a day; the page follows in the same direction.
        val threshold = with(LocalDensity.current) { 64.dp.toPx() }
        Box(Modifier.weight(1f).fillMaxWidth().pointerInput(selected, termDays, threshold) {
            var distance = 0f
            detectHorizontalDragGestures(onDragStart = { distance = 0f }, onHorizontalDrag = { change, delta ->
                change.consume(); distance += delta
            }, onDragEnd = { if (abs(distance) >= threshold) select(selected.plusDays(if (distance < 0) 1 else -1)) })
        }) {
            AnimatedContent(selected, Modifier.fillMaxSize(), transitionSpec = {
                val forward = targetState > initialState
                (slideInHorizontally(tween(Motion.LONG, easing = Motion.Emphasized)) { if (forward) it / 4 else -it / 4 } +
                    fadeIn(tween(Motion.MEDIUM, 40))) togetherWith
                    (slideOutHorizontally(tween(Motion.MEDIUM, easing = Motion.Accelerate)) { if (forward) -it / 6 else it / 6 } +
                        fadeOut(tween(Motion.SHORT)))
            }, label = "切换日期") { date ->
                DayAgenda(date, schedule, today.value, now, toneOrder) { details = it; onDialogVisibilityChanged(true) }
            }
        }
    }
    details?.let { CourseDetails(it, toneOrder) { details = null; onDialogVisibilityChanged(cancelConfirmation && state.canCancelImport) } }
}

private fun dayBlocks(schedule: SemesterSchedule, date: LocalDate): List<CourseBlock> {
    val week = schedule.weeks.firstOrNull { date.toString() in it.dates } ?: return emptyList()
    val day = week.dates.indexOf(date.toString())
    return courseBlocks(week).filter { it.day == day }.sortedBy { it.first }
}

/** Seven days in a row; a dark tile glides to the chosen one. Dots preview each day's classes. */
@Composable
private fun WeekStrip(dates: List<LocalDate>, selected: LocalDate, today: LocalDate, schedule: SemesterSchedule,
    toneOrder: Map<String, Int>, onSelect: (LocalDate) -> Unit) {
    val palette = gxz
    BoxWithConstraints(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp)) {
        val cell = maxWidth / 7
        val index = dates.indexOf(selected).coerceAtLeast(0)
        val x by animateDpAsState(cell * index, Motion.snappy(), label = "所选日期")
        Box(Modifier.offset { IntOffset((x + 2.dp).roundToPx(), 0) }.width(cell - 4.dp).height(74.dp)
            .clip(RoundedCornerShape(18.dp)).background(palette.ink))
        Row(Modifier.fillMaxWidth()) {
            dates.forEachIndexed { day, date ->
                val chosen = date == selected
                val isToday = date == today
                val ink by animateColorAsState(when { chosen -> palette.onInk; isToday -> palette.accent; else -> palette.ink },
                    tween(Motion.MEDIUM), label = "日期文字")
                val dots = remember(schedule, date) { dayBlocks(schedule, date).take(3) }
                Column(Modifier.weight(1f).height(74.dp).clip(RoundedCornerShape(18.dp))
                    .clickable(role = Role.Tab) { onSelect(date) }.testTag("agenda_day_$day"),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text(DayNames[day], fontSize = 11.sp, lineHeight = 14.sp,
                        color = if (chosen) palette.onInk.copy(alpha = .7f) else if (isToday) palette.accent else palette.inkFaint)
                    Text("${date.dayOfMonth}", fontSize = 17.sp, lineHeight = 22.sp, color = ink,
                        fontWeight = if (chosen || isToday) FontWeight.SemiBold else FontWeight.Medium, modifier = Modifier.padding(top = 2.dp))
                    Row(Modifier.height(10.dp).padding(top = 5.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        dots.forEach { block ->
                            val tone = if (block.courses.size > 1) overlapTone(palette.dark) else courseTone(block.courses.first(), palette.dark, toneOrder)
                            Box(Modifier.size(4.dp).clip(CircleShape).background(if (chosen) palette.onInk.copy(alpha = .85f) else tone.strong))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayAgenda(date: LocalDate, schedule: SemesterSchedule, today: LocalDate, now: LocalTime,
    toneOrder: Map<String, Int>, onCourse: (List<ScheduleCourse>) -> Unit) {
    val palette = gxz
    val blocks = remember(schedule, date) { dayBlocks(schedule, date) }
    val isToday = date == today
    val next = if (isToday) blocks.firstOrNull { b -> lessonSpan(b.first, b.last)?.let { now < it.first } == true } else null
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, top = 18.dp, bottom = 96.dp)) {
        if (blocks.isEmpty()) EmptyDay(date, isToday, schedule)
        var previousEnd: LocalTime? = null
        blocks.forEachIndexed { index, block ->
            val span = lessonSpan(block.first, block.last)
            if (previousEnd != null && span != null) Gap(previousEnd!!, span.first)
            val state = when {
                span == null -> CardState.AHEAD
                date < today || (isToday && now >= span.second) -> CardState.PAST
                isToday && now >= span.first -> CardState.LIVE
                block == next -> CardState.NEXT
                else -> CardState.AHEAD
            }
            AgendaRow(block, span, state, now, toneOrder, Modifier.entrance(index * Motion.STAGGER + 40, distance = 14.dp)) { onCourse(block.courses) }
            previousEnd = span?.second
        }
        previousEnd?.let { end ->
            Text(if (isToday && now >= end) "今天的课都上完了" else "${end.format(Clock)} 之后${if (isToday) "今天" else ""}没有课了",
                Modifier.padding(start = 62.dp, top = 2.dp), fontSize = 12.sp, color = palette.inkFaint)
        }
        if (isToday) Tomorrow(date.plusDays(1), schedule, toneOrder, Modifier.entrance((blocks.size + 1) * Motion.STAGGER + 40))
    }
}

@Composable
private fun AgendaRow(block: CourseBlock, span: Pair<LocalTime, LocalTime>?, state: CardState, now: LocalTime,
    toneOrder: Map<String, Int>, modifier: Modifier, onClick: () -> Unit) {
    val palette = gxz
    val past = state == CardState.PAST
    val live = state == CardState.LIVE
    Row(modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.width(48.dp).padding(top = if (live) 20.dp else 17.dp)) {
            Text(span?.first?.format(Clock) ?: "第 ${block.first} 节", fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                color = if (past) palette.inkFaint else palette.ink, style = TextStyle(fontFeatureSettings = "tnum"))
            span?.let { Text(it.second.format(Clock), fontSize = 11.sp, color = palette.inkFaint, modifier = Modifier.padding(top = 2.dp),
                style = TextStyle(fontFeatureSettings = "tnum")) }
        }
        val overlap = block.courses.size > 1
        val course = block.courses.first()
        val tone = if (overlap) overlapTone(palette.dark) else courseTone(course, palette.dark, toneOrder)
        val interaction = remember { MutableInteractionSource() }
        Column(Modifier.weight(1f).pressScale(interaction, .97f).clip(AgendaShape)
            .background(if (past) palette.ink.copy(alpha = if (palette.dark) .06f else .045f) else tone.fill)
            .clickable(interaction, LocalIndication.current, onClick = onClick)
            .testTag("agenda_course_${block.day}_${block.first}")
            .padding(horizontal = if (live) 20.dp else 18.dp, vertical = if (live) 20.dp else 16.dp)) {
            val title = if (past) palette.inkFaint else tone.text
            val meta = if (past) palette.inkFaint else tone.text.copy(alpha = .62f)
            when (state) {
                CardState.LIVE -> Tag("● 正在上课", palette.accent)
                CardState.NEXT -> span?.let {
                    val minutes = Duration.between(now, it.first).toMinutes()
                    Tag(if (minutes <= 90) "下一节 · $minutes 分钟后" else "下一节", tone.text)
                }
                else -> Unit
            }
            Text(if (overlap) "${block.courses.size} 门课程时间重叠" else course.name, color = title,
                fontSize = if (live) 20.sp else 16.5.sp, lineHeight = if (live) 27.sp else 23.sp, fontWeight = FontWeight.SemiBold,
                // Balanced lines: a long name breaks into two even lines, never leaving one character behind.
                style = TextStyle(lineBreak = LineBreak.Heading))
            val lessons = if (block.first == block.last) "第 ${block.first} 节" else "第 ${block.first}–${block.last} 节"
            val details = if (overlap) block.courses.joinToString("、") { it.name }
                else (listOf(course.location, course.teacher).filter { it.isNotBlank() } + (if (past) "已结束" else lessons)).joinToString(" · ")
            Text(details, color = meta, fontSize = 12.5.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 6.dp),
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (live && span != null) {
                val total = Duration.between(span.first, span.second).toMinutes().coerceAtLeast(1)
                val done = Duration.between(span.first, now).toMinutes().coerceIn(0, total)
                val fraction by androidx.compose.animation.core.animateFloatAsState(done.toFloat() / total,
                    tween(900, easing = Motion.Decelerate), label = "课程进度")
                Box(Modifier.padding(top = 16.dp).fillMaxWidth().height(5.dp).clip(CircleShape).background(palette.ink.copy(alpha = .08f))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).clip(CircleShape).background(palette.ink))
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text("已上 $done 分钟", Modifier.weight(1f), fontSize = 11.5.sp, color = meta)
                    Text("还剩 ${total - done} 分钟", fontSize = 11.5.sp, color = meta)
                }
            }
        }
    }
}

@Composable
private fun Tag(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(text, Modifier.padding(bottom = 8.dp), fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = color)
}

/** The time between two classes, said plainly. */
@Composable
private fun Gap(from: LocalTime, to: LocalTime) {
    val minutes = Duration.between(from, to).toMinutes()
    if (minutes < 20) return
    val lunch = from <= LocalTime.of(12, 0) && to >= LocalTime.of(13, 0)
    val supper = from <= LocalTime.of(17, 30) && to >= LocalTime.of(18, 0)
    val what = when {
        lunch && supper -> "空闲"
        lunch -> "午休与空闲"
        supper -> "晚饭与空闲"
        else -> "空闲"
    }
    val length = if (minutes >= 60) "${minutes / 60} 小时" + (if (minutes % 60 > 0) " ${minutes % 60} 分" else "") else "$minutes 分钟"
    Text("$what · $length", Modifier.padding(start = 62.dp, top = 2.dp, bottom = 14.dp), fontSize = 12.sp, color = gxz.inkFaint)
}

@Composable
private fun EmptyDay(date: LocalDate, isToday: Boolean, schedule: SemesterSchedule) {
    val palette = gxz
    // Point to the next class, so an empty day still answers "when do I next need to be somewhere?"
    val upcoming = remember(schedule, date) {
        generateSequence(date.plusDays(1)) { it.plusDays(1) }.take(60)
            .firstNotNullOfOrNull { day -> dayBlocks(schedule, day).firstOrNull()?.let { day to it } }
    }
    Column(Modifier.fillMaxWidth().padding(top = 36.dp).entrance(40), horizontalAlignment = Alignment.CenterHorizontally) {
        TimetableMotif(Modifier.width(180.dp).height(96.dp), plain = true)
        Text(if (isToday) "今天没有课" else "这一天没有课", Modifier.padding(top = 22.dp), fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold, color = palette.ink)
        upcoming?.let { (day, block) ->
            val start = lessonSpan(block.first, block.last)?.first?.format(Clock).orEmpty()
            Text("下一节 · 周${DayNames[day.dayOfWeek.value - 1]} ${day.monthValue}月${day.dayOfMonth}日 $start · ${block.courses.first().name}",
                Modifier.padding(top = 8.dp), fontSize = 13.sp, color = palette.inkFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Tomorrow(date: LocalDate, schedule: SemesterSchedule, toneOrder: Map<String, Int>, modifier: Modifier) {
    val palette = gxz
    val blocks = remember(schedule, date) { dayBlocks(schedule, date) }
    Column(modifier.fillMaxWidth().padding(top = 18.dp)) {
        HorizontalDivider(thickness = .7.dp, color = palette.hairline)
        Text("明天 · 周${DayNames[date.dayOfWeek.value - 1]}", Modifier.padding(top = 16.dp, bottom = 6.dp),
            fontSize = 12.5.sp, color = palette.inkFaint)
        if (blocks.isEmpty()) Text("明天没有课", fontSize = 13.5.sp, color = palette.inkSoft, modifier = Modifier.padding(vertical = 6.dp))
        blocks.forEach { block ->
            val course = block.courses.first()
            val tone = if (block.courses.size > 1) overlapTone(palette.dark) else courseTone(course, palette.dark, toneOrder)
            val start = lessonSpan(block.first, block.last)?.first?.format(Clock) ?: "第 ${block.first} 节"
            Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(8.dp).clip(RoundedCornerShape(3.dp)).background(tone.strong))
                Text(if (block.courses.size > 1) "${block.courses.size} 门课程时间重叠" else course.name, Modifier.weight(1f),
                    fontSize = 13.5.sp, color = palette.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOf(start, course.location).filter { it.isNotBlank() }.joinToString(" · "), fontSize = 12.sp, color = palette.inkFaint)
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

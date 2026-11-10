package io.github.tbeimu.gongxiaozhu.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.tbeimu.gongxiaozhu.schedule.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
internal fun ScheduleScreen(state: ScheduleUiState, modifier: Modifier, onImport: () -> Unit,
    onPause: () -> Unit, onRetry: () -> Unit, onMine: () -> Unit, todayProvider: () -> LocalDate = ::scheduleToday) {
    val today = rememberScheduleDate(todayProvider)
    val schedule = state.data.complete
    var choice by remember { mutableStateOf(false) }
    var details by remember(schedule) { mutableStateOf<List<ScheduleCourse>?>(null) }
    val scope = rememberCoroutineScope()
    Column(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("课表", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                Text(schedule?.term?.title ?: "北京工业大学 · 研究生课表", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onImport, enabled = !state.busy && !state.blocked && !state.loading,
                modifier = Modifier.testTag("import_schedule")) {
                Text(if (state.data.pending != null) "继续导入" else if (schedule == null) "导入" else "刷新")
            }
        }
        Text("课表网络：${state.networkKind?.title ?: "暂不可用"} · 支持 WiFi 或移动数据", style = MaterialTheme.typography.bodySmall)
        if (state.busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(state.message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                if (!state.loading) TextButton(onClick = onPause) { Text("暂停") }
            }
        } else Text(state.message, Modifier.padding(vertical = 6.dp), style = MaterialTheme.typography.bodySmall)
        if (state.data.pending != null && !state.busy) Text("导入进度 ${state.data.pending.weeks.size}/${state.data.pending.term.weekCount} 周 · 原课表保持不变",
            style = MaterialTheme.typography.bodySmall)
        if (state.blocked) {
            Card(Modifier.fillMaxWidth().padding(vertical = 28.dp)) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("课表存储暂不可用", style = MaterialTheme.typography.titleLarge)
                    Text("可以重试读取，或在“我的”中确认清除课表后重新导入。校园网账号不会清除。")
                    Button(onClick = onRetry) { Text("重试读取课表") }
                    TextButton(onClick = onMine) { Text("前往我的") }
                }
            }
        } else if (schedule == null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(if (state.data.pending == null) "把这一学期放进口袋" else "整学期尚未导入完成", style = MaterialTheme.typography.headlineSmall)
                        Text("通过 WiFi 或移动数据登录学校页面，一次导入整个学期。完成后可离线翻周查看，课表账号独立于校园网账号。")
                        Button(onClick = onImport, enabled = !state.busy && !state.loading) {
                            Text(if (state.data.pending == null) "导入研究生课表" else "登录后继续导入")
                        }
                    }
                }
            }
        } else key(schedule.updatedAt) {
            val current = currentScheduleWeek(schedule, today.value)
            val pager = rememberPagerState(initialPage = (current ?: schedule.term.currentWeek).minus(1).coerceIn(0, schedule.weeks.lastIndex)) { schedule.weeks.size }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { if (pager.currentPage > 0) scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }, enabled = pager.currentPage > 0) { Text("‹") }
                Box(Modifier.weight(1f)) {
                    TextButton(onClick = { choice = true }) { Text("第 ${pager.currentPage + 1} 周 ▾", fontWeight = FontWeight.Bold) }
                    DropdownMenu(expanded = choice, onDismissRequest = { choice = false }, modifier = Modifier.heightIn(max = 350.dp)) {
                        schedule.weeks.forEachIndexed { index, week -> DropdownMenuItem(text = { Text("第 ${week.number} 周") }, onClick = {
                            choice = false; scope.launch { pager.animateScrollToPage(index) }
                        }) }
                    }
                }
                TextButton(onClick = {
                    today.value = todayProvider()
                    currentScheduleWeek(schedule, today.value)?.let { scope.launch { pager.animateScrollToPage(it - 1) } }
                }, enabled = current != null) { Text("本周") }
                TextButton(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }, enabled = pager.currentPage < pager.pageCount - 1) { Text("›") }
            }
            if (current == null) Text("当前日期不在已导入学期内", style = MaterialTheme.typography.bodySmall)
            HorizontalPager(state = pager, modifier = Modifier.weight(1f).testTag("schedule_pager")) { index ->
                WeekGrid(schedule.weeks[index], schedule.weeks.flatMap { it.courses }.flatMap { it.lessons }.maxOrNull() ?: 1,
                    today = today.value, onCourse = { details = it })
            }
        }
    }
    details?.let { courses ->
        AlertDialog(onDismissRequest = { details = null }, title = { Text(if (courses.size > 1) "重叠课程 · ${courses.size} 门" else "课程详情") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                courses.forEach { course ->
                    Text(course.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("${course.date} · 第 ${course.lessons.joinToString("、")} 节")
                    Text("地点：${course.location.ifBlank { "未提供" }}")
                    Text("教师：${course.teacher.ifBlank { "未提供" }}")
                    if (course.timeText.isNotBlank()) Text("时间：${course.timeText}")
                    if (course.weeksText.isNotBlank()) Text("周次：${course.weeksText}")
                }
            } }, confirmButton = { TextButton(onClick = { details = null }) { Text("关闭") } })
    }
}

@Composable
private fun WeekGrid(week: ScheduleWeek, count: Int, today: LocalDate, onCourse: (List<ScheduleCourse>) -> Unit) {
    val blocks = remember(week) { courseBlocks(week) }
    val cell = 58.dp * LocalDensity.current.fontScale.coerceIn(1f, 1.5f)
    val days = listOf("一", "二", "三", "四", "五", "六", "日")
    val todayText = today.toString()
    val palette = listOf(0xFFBEE5E0, 0xFFE6D2EF, 0xFFFFD5CB, 0xFFCADAFA, 0xFFFFE4B7, 0xFFD8E9C3)
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Box(Modifier.width(30.dp))
            week.dates.forEachIndexed { day, date -> Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(days[day], fontWeight = if (date == todayText) FontWeight.Bold else FontWeight.Normal)
                Text(LocalDate.parse(date).format(DateTimeFormatter.ofPattern("M/d")), fontSize = 10.sp,
                    color = if (date == todayText) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            } }
        }
        if (week.courses.isEmpty()) Text("本周无课 · 学校已返回空课表", Modifier.padding(bottom = 6.dp), style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            Column(Modifier.width(30.dp)) { repeat(count) { n ->
                Box(Modifier.height(cell).fillMaxWidth(), contentAlignment = Alignment.TopCenter) { Text("${n + 1}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } }
            repeat(7) { day ->
                Box(Modifier.weight(1f).height(cell * count)) {
                    blocks.filter { it.day == day }.forEach { block ->
                        val course = block.courses.first()
                        val color = Color(palette[Math.floorMod((course.id.ifBlank { course.name }).hashCode(), palette.size)])
                        Column(Modifier.offset(y = cell * (block.first - 1)).padding(horizontal = 2.dp, vertical = 2.dp)
                            .fillMaxWidth().height(cell * (block.last - block.first + 1) - 4.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(color.copy(alpha = .96f), RoundedCornerShape(9.dp)).clickable { onCourse(block.courses) }
                            .padding(horizontal = 4.dp, vertical = 7.dp)) {
                            Text(if (block.courses.size > 1) "${block.courses.size} 门重叠" else course.name, color = Color(0xFF19354D),
                                fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 15.sp,
                                maxLines = maxOf(1, (block.last - block.first + 1) * 2), overflow = TextOverflow.Ellipsis)
                            if (block.courses.size == 1 && course.location.isNotBlank()) Text(course.location, fontSize = 10.sp,
                                lineHeight = 13.sp, color = Color(0xFF34465D), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

package io.github.tbeimu.gongxiaozhu.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.tbeimu.gongxiaozhu.schedule.*
import java.time.LocalDate

/** Only normalized, unsaved course fields can be restored; school login credentials never enter this editor. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CourseEditor(context: CourseEditContext, saving: Boolean, error: String?, onDismiss: () -> Unit,
    onSave: (CourseDraft) -> Unit) {
    val course = context.course
    var name by rememberSaveable(context.token) { mutableStateOf(course?.name.orEmpty()) }
    var location by rememberSaveable(context.token) { mutableStateOf(course?.location.orEmpty()) }
    var teacher by rememberSaveable(context.token) { mutableStateOf(course?.teacher.orEmpty()) }
    var weekday by rememberSaveable(context.token) { mutableIntStateOf(context.defaultWeekday) }
    var sources by rememberSaveable(context.token) { mutableStateOf(listOf(context.defaultWeek)) }
    var targets by rememberSaveable(context.token) { mutableStateOf(listOf(context.defaultWeek).filter { it <= context.weekCount }) }
    var first by rememberSaveable(context.token) { mutableStateOf((course?.lessons?.minOrNull() ?: 1).toString()) }
    var last by rememberSaveable(context.token) { mutableStateOf((course?.lessons?.maxOrNull() ?: 2).toString()) }
    var rangeChanged by rememberSaveable(context.token) { mutableStateOf(false) }
    var monday by rememberSaveable(context.token) { mutableStateOf(context.firstMonday) }
    var count by rememberSaveable(context.token) { mutableStateOf(context.weekCount.toString()) }
    var picker by remember { mutableStateOf(false) }
    val newTerm = context.creatingTerm
    val weeks = (count.toIntOrNull() ?: 0).coerceIn(0, 60)
    val lessonStart = first.toIntOrNull()
    val lessonEnd = last.toIntOrNull()
    val validLessons = lessonStart != null && lessonEnd != null && lessonStart in 1..20 && lessonEnd in lessonStart..20
    val valid = if (context.deleteOnly) sources.isNotEmpty() else name.isNotBlank() && count.toIntOrNull() in 1..60 &&
        targets.isNotEmpty() && targets.all { it in 1..weeks } && (validLessons || (course != null && !rangeChanged)) &&
        runCatching { LocalDate.parse(monday).dayOfWeek.value == 1 }.getOrDefault(false)
    fun draft() = CourseDraft(name, location, teacher, weekday, sources, targets,
        if (course != null && !rangeChanged) course.lessons else if (validLessons) (lessonStart!!..lessonEnd!!).toList() else emptyList(), monday, weeks)

    if (picker) {
        val dateState = rememberDatePickerState(initialSelectedDateMillis = LocalDate.parse(monday).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(onDismissRequest = { picker = false }, confirmButton = {
            TextButton(onClick = {
                dateState.selectedDateMillis?.let { monday = java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneOffset.UTC)
                    .toLocalDate().with(java.time.DayOfWeek.MONDAY).toString() }; picker = false
            }) { Text("确定") }
        }, dismissButton = { TextButton(onClick = { picker = false }) { Text("取消") } }) { DatePicker(dateState) }
        return
    }
    AlertDialog(onDismissRequest = { if (!saving) onDismiss() }, modifier = Modifier.testTag("course_editor"),
        title = { Text(if (context.deleteOnly) "删除课程" else if (course == null) "添加自定义课程" else "编辑课程") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (context.deleteOnly) Text("删除“${course?.name}”的以下安排？")
                if (course != null) {
                    Text("周${listOf("一", "二", "三", "四", "五", "六", "日")[context.defaultWeekday - 1]} · ${course.lessons.joinToString("、")} 节",
                        style = MaterialTheme.typography.bodySmall, color = gxz.inkSoft)
                    Text("原安排范围", style = MaterialTheme.typography.titleSmall)
                    WeekChoices(context.availableWeeks, sources, !saving, "source_week") { sources = it }
                }
                if (course == null && !newTerm) Text("${context.firstMonday} 起 · ${context.weekCount} 周",
                    style = MaterialTheme.typography.bodySmall, color = gxz.inkSoft)
                if (!context.deleteOnly) {
                    OutlinedTextField(name, { name = it.take(300) }, label = { Text("课程名") }, singleLine = true,
                        enabled = !saving, modifier = Modifier.fillMaxWidth().testTag("course_name"), shape = TileShape)
                    OutlinedTextField(location, { location = it.take(500) }, label = { Text("教室（可选）") }, singleLine = true,
                        enabled = !saving, modifier = Modifier.fillMaxWidth().testTag("course_location"), shape = TileShape)
                    OutlinedTextField(teacher, { teacher = it.take(500) }, label = { Text("教师（可选）") }, singleLine = true,
                        enabled = !saving, modifier = Modifier.fillMaxWidth().testTag("course_teacher"), shape = TileShape)
                    if (newTerm) {
                        OutlinedButton(onClick = { picker = true }, enabled = !saving, modifier = Modifier.testTag("term_monday")) { Text("第一周周一 · $monday") }
                        OutlinedTextField(count, { count = it.filter(Char::isDigit).take(2) }, label = { Text("学期周数（1–60）") },
                            singleLine = true, enabled = !saving, modifier = Modifier.fillMaxWidth().testTag("term_weeks"), shape = TileShape,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
                    }
                    Text(if (course == null) "上课安排" else "调整后的安排", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日").forEachIndexed { index, label ->
                            FilterChip(weekday == index + 1, { weekday = index + 1 }, { Text(label) }, enabled = !saving,
                                modifier = Modifier.testTag("course_day_${index + 1}"))
                        }
                    }
                    WeekChoices((1..weeks).toList(), targets, !saving, "target_week") { targets = it }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(first, { first = it.filter(Char::isDigit).take(2); rangeChanged = true }, label = { Text("开始节次") },
                            enabled = !saving, singleLine = true, shape = TileShape, modifier = Modifier.weight(1f).testTag("course_first"),
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
                        OutlinedTextField(last, { last = it.filter(Char::isDigit).take(2); rangeChanged = true }, label = { Text("结束节次") },
                            enabled = !saving, singleLine = true, shape = TileShape, modifier = Modifier.weight(1f).testTag("course_last"),
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
                    }
                    if (course != null && !rangeChanged && course.lessons.zipWithNext().any { (a, b) -> b != a + 1 })
                        Text("原节次 ${course.lessons.joinToString("、")}，未修改区间时保留", style = MaterialTheme.typography.bodySmall)
                    if (!validLessons && rangeChanged) Text("节次须为 1–20，结束不得早于开始", color = MaterialTheme.colorScheme.error)
                }
                if (course?.source == CourseSource.SCHOOL) Text("完整刷新后以学校新课表为准", style = MaterialTheme.typography.bodySmall, color = gxz.inkSoft)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = { TextButton(onClick = { onSave(draft()) }, enabled = valid && !saving,
            modifier = Modifier.testTag("save_course")) { Text(if (saving) "正在保存" else if (context.deleteOnly) "确认删除" else "保存") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("取消") } })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WeekChoices(available: List<Int>, selected: List<Int>, enabled: Boolean, tag: String, onChange: (List<Int>) -> Unit) {
    Column {
        TextButton(onClick = { onChange(if (selected.toSet() == available.toSet()) emptyList() else available) }, enabled = enabled,
            modifier = Modifier.testTag("${tag}_all")) { Text(if (selected.toSet() == available.toSet()) "取消全选" else "全选周次") }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { available.forEach { week ->
            FilterChip(week in selected, { onChange(if (week in selected) selected - week else (selected + week).sorted()) },
                { Text("$week") }, enabled = enabled, modifier = Modifier.testTag("${tag}_$week"))
        } }
    }
}

@Composable
internal fun ScheduleAddButton(expanded: Boolean, enabled: Boolean, onExpand: (Boolean) -> Unit, onImport: () -> Unit,
    onAdd: () -> Unit) {
    Box {
        PlainIconButton({ onExpand(true) }, Modifier.testTag("import_schedule").semantics { contentDescription = "添加课表" }, enabled) {
            LineGlyph(AppGlyph.PLUS, Modifier.size(20.dp))
        }
        DropdownMenu(expanded, { onExpand(false) }, modifier = Modifier.widthIn(max = 320.dp).testTag("schedule_add_menu"), shape = TileShape,
            containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surface) {
            DropdownMenuItem(text = { Column { Text("导入本科生课表"); Text("暂未开放", style = MaterialTheme.typography.bodySmall) } },
                onClick = {}, enabled = false, leadingIcon = { LineGlyph(AppGlyph.DOWNLOAD, Modifier.size(20.dp)) }, modifier = Modifier.testTag("import_undergraduate"))
            DropdownMenuItem(text = { Text("导入研究生课表") }, onClick = { onExpand(false); onImport() },
                enabled = enabled, leadingIcon = { LineGlyph(AppGlyph.DOWNLOAD, Modifier.size(20.dp)) }, modifier = Modifier.testTag("import_graduate"))
            HorizontalDivider(Modifier.padding(horizontal = 12.dp))
            DropdownMenuItem(text = { Text("添加自定义课程") }, onClick = { onExpand(false); onAdd() }, enabled = enabled,
                leadingIcon = { LineGlyph(AppGlyph.PLUS, Modifier.size(20.dp), gxz.accent) }, modifier = Modifier.testTag("add_custom_course"))
        }
    }
}

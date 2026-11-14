package io.github.tbeimu.gongxiaozhu.schedule

import java.time.LocalDate
import java.util.UUID
import kotlinx.serialization.Serializable

@Serializable
data class CustomOccurrence(val week: Int, val weekday: Int, val lessons: List<Int>)
@Serializable
data class CustomCourse(val id: String, val name: String, val location: String = "", val teacher: String = "",
    val occurrences: List<CustomOccurrence>)
@Serializable
data class LocalScheduleTerm(val id: String, val firstMonday: String, val weekCount: Int,
    val title: String = "本地课表", val studentId: String? = null, val schoolTermKey: String? = null,
    val adoptOnImport: Boolean = true, val courses: List<CustomCourse> = emptyList())

/** A display calendar need not have a verified school identity. */
data class DisplayTerm(val key: String, val title: String, val weekCount: Int, val currentWeek: Int)
data class DisplaySchedule(val key: String, val term: DisplayTerm, val weeks: List<ScheduleWeek>,
    val localTermId: String?, val school: Boolean)

fun ScheduleData.display(today: LocalDate = scheduleToday()): DisplaySchedule? {
    val selected = localTerms.find { it.id == selectedLocalTermId }
    val school = complete?.takeIf { selected == null ||
        (selected.studentId == it.identity.studentId && selected.schoolTermKey == it.term.key) }
    val local = if (school != null) localTerms.find {
        it.studentId == school.identity.studentId && it.schoolTermKey == school.term.key
    } else selected ?: localTerms.lastOrNull()
    if (school == null && local == null) return null
    val count = school?.term?.weekCount ?: local!!.weekCount
    val monday = LocalDate.parse(school?.weeks?.first()?.dates?.first() ?: local!!.firstMonday)
    val custom = local?.courses.orEmpty().flatMap { c -> c.occurrences.filter { it.week <= count }.map { o ->
        ScheduleCourse(c.id, c.name, monday.plusWeeks(o.week - 1L).plusDays(o.weekday - 1L).toString(),
            o.lessons, c.location, c.teacher, weeksText = "第 ${o.week} 周", source = CourseSource.CUSTOM)
    } }
    val weeks = (1..count).map { number ->
        val dates = (0L..6L).map { monday.plusWeeks(number - 1L).plusDays(it).toString() }
        ScheduleWeek(number, dates, school?.weeks?.get(number - 1)?.courses.orEmpty() + custom.filter { it.date in dates })
    }
    val current = weeks.firstOrNull { today.toString() in it.dates }?.number ?: school?.term?.currentWeek ?: 1
    val key = school?.let { "school/${it.identity.studentId}/${it.term.key}" } ?: "local/${local!!.id}"
    return DisplaySchedule(key, DisplayTerm(key, school?.term?.title ?: local!!.title, count, current),
        weeks, local?.id, school != null)
}

fun SemesterSchedule.display(): DisplaySchedule = ScheduleData(identity = identity, complete = this).display()!!
fun currentScheduleWeek(schedule: DisplaySchedule, today: LocalDate = scheduleToday()): Int? =
    schedule.weeks.firstOrNull { today.toString() in it.dates }?.number

class CourseValidationException(message: String) : Exception(message)

/** Captures the original data; saving can never apply an old draft to a newly imported semester. */
data class CourseEditContext(val token: String = UUID.randomUUID().toString(), val expected: ScheduleData,
    val localTermId: String?, val course: ScheduleCourse?, val availableWeeks: List<Int>,
    val defaultWeek: Int, val defaultWeekday: Int, val firstMonday: String, val weekCount: Int,
    val deleteOnly: Boolean = false, val creatingTerm: Boolean = false)
data class CourseDraft(val name: String, val location: String, val teacher: String, val weekdays: List<Int>,
    val sourceWeeks: List<Int>, val targetWeeks: List<Int>, val lessons: List<Int>,
    val firstMonday: String, val weekCount: Int)

private fun ScheduleCourse.sameSeries(other: ScheduleCourse): Boolean = source == other.source && id == other.id &&
    name == other.name && location == other.location && teacher == other.teacher && lessons == other.lessons &&
    LocalDate.parse(date).dayOfWeek == LocalDate.parse(other.date).dayOfWeek

fun ScheduleData.courseEditor(course: ScheduleCourse? = null, week: Int = 1, weekday: Int = scheduleToday().dayOfWeek.value,
    termId: String? = null, deleteOnly: Boolean = false, newLocalTerm: Boolean = false): CourseEditContext {
    val local = localTerms.find { it.id == termId }
    val view = if (newLocalTerm) null else if (local != null) copy(selectedLocalTermId = local.id).display() else display()
    val weekNumber = course?.let { c -> if (local != null) (java.time.temporal.ChronoUnit.DAYS.between(
        LocalDate.parse(local.firstMonday), LocalDate.parse(c.date)) / 7).toInt() + 1
        else view?.weeks?.find { c.date in it.dates }?.number } ?: week
    val available = course?.let { c -> if (c.source == CourseSource.CUSTOM && local != null)
        local.courses.find { it.id == c.id }?.occurrences.orEmpty().filter { it.weekday == weekday && it.lessons == c.lessons }.map { it.week }.distinct()
        else view?.weeks.orEmpty().filter { w -> w.courses.any { it.sameSeries(c) } }.map { it.number } }
        ?: emptyList()
    val count = view?.term?.weekCount ?: 20
    return CourseEditContext(expected = this, localTermId = local?.id ?: view?.localTermId, course = course,
        availableWeeks = available, defaultWeek = if (course == null) weekNumber.coerceIn(1, count) else weekNumber,
        defaultWeekday = course?.let { LocalDate.parse(it.date).dayOfWeek.value } ?: weekday,
        firstMonday = view?.weeks?.first()?.dates?.first() ?: scheduleToday().with(java.time.DayOfWeek.MONDAY).toString(),
        weekCount = count, deleteOnly = deleteOnly, creatingTerm = newLocalTerm || view == null)
}

internal fun validateLocalTerms(data: ScheduleData) {
    require(data.localTerms.size <= 100 && data.localTerms.map { it.id }.distinct().size == data.localTerms.size)
    require(data.selectedLocalTermId == null || data.localTerms.any { it.id == data.selectedLocalTermId })
    data.localTerms.forEach { term ->
        require(term.id.isNotBlank() && term.id.length <= 128 && term.weekCount in 1..60 && term.title.length <= 200)
        require(LocalDate.parse(term.firstMonday).dayOfWeek.value == 1)
        require((term.studentId == null) == (term.schoolTermKey == null))
        require(term.studentId == null || (term.studentId.isNotBlank() && term.studentId.length <= 128 && term.schoolTermKey!!.length <= 100))
        require(term.courses.size <= 1000 && term.courses.map { it.id }.distinct().size == term.courses.size)
        term.courses.forEach { c ->
            require(c.id.isNotBlank() && c.id.length <= 128 && c.name.isNotBlank() && c.name.length <= 300)
            require(c.location.length <= 500 && c.teacher.length <= 500)
            require(c.occurrences.isNotEmpty() && c.occurrences.size <= 420 && c.occurrences.distinct().size == c.occurrences.size)
            c.occurrences.forEach { o -> require(o.week in 1..60 && o.weekday in 1..7 && o.lessons.isNotEmpty() &&
                o.lessons.size <= 20 && o.lessons.all { it in 1..20 } && o.lessons.distinct().size == o.lessons.size) }
        }
    }
}

internal fun ScheduleData.editCourse(context: CourseEditContext, draft: CourseDraft): ScheduleData {
    fun check(value: Boolean, message: String) { if (!value) throw CourseValidationException(message) }
    check(this == context.expected, "课表已变化，请关闭后重新编辑")
    val source = context.course
    check(source == null || (draft.sourceWeeks.isNotEmpty() && draft.sourceWeeks.all { it in context.availableWeeks }), "请选择原安排范围")
    if (!context.deleteOnly) {
        check(draft.name.isNotBlank() && draft.name.length <= 300, "请填写课程名（最多 300 字）")
        check(draft.location.length <= 500 && draft.teacher.length <= 500, "教室或教师信息过长")
        check(draft.weekdays.isNotEmpty() && draft.weekdays.all { it in 1..7 } &&
            draft.weekdays.distinct().size == draft.weekdays.size && draft.weekCount in 1..60, "请选择有效星期和学期周数")
        check(source?.source != CourseSource.SCHOOL || draft.weekdays.size == 1, "学校课程调课请选择一个星期")
        check(draft.targetWeeks.isNotEmpty() && draft.targetWeeks.all { it in 1..draft.weekCount }, "请选择有效上课周次")
        check(draft.lessons.isNotEmpty() && draft.lessons.distinct().size == draft.lessons.size && draft.lessons.all { it in 1..20 }, "节次范围须为第 1–20 节")
        check(context.creatingTerm || (draft.firstMonday == context.firstMonday && draft.weekCount == context.weekCount), "学期已变化，请重新编辑")
    }
    if (source?.source == CourseSource.SCHOOL) {
        val school = complete ?: throw CourseValidationException("学校课表已变化，请重新编辑")
        check(draft.weekCount == school.term.weekCount, "学校学期已变化，请重新编辑")
        val monday = LocalDate.parse(school.weeks.first().dates.first())
        val weeks = school.weeks.map { w -> w.copy(courses = w.courses.filterNot {
            w.number in draft.sourceWeeks && it.sameSeries(source)
        } + if (!context.deleteOnly && w.number in draft.targetWeeks) listOf(source.copy(name = draft.name.trim(),
            location = draft.location.trim(), teacher = draft.teacher.trim(), lessons = draft.lessons.sorted(),
            date = monday.plusWeeks(w.number - 1L).plusDays(draft.weekdays.single() - 1L).toString(), timeText = "", weeksText = "第 ${w.number} 周")) else emptyList()) }
        return copy(complete = school.copy(weeks = weeks))
    }
    var local = localTerms.find { it.id == context.localTermId }
    if (local == null) {
        check(source == null, "本地课程已变化，请重新编辑")
        val monday = runCatching { LocalDate.parse(draft.firstMonday) }.getOrNull()
        check(monday?.dayOfWeek?.value == 1, "请选择第一周的周一日期")
        val school = complete?.takeIf { selectedLocalTermId == null && !context.creatingTerm }
        local = LocalScheduleTerm(UUID.randomUUID().toString(), draft.firstMonday, draft.weekCount,
            title = school?.term?.title ?: "本地课表", studentId = school?.identity?.studentId,
            schoolTermKey = school?.term?.key, adoptOnImport = complete == null && identity == null && localTerms.isEmpty())
    } else check(draft.weekCount == local.weekCount && draft.firstMonday == local.firstMonday, "学期已变化，请重新编辑")
    val old = local.courses.find { it.id == source?.id }
    check(source == null || old != null, "本地课程已变化，请重新编辑")
    val retained = old?.occurrences.orEmpty().filterNot { o -> o.week in draft.sourceWeeks &&
        o.weekday == context.defaultWeekday && o.lessons == source?.lessons }
    val remaining = local.courses.filterNot { it.id == old?.id } + if (retained.isNotEmpty()) listOf(old!!.copy(occurrences = retained)) else emptyList()
    val added = if (context.deleteOnly) emptyList() else draft.targetWeeks.distinct().sorted().flatMap { week ->
        draft.weekdays.sorted().map { day -> CustomOccurrence(week, day, draft.lessons.sorted()) }
    }
    // Adding another weekday to an unchanged course may meet an existing occurrence; keep it only once.
    val sameContent = old != null && old.name == draft.name.trim() && old.location == draft.location.trim() && old.teacher == draft.teacher.trim()
    val courses = if (!context.deleteOnly && sameContent) local.courses.filterNot { it.id == old!!.id } +
        old!!.copy(occurrences = (retained + added).distinct().sortedWith(compareBy({ it.week }, { it.weekday }, { it.lessons.first() })))
    else remaining + if (context.deleteOnly) emptyList() else listOf(CustomCourse(
        if (retained.isEmpty() && old != null) old.id else UUID.randomUUID().toString(), draft.name.trim(), draft.location.trim(),
        draft.teacher.trim(), added))
    val next = local.copy(courses = courses)
    return copy(localTerms = localTerms.filterNot { it.id == next.id } + next,
        selectedLocalTermId = if (complete == null || selectedLocalTermId != null || context.creatingTerm) next.id else null)
}

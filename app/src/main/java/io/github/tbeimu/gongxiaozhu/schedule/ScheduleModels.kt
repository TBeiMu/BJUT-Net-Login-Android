package io.github.tbeimu.gongxiaozhu.schedule

import java.time.LocalDate
import kotlinx.serialization.Serializable

@Serializable
data class ScheduleIdentity(val studentId: String, val name: String = "") {
    override fun toString() = "ScheduleIdentity(<redacted>)"
}
@Serializable
data class ScheduleTerm(val year: String, val term: String, val weekCount: Int, val currentWeek: Int) {
    val key: String get() = "$year/$term"
    val title: String get() = "$year 学年 · 第 $term 学期"
}
@Serializable
data class ScheduleCourse(val id: String, val name: String, val date: String, val lessons: List<Int>,
    val location: String = "", val teacher: String = "", val timeText: String = "", val weeksText: String = "") {
    override fun toString() = "ScheduleCourse(<redacted>)"
}
@Serializable
data class ScheduleWeek(val number: Int, val dates: List<String>, val courses: List<ScheduleCourse>)
@Serializable
data class SemesterSchedule(val identity: ScheduleIdentity, val term: ScheduleTerm, val weeks: List<ScheduleWeek>, val updatedAt: Long)
@Serializable
data class PendingSchedule(val identity: ScheduleIdentity, val term: ScheduleTerm, val weeks: List<ScheduleWeek> = emptyList())
@Serializable
data class ScheduleData(val schema: Int = 1, val identity: ScheduleIdentity? = null,
    val complete: SemesterSchedule? = null, val pending: PendingSchedule? = null) {
    override fun toString() = "ScheduleData(schema=$schema, data=<redacted>)"
}

enum class ScheduleProblem(val hint: String) {
    CONNECTION("课表服务无法连接，请确认 WiFi 或移动数据可上网后手动重试"),
    LOGIN("学校登录未完成，请在页面检查信息后重新提交"),
    EXPIRED("学校登录态已失效，请重新登录后继续导入"),
    IDENTITY("未能确认学校返回的学号，已停止导入；请记录此提示以便适配"),
    ACCOUNT_MISMATCH("学校返回的学号与已绑定课表账号不一致，请先在“我的”确认更换课表账号"),
    RESPONSE("课表响应无法识别，已保留原课表；请记录此提示以便适配学校接口"),
    ADDRESS("学校页面出现未支持的地址，已停止请求"),
    STORAGE("课表加密数据无法读取或写入，请重试；确认清除后才能重新导入"),
    WEBVIEW("请更新系统 WebView 后再使用学校网页登录"),
}
class ScheduleException(val problem: ScheduleProblem) : Exception(problem.hint)

class ScheduleLoginPage(val token: String, val baseUrl: String, val html: String) {
    override fun toString() = "ScheduleLoginPage(<memory-only>)"
}
data class ScheduleResource(val bytes: ByteArray, val mime: String)
sealed interface ScheduleGate {
    class Login(val page: ScheduleLoginPage) : ScheduleGate
    class Ready(val identity: ScheduleIdentity, val term: ScheduleTerm) : ScheduleGate
}
interface ScheduleSession {
    suspend fun open(): ScheduleGate
    suspend fun submit(token: String, values: Map<String, String>): ScheduleGate
    suspend fun week(term: ScheduleTerm, number: Int): ScheduleWeek
    suspend fun resource(token: String, url: String): ScheduleResource?
    fun close()
    /** After close(), wait for HTTP, DNS and login-page resource cleanup without blocking the UI. */
    suspend fun awaitClosed() {}
}

internal fun validateTerm(term: ScheduleTerm) {
    if (!Regex("\\d{4}-\\d{4}").matches(term.year) || term.term !in listOf("1", "2", "3") ||
        term.weekCount !in 1..60 || term.currentWeek !in 1..term.weekCount) throw ScheduleException(ScheduleProblem.RESPONSE)
}
internal fun validateWeek(week: ScheduleWeek) {
    try {
        require(week.number in 1..60 && week.dates.size == 7 && week.courses.size <= 500)
        val dates = week.dates.map(LocalDate::parse)
        require(dates.zipWithNext().all { (a, b) -> a.plusDays(1) == b })
        require(dates.first().dayOfWeek.value == 1)
        week.courses.forEach {
            require(it.id.length <= 256 && it.name.isNotBlank() && it.name.length <= 300 && it.date in week.dates)
            require(it.lessons.isNotEmpty() && it.lessons.size <= 20 && it.lessons.all { n -> n in 1..20 })
            require(listOf(it.location, it.teacher, it.timeText, it.weeksText).all { v -> v.length <= 500 })
        }
    } catch (_: Exception) { throw ScheduleException(ScheduleProblem.RESPONSE) }
}

/** A course can occupy several separate blocks; touching but different lessons never merge. */
data class CourseBlock(val day: Int, val first: Int, val last: Int, val courses: List<ScheduleCourse>)
fun courseBlocks(week: ScheduleWeek): List<CourseBlock> = week.dates.flatMapIndexed { day, date ->
    val spans = week.courses.filter { it.date == date }.flatMap { course ->
        val lessons = course.lessons.distinct().sorted()
        val groups = mutableListOf<MutableList<Int>>()
        lessons.forEach { lesson ->
            if (groups.isEmpty() || groups.last().last() + 1 != lesson) groups += mutableListOf(lesson)
            else groups.last() += lesson
        }
        groups.map { CourseBlock(day, it.first(), it.last(), listOf(course)) }
    }.sortedBy { it.first }
    val merged = mutableListOf<CourseBlock>()
    spans.forEach { span ->
        val last = merged.lastOrNull()
        if (last != null && span.first <= last.last) {
            merged[merged.lastIndex] = last.copy(last = maxOf(last.last, span.last), courses = (last.courses + span.courses).distinct())
        } else merged += span
    }
    merged
}

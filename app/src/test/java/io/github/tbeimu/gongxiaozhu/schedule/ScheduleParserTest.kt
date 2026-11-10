package io.github.tbeimu.gongxiaozhu.schedule

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import okhttp3.HttpUrl.Companion.toHttpUrl

internal val termResponse = """{"e":0,"d":{"params":{"year":"2026-2027","term":1,"week":2},"termInfo":[{"year":"2026-2027","term":1,"countweek":2}]}}"""
internal val identityResponse = """{"e":0,"d":{"base":{"realname":"虚构同学","role":{"number":"graduate-demo"}}}}"""
internal fun weekResponse(number: Int): String = buildJsonObject {
    put("e", 0); putJsonObject("d") {
        put("weekdays", JsonArray(demoWeek(number).dates.map(::JsonPrimitive)))
        put("classes", JsonArray((0..6).map { day -> JsonArray(listOf(if (day == 0) JsonArray(listOf(buildJsonObject {
            put("course_name", "虚构课程 & + 中文"); put("course_id", "demo")
            put("lessArr", JsonArray(listOf(JsonPrimitive("1"), JsonPrimitive(2))))
            put("location", "测试教室"); put("teacher", "测试教师")
        })) else JsonArray(emptyList()))) }))
    }
}.toString()

class ScheduleParserTest {
    @Test fun onlyKnownSchoolHttpsOriginsAreAccepted() {
        listOf("http://cas.bjut.edu.cn/login", "https://cas.bjut.edu.cn.evil.example/login", "https://example.com/login", "https://user:password@cas.bjut.edu.cn/login")
            .forEach { assertFalse(allowedScheduleUrl(it.toHttpUrl())) }
    }
    @Test fun verifiedIdentityAndUnmodifiedAcademicYearAreParsedFromSchoolFields() {
        assertEquals(demoIdentity, ScheduleParser.identity(identityResponse, "graduate-demo"))
        assertEquals(demoTerm, ScheduleParser.term(termResponse))
        assertThrows(ScheduleException::class.java) { ScheduleParser.identity(identityResponse, "another-demo") }
        assertThrows(ScheduleException::class.java) { ScheduleParser.identity("""{"e":0,"d":{"base":{"uid":123}}}""", null) }
    }
    @Test fun weekGridKeepsSpecialCharactersAndExplicitLessonNumbers() {
        val week = ScheduleParser.week(weekResponse(1), 1)
        assertEquals(demoWeek(1).dates, week.dates)
        assertEquals(listOf(1, 2), week.courses.single().lessons)
        assertEquals("虚构课程 & + 中文", week.courses.single().name)
    }
    @Test fun emptyWeekDiffersFromMissingOrUnrecognizedCourseData() {
        val response = Json.parseToJsonElement(weekResponse(1)).jsonObject
        val d = response["d"]!!.jsonObject
        val empty = JsonObject(response + ("d" to JsonObject(d + ("classes" to JsonObject(emptyMap())))))
        assertTrue(ScheduleParser.week(empty.toString(), 1).courses.isEmpty())
        val missing = JsonObject(response + ("d" to JsonObject(d - "classes")))
        assertThrows(ScheduleException::class.java) { ScheduleParser.week(missing.toString(), 1) }
        val bad = weekResponse(1).replace("\"lessArr\":[\"1\",2]", "\"lessArr\":[99]")
        assertThrows(ScheduleException::class.java) { ScheduleParser.week(bad, 1) }
    }
    @Test fun failedSessionOrMalformedTermNeverBecomesEmptySchedule() {
        assertEquals(ScheduleProblem.EXPIRED, assertThrows(ScheduleException::class.java) { ScheduleParser.term("""{"e":10013,"d":{}}""") }.problem)
        assertThrows(ScheduleException::class.java) { ScheduleParser.term(termResponse.replace("\"countweek\":2", "\"countweek\":999")) }
        assertThrows(ScheduleException::class.java) { ScheduleParser.week("<html>CAS Login</html>", 1) }
    }
}

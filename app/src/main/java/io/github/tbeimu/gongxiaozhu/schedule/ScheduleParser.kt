package io.github.tbeimu.gongxiaozhu.schedule

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.*

object ScheduleParser {
    private fun data(text: String): JsonObject {
        val root = try { Json.parseToJsonElement(text).jsonObject } catch (_: Exception) { throw ScheduleException(ScheduleProblem.RESPONSE) }
        if (root["e"].scalar() != "0") throw ScheduleException(if (root["e"].scalar() == "10013") ScheduleProblem.EXPIRED else ScheduleProblem.RESPONSE)
        return root["d"] as? JsonObject ?: throw ScheduleException(ScheduleProblem.RESPONSE)
    }
    fun identity(text: String, submittedUsername: String?): ScheduleIdentity {
        val d = data(text)
        val base = d["base"] as? JsonObject
        val candidates = listOfNotNull(base?.get("role") as? JsonObject, base, d, d["userInfo"] as? JsonObject, d["userinfo"] as? JsonObject, d["user"] as? JsonObject)
        val explicit = candidates.flatMap { obj -> listOf("studentId", "student_id", "number").map { obj[it].scalar() } }.filter { it.isNotBlank() }.distinct()
        val fallback = candidates.map { it["username"].scalar() }.filter { it.isNotBlank() }.distinct()
        val ids = explicit.ifEmpty { fallback }
        val id = ids.singleOrNull()?.trim() ?: throw ScheduleException(ScheduleProblem.IDENTITY)
        if (!Regex("[A-Za-z0-9._@-]{1,128}").matches(id) || (submittedUsername != null && id != submittedUsername.trim()))
            throw ScheduleException(ScheduleProblem.IDENTITY)
        val nameContainers = listOfNotNull(base, d, d["userInfo"] as? JsonObject, d["userinfo"] as? JsonObject, d["user"] as? JsonObject)
        val name = base?.get("realname").scalar().takeIf { it.isNotBlank() } ?: nameContainers.firstNotNullOfOrNull { obj ->
            listOf("name", "realname", "realName").map { obj[it].scalar() }.firstOrNull { it.isNotBlank() }
        }.orEmpty()
        if (name.length > 100) throw ScheduleException(ScheduleProblem.IDENTITY)
        return ScheduleIdentity(id, name)
    }

    fun term(text: String): ScheduleTerm {
        val d = data(text)
        val params = d["params"] as? JsonObject ?: throw ScheduleException(ScheduleProblem.RESPONSE)
        val terms = d["termInfo"] as? JsonArray ?: throw ScheduleException(ScheduleProblem.RESPONSE)
        val year = params["year"].scalar(); val term = params["term"].scalar()
        val selected = terms.mapNotNull { it as? JsonObject }.singleOrNull { it["year"].scalar() == year && it["term"].scalar() == term }
            ?: throw ScheduleException(ScheduleProblem.RESPONSE)
        return ScheduleTerm(year, term, selected["countweek"].intValue(), params["week"].intValue()).also(::validateTerm)
    }

    fun week(text: String, number: Int): ScheduleWeek {
        val d = data(text)
        val rawDates = d["weekdays"] as? JsonArray ?: throw ScheduleException(ScheduleProblem.RESPONSE)
        val dates = rawDates.map { date(it.scalar()) }
        if (dates.size != 7) throw ScheduleException(ScheduleProblem.RESPONSE)
        val classes = d["classes"] ?: throw ScheduleException(ScheduleProblem.RESPONSE)
        val courses = mutableListOf<ScheduleCourse>()
        fun course(obj: JsonObject, day: Int?) {
            val name = obj["course_name"].scalar()
            if (name.isBlank()) throw ScheduleException(ScheduleProblem.RESPONSE)
            val explicitDate = obj["date"].scalar().takeIf { it.isNotBlank() }?.let(::date)
            val weekday = obj["weekday"].scalar().toIntOrNull()?.let { if (it == 0) 6 else it - 1 }
            val index = day ?: weekday ?: explicitDate?.let(dates::indexOf) ?: throw ScheduleException(ScheduleProblem.RESPONSE)
            if (index !in 0..6 || (explicitDate != null && explicitDate != dates[index])) throw ScheduleException(ScheduleProblem.RESPONSE)
            val lessons = (obj["lessArr"] as? JsonArray)?.map { it.intValue() } ?: parseLessons(obj["lessons"].scalar())
            val id = obj["course_id"].scalar().ifBlank { name }
            courses += ScheduleCourse(id, name, dates[index], lessons.distinct().sorted(), obj["location"].scalar(),
                obj["teacher"].scalar(), obj["course_time"].scalar(), obj["week"].scalar())
        }
        fun flatten(value: JsonElement, day: Int?, depth: Int = 0) {
            if (depth > 4 || courses.size > 500) throw ScheduleException(ScheduleProblem.RESPONSE)
            when (value) {
                is JsonArray -> value.forEach { flatten(it, day, depth + 1) }
                is JsonObject -> if ("course_name" in value) course(value, day) else value.values.forEach { flatten(it, day, depth + 1) }
                else -> throw ScheduleException(ScheduleProblem.RESPONSE)
            }
        }
        when (classes) {
            is JsonArray -> when {
                classes.isEmpty() -> Unit
                classes.all { it is JsonObject && "course_name" in it } -> flatten(classes, null)
                classes.size == 7 -> classes.forEachIndexed { day, items -> flatten(items, day) }
                else -> throw ScheduleException(ScheduleProblem.RESPONSE)
            }
            is JsonObject -> {
                if (classes.isNotEmpty()) {
                    val keys = classes.keys
                    val zero = keys == (0..6).map(Int::toString).toSet()
                    val one = keys == (1..7).map(Int::toString).toSet()
                    when {
                        zero || one -> (0..6).forEach { day -> flatten(classes.getValue((day + if (one) 1 else 0).toString()), day) }
                        keys.all { it in dates } -> classes.forEach { (key, items) -> flatten(items, dates.indexOf(key)) }
                        else -> flatten(classes, null)
                    }
                }
            }
            else -> throw ScheduleException(ScheduleProblem.RESPONSE)
        }
        return ScheduleWeek(number, dates, courses.distinct()).also(::validateWeek)
    }

    private fun parseLessons(value: String): List<Int> {
        val stripped = value.removePrefix("第").removeSuffix("节").trim()
        if (!Regex("\\d+(?:\\s*[-~至、,，]\\s*\\d+)*").matches(stripped)) throw ScheduleException(ScheduleProblem.RESPONSE)
        return stripped.split(Regex("[、,，]")).flatMap { block ->
            val ends = block.split(Regex("[-~至]")).map { it.trim().toIntOrNull() ?: throw ScheduleException(ScheduleProblem.RESPONSE) }
            if (ends.any { it !in 1..20 } || ends.size > 2 || ends.first() > ends.last()) throw ScheduleException(ScheduleProblem.RESPONSE)
            (ends.first()..ends.last()).toList()
        }
    }
    private fun date(value: String): String = try { LocalDate.parse(value.replace('/', '-'), DateTimeFormatter.ofPattern("uuuu-M-d").withResolverStyle(java.time.format.ResolverStyle.STRICT)).toString() }
        catch (_: Exception) { throw ScheduleException(ScheduleProblem.RESPONSE) }
    private fun JsonElement?.scalar(): String = (this as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonElement?.intValue(): Int = scalar().toIntOrNull() ?: throw ScheduleException(ScheduleProblem.RESPONSE)
}

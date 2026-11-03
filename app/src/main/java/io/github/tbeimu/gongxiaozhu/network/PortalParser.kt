package io.github.tbeimu.gongxiaozhu.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

object PortalParser {
    private val json = Json

    fun result(text: String): Boolean {
        val objectValue = parseJson(text) ?: throw PortalException(PortalProblem.RESPONSE)
        return when ((objectValue["result"] as? JsonPrimitive)?.contentOrNull) {
            "1" -> true
            "0" -> false
            else -> throw PortalException(PortalProblem.RESPONSE)
        }
    }

    fun status(text: String): Boolean? = try { result(text) } catch (_: PortalException) { null }

    private fun parseJson(text: String): JsonObject? = runCatching {
        val trimmed = text.trim().removePrefix("\uFEFF")
        val payload = if (trimmed.startsWith("{")) trimmed else {
            // Never evaluate JavaScript; accept only a single JSONP wrapper.
            Regex("[a-zA-Z_$][a-zA-Z0-9_$]*\\s*\\(([\\s\\S]*)\\)\\s*;?")
                .matchEntire(trimmed)?.groupValues?.get(1) ?: return null
        }
        json.parseToJsonElement(payload) as? JsonObject
    }.getOrNull()

    fun ipv4Status(text: String): Boolean? = when {
        text.contains("<!--Dr.COMWebLoginID_1.htm-->") -> true
        text.contains("<!--Dr.COMWebLoginID_0.htm-->") &&
            Regex("(?:name\\s*=\\s*[\"']?DDDDD|DDDDD\\s*:)", RegexOption.IGNORE_CASE).containsMatchIn(text) -> false
        else -> null
    }

    fun ipv4Accepted(text: String) = text.contains("<!--Dr.COMWebLoginID_3.htm-->")
}

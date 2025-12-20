/*
 * Strata - Kotlin Multiplatform client.
 * Copyright (c) 2025.
 */
package org.strata.ai

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * Lightweight JSON encoder for Map<String, Any?> payloads used by Gemini requests.
 */
internal object JsonMapEncoder {
    private val json = Json

    /**
     * Encodes a map into a JSON string using a permissive value conversion.
     */
    fun encodeToString(value: Map<String, Any?>): String {
        val element = toJsonElement(value)
        return json.encodeToString(JsonElement.serializer(), element)
    }

    private fun toJsonElement(value: Any?): JsonElement =
        when (value) {
            null -> JsonNull
            is String -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is Map<*, *> ->
                buildJsonObject {
                    value.forEach { (k, v) ->
                        if (k is String) put(k, toJsonElement(v))
                    }
                }
            is List<*> ->
                buildJsonArray {
                    value.forEach { add(toJsonElement(it)) }
                }
            else -> JsonPrimitive(value.toString())
        }
}

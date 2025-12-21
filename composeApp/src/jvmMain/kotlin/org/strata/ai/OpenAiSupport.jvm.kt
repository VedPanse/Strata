/*
 * Strata - Kotlin Multiplatform client.
 * Copyright (c) 2025.
 */
package org.strata.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

internal object OpenAiSupport {
    private val envFileCandidates =
        listOf(
            "src/.env",
            "composeApp/src/.env",
            ".env",
            "local.properties",
        )

    fun readEnv(key: String): String? {
        System.getenv(key)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        envFileCandidates.forEach { path ->
            val file = File(path)
            if (!file.exists()) return@forEach
            val map =
                file.readLines()
                    .asSequence()
                    .map(String::trim)
                    .filter { it.isNotEmpty() && !it.startsWith('#') }
                    .mapNotNull { line ->
                        val idx = line.indexOf('=')
                        if (idx <= 0) return@mapNotNull null
                        line.substring(0, idx) to line.substring(idx + 1)
                    }
                    .toMap()
            map[key]?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return null
    }

    fun requireApiKey(): String =
        readEnv("OPENAI_API_KEY")
            ?: throw IllegalStateException("Missing OPENAI_API_KEY")

    fun extractErrorMessage(
        json: Json,
        body: String,
    ): String? =
        runCatching {
            val root = json.parseToJsonElement(body).jsonObject
            val err = root["error"]?.jsonObject ?: return null
            val type = err["type"]?.jsonPrimitive?.contentOrNull
            val message = err["message"]?.jsonPrimitive?.contentOrNull
            listOf(type, message).filterNotNull().joinToString(": ").ifBlank { null }
        }.getOrNull()
}

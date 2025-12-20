/*
 * Strata - Kotlin Multiplatform client.
 * Copyright (c) 2025.
 */
package org.strata.ai

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.strata.auth.CalendarEvent
import org.strata.auth.GmailMail
import org.strata.auth.TaskItem

/**
 * JVM implementation of the Gemini summary generator.
 */
actual object SummaryAi {
    private val http = HttpClient(CIO)
    private val json = Json { ignoreUnknownKeys = true }

    // Minimal response model for Gemini generateContent
    @Serializable
    private data class GeminiCandidate(
        val content: GeminiContent? = null,
    )

    @Serializable
    private data class GeminiContent(
        val parts: List<GeminiPart> = emptyList(),
    )

    @Serializable
    private data class GeminiPart(
        val text: String? = null,
    )

    @Serializable
    private data class GeminiResponse(
        val candidates: List<GeminiCandidate> = emptyList(),
        val promptFeedback: PromptFeedback? = null,
    )

    @Serializable
    private data class PromptFeedback(
        val blockReason: String? = null,
    )

    actual suspend fun summarizeDay(
        unreadMails: List<GmailMail>,
        todayEvents: List<CalendarEvent>,
        tasks: List<TaskItem>,
    ): Result<String> {
        val apiKey = runCatching { GeminiSupport.requireApiKey() }.getOrElse { return Result.failure(it) }

        val prompt = buildPrompt(unreadMails, todayEvents, tasks)

        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$apiKey"
        val requestBody =
            mapOf(
                "contents" to
                    listOf(
                        mapOf(
                            "role" to "user",
                            "parts" to listOf(mapOf("text" to prompt)),
                        ),
                    ),
            )
        return runCatching {
            val response =
                http.post(url) {
                    contentType(ContentType.Application.Json)
                    setBody(JsonMapEncoder.encodeToString(requestBody))
                }
            val status = response.status.value
            val body = response.bodyAsText()
            if (status !in 200..299) {
                val message = GeminiSupport.extractErrorMessage(json, body) ?: body.take(1_000)
                error("Gemini HTTP $status: $message")
            }
            val parsed = runCatching { json.decodeFromString<GeminiResponse>(body) }.getOrNull()
            val text =
                parsed?.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                    ?: extractTextBestEffort(body)
            if (text.isNullOrBlank()) error("Empty response from Gemini")
            text.trim()
        }
    }

    private fun extractTextBestEffort(body: String): String? {
        return runCatching {
            val root = json.parseToJsonElement(body).jsonObject
            val candidates = root["candidates"]
            if (candidates != null && candidates is kotlinx.serialization.json.JsonArray) {
                val first = candidates.firstOrNull()?.jsonObject
                val content = first?.get("content")?.jsonObject
                val parts = content?.get("parts") as? kotlinx.serialization.json.JsonArray
                val part0 = parts?.firstOrNull()?.jsonObject
                part0?.get("text")?.jsonPrimitive?.content
            } else {
                null
            }
        }.getOrNull()
    }

    private fun buildPrompt(
        mails: List<GmailMail>,
        events: List<CalendarEvent>,
        tasks: List<TaskItem>,
    ): String {
        fun truncate(
            s: String,
            n: Int,
        ) = if (s.length <= n) s else s.take(n) + "…"
        val mailLines =
            if (mails.isEmpty()) {
                listOf("(no unread emails)")
            } else {
                mails.take(5).mapIndexed { i, m ->
                    "${i + 1}. From: ${truncate(
                        m.from,
                        60,
                    )} | Subject: ${truncate(
                        m.subject,
                        120,
                    )} | Snippet: ${truncate(if (m.body.isNotBlank()) m.body else m.snippet, 200)}"
                }
            }

        fun formatTime(dt: LocalDateTime): String = "%02d:%02d".format(dt.hour, dt.minute)

        val eventLines =
            if (events.isEmpty()) {
                listOf("(no events today)")
            } else {
                events.take(10).mapIndexed { i, e ->
                    val start = formatTime(e.start)
                    val end = formatTime(e.end)
                    val locationSuffix = e.location?.let { ", @ ${truncate(it, 60)}" }.orEmpty()
                    "${i + 1}. ${truncate(e.title, 120)} ($start-$end$locationSuffix)"
                }
            }
        val taskLines =
            if (tasks.isEmpty()) {
                listOf("(no upcoming reminders)")
            } else {
                tasks.take(10).mapIndexed { i, t ->
                    val due =
                        t.due?.let { dueAt ->
                            " due ${dueAt.date} ${formatTime(dueAt)}"
                        }.orEmpty()
                    val notes = t.notes?.let { " — ${truncate(it, 120)}" } ?: ""
                    "${i + 1}. ${truncate(t.title, 120)}${due}$notes"
                }
            }
        return buildString {
            appendLine("You are an assistant that writes a concise, helpful daily brief as bullet points.")
            appendLine("Summarize the user's day based on their unread emails, today's calendar events, and reminders.")
            appendLine("Return 4–6 short bullet points using a leading \"•\" or \"-\".")
            appendLine("Be neutral and prioritize time-sensitive or high-signal items.")
            appendLine()
            appendLine("Unread emails:")
            mailLines.forEach { appendLine(it) }
            appendLine()
            appendLine("Today's calendar:")
            eventLines.forEach { appendLine(it) }
            appendLine()
            appendLine("Reminders/tasks:")
            taskLines.forEach { appendLine(it) }
        }
    }
}

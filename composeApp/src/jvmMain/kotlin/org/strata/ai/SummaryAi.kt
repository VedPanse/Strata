/*
 * Strata - Kotlin Multiplatform client.
 * Copyright (c) 2025.
 */
package org.strata.ai

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
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
    private data class ChatMessage(val role: String, val content: String)

    @Serializable
    private data class ChatRequest(
        val model: String,
        val messages: List<ChatMessage>,
        val temperature: Double = 0.2,
    )

    @Serializable
    private data class ChatChoice(val message: ChatMessage? = null)

    @Serializable
    private data class ChatResponse(val choices: List<ChatChoice> = emptyList())

    actual suspend fun summarizeDay(
        unreadMails: List<GmailMail>,
        todayEvents: List<CalendarEvent>,
        tasks: List<TaskItem>,
    ): Result<String> {
        LlmHealth.requestBlockReason()?.let { return Result.failure(IllegalStateException(it)) }
        val apiKey = runCatching { OpenAiSupport.requireApiKey() }.getOrElse { return Result.failure(it) }

        val prompt = buildPrompt(unreadMails, todayEvents, tasks)
        val cacheKey = "summary:$prompt"
        LlmCache.get(cacheKey)?.let { return Result.success(it) }

        val url = "https://api.openai.com/v1/chat/completions"
        val requestBody =
            ChatRequest(
                model = "gpt-4o-mini",
                messages = listOf(ChatMessage(role = "user", content = prompt)),
            )
        val result =
            runCatching {
                val response =
                    http.post(url) {
                        header("Authorization", "Bearer $apiKey")
                        contentType(ContentType.Application.Json)
                        setBody(json.encodeToString(ChatRequest.serializer(), requestBody))
                    }
                val status = response.status.value
                val body = response.bodyAsText()
                if (status !in 200..299) {
                    val message = OpenAiSupport.extractErrorMessage(json, body) ?: body.take(1_000)
                    error("OpenAI HTTP $status: $message")
                }
                val parsed = runCatching { json.decodeFromString<ChatResponse>(body) }.getOrNull()
                val text = parsed?.choices?.firstOrNull()?.message?.content ?: extractTextBestEffort(body)
                if (text.isNullOrBlank()) error("Empty response from OpenAI")
                text.trim()
            }
        result.onSuccess { response ->
            LlmHealth.recordSuccess()
            LlmCache.put(cacheKey, response)
        }.onFailure { err ->
            LlmHealth.recordFailure(err)
        }
        return result
    }

    private fun extractTextBestEffort(body: String): String? {
        return runCatching {
            val root = json.parseToJsonElement(body).jsonObject
            val choices = root["choices"] as? kotlinx.serialization.json.JsonArray
            val first = choices?.firstOrNull()?.jsonObject
            val message = first?.get("message")?.jsonObject
            message?.get("content")?.jsonPrimitive?.content
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

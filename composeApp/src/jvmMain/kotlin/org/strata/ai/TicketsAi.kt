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
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.strata.auth.CalendarEvent
import org.strata.auth.GmailMail

/**
 * JVM implementation of the Gemini ticket extractor.
 */
actual object TicketsAi {
    private val http = HttpClient(CIO)
    private val json = Json { ignoreUnknownKeys = true }

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

    actual suspend fun extractTickets(
        unreadMails: List<GmailMail>,
        todayEvents: List<CalendarEvent>,
    ): Result<List<ExtractedTicket>> {
        LlmHealth.requestBlockReason()?.let { return Result.failure(IllegalStateException(it)) }
        val apiKey = runCatching { OpenAiSupport.requireApiKey() }.getOrElse { return Result.failure(it) }

        val prompt = buildPrompt(unreadMails, todayEvents)
        val cacheKey = "tickets:$prompt"
        LlmCache.get(cacheKey)?.let { cached ->
            val items = runCatching { parseTicketsJson(cached) }.getOrNull()
            if (items != null) return Result.success(items)
        }
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
                val text = extractText(body)
                println("[DEBUG_LOG][TicketsAi] OpenAI raw text (truncated): ${text.take(500)}")
                val items = parseTicketsJson(text)
                println("[DEBUG_LOG][TicketsAi] Parsed ${items.size} ticket item(s) from OpenAI response")
                text to items
            }
        result.onSuccess { (text, _) ->
            LlmHealth.recordSuccess()
            LlmCache.put(cacheKey, text)
        }.onFailure { err ->
            LlmHealth.recordFailure(err)
        }
        return result.map { it.second }
    }

    private fun extractText(body: String): String {
        // Try to parse structured, else fallback to best-effort
        return runCatching {
            val parsed = json.decodeFromString(ChatResponse.serializer(), body)
            parsed.choices.firstOrNull()?.message?.content?.takeIf { !it.isNullOrBlank() }
        }.getOrNull() ?: run {
            // fallback parse
            val root = json.parseToJsonElement(body).jsonObject
            val choices = root["choices"] as? JsonArray
            choices?.firstOrNull()?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content
        } ?: throw IllegalStateException("Empty response from OpenAI")
    }

    private fun parseTicketsJson(text: String): List<ExtractedTicket> {
        // Expect the model to return strict JSON. If extra text present, find the first [ and last ]
        val jsonSlice =
            run {
                val start = text.indexOf('[')
                val end = text.lastIndexOf(']')
                if (start >= 0 && end > start) text.substring(start, end + 1) else text
            }
        val arr = json.parseToJsonElement(jsonSlice).jsonArray
        val list =
            arr.map { el ->
                val o = el.jsonObject
                var ticket =
                    ExtractedTicket(
                        type = o["type"]?.jsonPrimitive?.content ?: "FLIGHT",
                        number = o["number"]?.jsonPrimitive?.content ?: "",
                        status = o["status"]?.jsonPrimitive?.content ?: "ON_TIME",
                        departureName = o["departureName"]?.jsonPrimitive?.content ?: "",
                        departureAbbr = o["departureAbbr"]?.jsonPrimitive?.content ?: "",
                        arrivalName = o["arrivalName"]?.jsonPrimitive?.content ?: "",
                        arrivalAbbr = o["arrivalAbbr"]?.jsonPrimitive?.content ?: "",
                        departureIso = o["departureIso"]?.jsonPrimitive?.content ?: "",
                        arrivalIso = o["arrivalIso"]?.jsonPrimitive?.content ?: "",
                    )
                // Cleanup common truncations/abbreviations in names coming from the model
                val cleanedDep = normalizePlaceName(ticket.departureName)
                val cleanedArr = normalizePlaceName(ticket.arrivalName)
                if (cleanedDep != ticket.departureName || cleanedArr != ticket.arrivalName) {
                    val normalizedLog =
                        "[DEBUG_LOG][TicketsAi] Normalized names -> " +
                            "dep='${ticket.departureName}'->'$cleanedDep', " +
                            "arr='${ticket.arrivalName}'->'$cleanedArr'"
                    println(normalizedLog)
                    ticket = ticket.copy(departureName = cleanedDep, arrivalName = cleanedArr)
                }
                val ticketLog =
                    "[DEBUG_LOG][TicketsAi] Parsed ticket raw -> " +
                        "type=${ticket.type}, number=${ticket.number}, " +
                        "dep='${ticket.departureName}' (${ticket.departureAbbr}), " +
                        "arr='${ticket.arrivalName}' (${ticket.arrivalAbbr}), " +
                        "depIso=${ticket.departureIso}, arrIso=${ticket.arrivalIso}"
                println(ticketLog)
                ticket
            }.filter {
                it.departureName.isNotBlank() &&
                    it.arrivalName.isNotBlank() &&
                    it.departureIso.isNotBlank() &&
                    it.arrivalIso.isNotBlank()
            }
        println("[DEBUG_LOG][TicketsAi] Tickets after mandatory-field filter: ${list.size}")
        return list
    }

    private fun normalizePlaceName(name: String): String {
        var s = name.trim()
        // expand common trailing abbreviations
        val replacements =
            listOf(
                // exact suffix -> replacement to append
                " Ai" to " Airport",
                " Ai." to " Airport",
                " Ai," to " Airport",
                " Intl" to " International",
                " Intl." to " International",
                " Int'l" to " International",
                " Apt" to " Airport",
                " Apt." to " Airport",
            )
        for ((suffix, full) in replacements) {
            if (s.endsWith(suffix, ignoreCase = true)) {
                s = s.removeSuffix(suffix).trimEnd() + full
                break
            }
        }
        return s
    }

    private fun buildPrompt(
        mails: List<GmailMail>,
        events: List<CalendarEvent>,
    ): String {
        fun truncate(
            s: String,
            n: Int,
        ) = if (s.length <= n) s else s.take(n) + "…"
        val mailLines =
            if (mails.isEmpty()) {
                listOf("(no unread emails)")
            } else {
                mails.take(20).map { m ->
                    val from = truncate(m.from, 60)
                    val subject = truncate(m.subject, 120)
                    val body = truncate(if (m.body.isNotBlank()) m.body else m.snippet, 300)
                    "From: $from | Subject: $subject | Body: $body"
                }
            }
        val eventLines =
            if (events.isEmpty()) {
                listOf("(no events)")
            } else {
                events.take(20).map { e ->
                    val loc = e.location?.let { ", @ ${truncate(it, 80)}" } ?: ""
                    "Event: ${truncate(e.title, 140)} (${e.start} - ${e.end}$loc)"
                }
            }
        return buildString {
            appendLine("You are a structured information extractor.")
            appendLine("Task: From the following emails and calendar events, identify travel tickets.")
            appendLine("Consider flights, trains, and buses.")
            appendLine("For each detected ticket, return a JSON array where each item has this exact schema and keys:")
            appendLine(
                """
                [{
                  "type": "FLIGHT|TRAIN|BUS",
                  "number": "string, e.g., QR750 or 9F 123",
                  "status": "ON_TIME|DELAYED|CANCELLED",
                  "departureName": "full name of origin airport/station",
                  "departureAbbr": "IATA or short code if known, else empty",
                  "arrivalName": "full name of destination",
                  "arrivalAbbr": "IATA or short code if known, else empty",
                  "departureIso": "YYYY-MM-DDThh:mm (24h) in the user's local timezone if not specified",
                  "arrivalIso": "YYYY-MM-DDThh:mm (24h) in the user's local timezone if not specified"
                }]
                """.trimIndent(),
            )
            appendLine("Strict rules:")
            appendLine("- Output ONLY the JSON array. No prose.")
            appendLine("- If you are uncertain, omit the item.")
            appendLine("- Infer missing abbreviations (IATA like SAN/LHR) when obvious; otherwise leave empty string.")
            appendLine()
            appendLine("Emails:")
            mailLines.forEach { appendLine(it) }
            appendLine()
            appendLine("Calendar:")
            eventLines.forEach { appendLine(it) }
        }
    }
}

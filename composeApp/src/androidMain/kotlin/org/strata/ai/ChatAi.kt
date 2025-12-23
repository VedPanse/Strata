/*
 * Strata - Kotlin Multiplatform client.
 * Copyright (c) 2025.
 */
package org.strata.ai

import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

/**
 * Android stub for Gemini chat; desktop handles the actual implementation.
 */
actual object ChatAi {
    private const val MODEL = "gemini-2.5-flash"
    private const val BASE_URL =
        "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent"
    private val json =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = false
        }
    private var quotaInitialized = false

    actual suspend fun sendPrompt(
        prompt: String,
        screen: ScreenAttachment?,
    ): Result<String> {
        if (prompt.isBlank()) return Result.failure(IllegalArgumentException("Prompt is blank"))
        return callGemini(buildAgentPrompt(prompt), screen)
    }

    actual suspend fun mailRewrite(
        subject: String,
        body: String,
        request: String,
    ): Result<String> {
        if (subject.isBlank() || body.isBlank() || request.isBlank()) {
            return Result.failure(IllegalArgumentException("Prompt is blank"))
        }
        return callGemini(buildRewritePrompt(subject, body, request), null)
    }

    private suspend fun callGemini(
        fullPrompt: String,
        screen: ScreenAttachment?,
    ): Result<String> {
        LlmHealth.requestBlockReason()?.let { return Result.failure(IllegalStateException(it)) }
        ensureQuotaConfigured()
        val cacheKey = if (screen == null) "chat:$fullPrompt" else "chat:$fullPrompt:screen"
        LlmCache.get(cacheKey)?.let { return Result.success(it) }

        val result =
            runCatching {
                val apiKey = GeminiSupport.requireApiKey()
                val parts = mutableListOf<Map<String, Any?>>()
                parts += mapOf("text" to fullPrompt)
                if (screen != null) {
                    parts +=
                        mapOf(
                            "inline_data" to
                                mapOf(
                                    "mime_type" to "image/jpeg",
                                    "data" to screen.base64Jpeg,
                                ),
                        )
                }
                val payload =
                    mapOf(
                        "contents" to
                            listOf(
                                mapOf(
                                    "role" to "user",
                                    "parts" to parts,
                                ),
                            ),
                        "generationConfig" to
                            mapOf(
                                "temperature" to 0.2,
                                "maxOutputTokens" to 512,
                            ),
                    )
                val body = JsonMapEncoder.encodeToString(payload)
                val url = URL("$BASE_URL?key=$apiKey")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { output ->
                    output.write(body.toByteArray())
                }
                val status = connection.responseCode
                val stream =
                    if (status in 200..299) connection.inputStream else connection.errorStream
                val raw = stream.bufferedReader().use { it.readText() }
                if (status !in 200..299) {
                    val message = GeminiSupport.extractErrorMessage(json, raw) ?: raw.take(1_000)
                    error("Gemini HTTP $status: $message")
                }
                extractGeminiText(raw) ?: error("Gemini returned an empty message")
            }

        result.onSuccess { response ->
            LlmHealth.recordSuccess()
            LlmCache.put(cacheKey, response)
        }.onFailure { err ->
            LlmHealth.recordFailure(err)
        }

        return result
    }

    private fun ensureQuotaConfigured() {
        if (quotaInitialized) return
        quotaInitialized = true
        val raw = GeminiSupport.readEnv("GEMINI_DAILY_QUOTA")?.trim()?.toIntOrNull()
        LlmHealth.setDailyLimit(raw)
    }

    private fun buildAgentPrompt(userText: String): String =
        AGENT_PROMPT
            .replace("{{NOW_ISO}}", nowIsoKolkata())
            .trim() + "\n\nUSER_REQUEST:\n" + userText.trim()

    private fun buildRewritePrompt(
        subject: String,
        body: String,
        userPrompt: String,
    ): String =
        REWRITE_PROMPT
            .replace("{{SUBJECT}}", subject)
            .replace("{{BODY}}", body)
            .replace("{{USER_PROMPT}}", userPrompt)

    private fun nowIsoKolkata(): String {
        val zone = TimeZone.of("Asia/Kolkata")
        val now = Clock.System.now()
        val local = now.toLocalDateTime(zone)
        val offset = zone.offsetAt(now).toString().removePrefix("UTC")
        val date = "%04d-%02d-%02d".format(local.year, local.monthNumber, local.dayOfMonth)
        val time = "%02d:%02d:%02d".format(local.hour, local.minute, local.second)
        return "${date}T$time$offset"
    }

    private fun extractGeminiText(raw: String): String? =
        runCatching {
            val root = json.parseToJsonElement(raw).jsonObject
            val candidates = root["candidates"]?.jsonArray ?: return null
            val first = candidates.firstOrNull()?.jsonObject ?: return null
            val content = first["content"]?.jsonObject ?: return null
            val parts = content["parts"]?.jsonArray ?: return null
            parts.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content?.trim()
        }.getOrNull()

    val idToken = org.strata.auth.SessionStorage.read()?.idToken
    val displayName = idToken?.let { org.strata.auth.JwtTools.extractName(it) } ?: "Account"

    private val AGENT_PROMPT: String = """
You are Strata’s autonomous operations co-pilot — think of yourself as the user's Jarvis.
You can orchestrate email, calendar, and reminder workflows end-to-end without asking
permission for every micro-decision. Plan ahead, resolve ambiguity, and surface only
the clarifications that truly need human input.

User identity: $displayName. Address people and craft content appropriately when drafting communications.
Long-term memory is provided in the prompt. Only store new memory when the user explicitly asks.
Tone: be playful, super friendly, and helpful - like a trusted assistant who works for the user.
When drafting emails, proactively prepare a clean draft and use `send_email` so the user can edit in the preview.

GENERAL OPERATING PRINCIPLES:
- Internalize the full user request before acting; break it into ordered steps and use
  follow-up assumptions only when details are missing.
- If the user's intent is ambiguous (e.g., "clear my inbox"), ask a clarification via
  an `await_user` action before taking any operational step.
- Choose the most contextually appropriate items when multiple matches exist (titles,
  dates, notes, related email threads). Prefer smart defaults, and record any inference
  inside "assumptions".
- For destructive actions (deleting mail, calendar entries, or reminders) resolve
  ambiguity by selecting the best candidate(s) and note your reasoning. If nothing is
  clearly correct, ask the user via a `user_msg` rather than guessing.
- Prefer stable IDs for updates/deletes; when IDs are unknown, do careful matching and
  only escalate to the user when ambiguity truly remains.
- Keep reminders/calendar/mail in sync: if you schedule something, make sure supporting
  reminders or emails line up with that schedule when it helps the user.
- Your output must stay machine-executable: a pure JSON array of action objects with no prose wrappers.
- When the prompt includes screen perception, answer directly from the visible screen content.
  Do not ask for confirmation or propose checking UI panels; list what you can see and, if needed,
  state what is not visible and request the user to open/scroll that view.
- When screen perception is present, output at most one action per response so the system can
  re-capture the screen after each step.
- For any click, first output a move_cursor action. On the next response, output the click action.
- Avoid asking the user to intervene unless the UI is blocked or you need credentials.
- Use open_url for informational browsing only (scores, facts, links), not for app launching.

ALLOWED ACTION TYPES (exactly one top-level key per object):
- send_email
- explain_email
- reply_to_email
- forward_email
- delete_email
- add_calendar_event
- update_calendar_event
- delete_calendar_event
- user_msg
- add_task
- update_task
- delete_task
- await_user
- tap
- move_cursor
- click
- mouse_down
- mouse_up
- drag
- type_text
- scroll
- open_url
- key_combo
- press_key
- done
- remember
- clear_memory
- web_search
- fetch_url

FORMAT RULES:
- Always output a pure JSON array; no prose; no code fences; no trailing commas.
- Never claim success until verification passes.
- The array’s order is the execution order.
- All dates/times must be resolved to the Asia/Kolkata timezone.
- Prefer event_id for updates/deletes. If unknown, provide the relevant match_* fields so Strata can resolve the item.
- If the user’s phrasing is relative (e.g., “tonight”, “tomorrow morning”), pick a
  reasonable specific time and include an "assumptions" field explaining the choice.
- If required details are missing, make a reasonable assumption and include it in "assumptions".
- Email bodies are plain text. Avoid markdown in email body unless the user explicitly requires it.
- For calendar events, always include "start_time" plus either "end_time" or
  "duration_minutes"; use 24-hour Asia/Kolkata times.
- Never include any keys beyond the allowed action types at the top level of each object.
- Assume the current local timestamp is {{NOW_ISO}} (Asia/Kolkata) unless the user specifies otherwise.
- If you output `await_user`, the array must contain only that single action.

STRICT EMAIL POLICY:
- Only trigger mail actions when the user clearly asks to send/forward/reply/notify by email.
- Never send mail to the user themselves unless they explicitly request it.
- For schedule/planning requests, prefer calendar and task actions unless an email is part of the ask.

INTERACTION RULES:
- When screen perception is present, do not emit a trailing `user_msg` after UI actions.
  Only emit `user_msg` when you need to stop or ask for input.
- When screen perception is not present, after every operational action (anything except `user_msg`),
  emit a trailing `user_msg` summarizing what you just did and offering the next logical choice or
  clarification.
- Any follow-up question must appear inside a `user_msg` or `await_user`. Keep them concise and purposeful.
- Never ask repetitive confirmation questions; if the user already answered, proceed or reply directly.
- If the user is purely conversational, respond with a single `user_msg` only.
- External actions are disabled. Do not output `external_action`.
- For UI tasks, do not use web_search. Use move_cursor/click/type_text/scroll/press_key and verify via the screen.
- Never claim an app/site is open unless you can see it on screen.

CONSTRAINTS:
- Use 24-hour times.
- Resolve relative phrases (e.g., “afternoon”, “evening”, “morning”) into concrete
  times. Example defaults: morning=10:00, afternoon=15:00, evening=19:00,
  night=21:00 (override sensibly based on context).
- If multiple actions are requested (e.g., planning plus an email), include each as a
  separate object, in the order they should be performed.
- Do not ask questions except inside user_msg. Make reasonable assumptions and record them in "assumptions".
- Output MUST be a single JSON array. No extra text.
    """

    private val REWRITE_PROMPT: String =
        """
        You are a Strata email rewriting agent. Your job is to re-write / modify the
        email subject and body to match the user's preferences, without changing its
        meaning.
        Return the modification strictly as JSON using this template (no markdown fences):
        {
          "subject": <the new subject you've re-written>,
          "body": <the new body you've re-written>
        }

        This is the email's current subject: {{SUBJECT}}
        This is the email's current body: {{BODY}}
        This is how the user wants the email to be modified: {{USER_PROMPT}}
        """.trimIndent()
}

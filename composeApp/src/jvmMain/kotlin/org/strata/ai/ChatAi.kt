/*
 * Strata - Kotlin Multiplatform client.
 * Copyright (c) 2025.
 */
package org.strata.ai

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * JVM implementation of the chat client.
 *
 * Uses OpenAI for text-only chat and Gemini when a screen attachment is present.
 * The prompt wrapper enforces a strict JSON array of actions that the agent runtime
 * can execute deterministically.
 */
actual object ChatAi {
    // ---------- Configuration ----------
    private const val GEMINI_MODEL = "gemini-2.5-flash"
    private const val GEMINI_BASE_URL =
        "https://generativelanguage.googleapis.com/v1beta/models/$GEMINI_MODEL:generateContent"
    private const val OPENAI_MODEL = "gpt-4o-mini"
    private const val OPENAI_BASE_URL = "https://api.openai.com/v1/chat/completions"
    private val KOLKATA_ZONE: ZoneId = ZoneId.of("Asia/Kolkata")
    private val ISO_OFFSET: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    // ---------- HTTP / JSON ----------
    private val http =
        HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = 30_000
                connectTimeoutMillis = 15_000
                socketTimeoutMillis = 30_000
            }
            expectSuccess = false // we want to read error bodies ourselves
        }

    private val json =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = false
        }

    private var quotaInitialized = false

    // ---------- Public API ----------
    actual suspend fun mailRewrite(
        subject: String,
        body: String,
        request: String,
    ): Result<String> {
        if (subject.isBlank() || body.isBlank() || request.isBlank()) {
            return Result.failure(IllegalArgumentException("Prompt is blank"))
        }

        val prompt = buildRewritePrompt(subject, body, request)
        // Keep the existing behavior of printing the prompt for debugging
        println(prompt)
        return callGemini(prompt)
    }

    /**
     * Send a prompt to Gemini. We wrap the caller's prompt in our agent instructions so the
     * model returns ONLY the JSON action list we expect.
     */
    actual suspend fun sendPrompt(
        prompt: String,
        screen: ScreenAttachment?,
    ): Result<String> {
        if (prompt.isBlank()) return Result.failure(IllegalArgumentException("Prompt is blank"))
        val fullPrompt = buildAgentPrompt(prompt)
        return if (screen == null) {
            callOpenAi(fullPrompt)
        } else {
            callGemini(fullPrompt, screen)
        }
    }

    // ---------- Core request path (deduplicated) ----------
    private suspend fun callOpenAi(fullPrompt: String): Result<String> {
        LlmHealth.requestBlockReason()?.let { return Result.failure(IllegalStateException(it)) }

        val cacheKey = "chat:openai:$fullPrompt"
        LlmCache.get(cacheKey)?.let { return Result.success(it) }

        val result =
            runCatching {
                val apiKey = OpenAiSupport.requireApiKey()
                val payload =
                    mapOf(
                        "model" to OPENAI_MODEL,
                        "messages" to
                            listOf(
                                mapOf(
                                    "role" to "user",
                                    "content" to fullPrompt,
                                ),
                            ),
                        "temperature" to 0.2,
                        "max_tokens" to 512,
                    )
                val body = JsonMapEncoder.encodeToString(payload)
                val resp =
                    http.post(OPENAI_BASE_URL) {
                        header("Authorization", "Bearer $apiKey")
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }
                val status = resp.status.value
                val rawBody = resp.bodyAsText()
                if (status !in 200..299) {
                    val message = OpenAiSupport.extractErrorMessage(json, rawBody) ?: rawBody.take(1_000)
                    error("OpenAI HTTP $status: $message")
                }
                extractOpenAiText(rawBody) ?: error("OpenAI returned an empty message")
            }

        result.onSuccess { response ->
            LlmHealth.recordSuccess()
            LlmCache.put(cacheKey, response)
        }.onFailure { err ->
            LlmHealth.recordFailure(err)
        }

        return result
    }

    private suspend fun callGemini(
        fullPrompt: String,
        screen: ScreenAttachment? = null,
    ): Result<String> {
        LlmHealth.requestBlockReason()?.let { return Result.failure(IllegalStateException(it)) }

        val cacheKey = if (screen == null) "chat:gemini:$fullPrompt" else "chat:gemini:$fullPrompt:screen"
        LlmCache.get(cacheKey)?.let { return Result.success(it) }

        ensureQuotaConfigured()

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

                val resp =
                    http.post("$GEMINI_BASE_URL?key=$apiKey") {
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }

                val status = resp.status.value
                val rawBody = resp.bodyAsText()

                // 1) Surface non-success with full server message
                if (status !in 200..299) {
                    val message = GeminiSupport.extractErrorMessage(json, rawBody) ?: rawBody.take(1_000)
                    error("Gemini HTTP $status: $message")
                }

                extractGeminiText(rawBody) ?: error("Gemini returned an empty message")
            }

        result.onSuccess { response ->
            LlmHealth.recordSuccess()
            LlmCache.put(cacheKey, response)
        }.onFailure { err ->
            LlmHealth.recordFailure(err)
        }

        return result
    }

    // ---------- Prompt builders ----------
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

    private fun nowIsoKolkata(): String = ZonedDateTime.now(KOLKATA_ZONE).format(ISO_OFFSET)

    private fun ensureQuotaConfigured() {
        if (quotaInitialized) return
        quotaInitialized = true
        val raw = GeminiSupport.readEnv("GEMINI_DAILY_QUOTA")?.trim()?.toIntOrNull()
        LlmHealth.setDailyLimit(raw)
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

    private fun extractOpenAiText(raw: String): String? =
        runCatching {
            val root = json.parseToJsonElement(raw).jsonObject
            val choices = root["choices"]?.jsonArray ?: return null
            val first = choices.firstOrNull()?.jsonObject ?: return null
            val message = first["message"]?.jsonObject ?: return null
            message["content"]?.jsonPrimitive?.content?.trim()
        }.getOrNull()

    // ---------- Utilities ----------
    // ---------- Prompts ----------
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
- Use `remember` only when the user explicitly asks you to remember something or store a preference.
- Use `web_search` or `fetch_url` when the user asks for current info, links, or online research.
- For `open_url`, open the URL in the user's default browser.
- For `key_combo`, provide key names like ["CMD","SPACE"] or ["CTRL","L"].

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

ACTION PAYLOAD CONTRACTS:

send_email:
{
  "send_email": {
    "to": ["email@example.com"],           // one or more recipients (emails only)
    "subject": "string",
    "body": "string (plain text)",
    "cc": ["optional@domain.com"],         // optional
    "bcc": ["optional@domain.com"],        // optional
    "assumptions": "optional notes"
  }
}

explain_email:
{
  "explain_email": {
    "email_id": "provider-specific-id",
    "summary_style": "tl;dr" | "bullets" | "paragraph",  // optional
    "assumptions": "optional notes"
  }
}

reply_to_email:
{
  "reply_to_email": {
    "email_id": "provider-specific-id",
    "body": "string (plain text)",
    "subject": "string (optional; default 'Re: <original>')",
    "assumptions": "optional notes"
  }
}

forward_email:
{
  "forward_email": {
    "email_id": "provider-specific-id",
    "to": ["email@example.com"],
    "preface": "optional intro text",
    "assumptions": "optional notes"
  }
}

delete_email:
{
  "delete_email": {
    "email_id": "provider-specific-id",
    "assumptions": "optional notes"
  }
}

add_calendar_event:
{
  "add_calendar_event": {
    "title": "string",
    "date": "YYYY-MM-DD",                 // Asia/Kolkata
    "start_time": "HH:MM",                // 24-hour time, local
    "end_time": "HH:MM",                  // OR omit end_time and provide duration_minutes
    "duration_minutes": 60,               // optional alternative to end_time
    "location": "optional",
    "notes": "optional",
    "assumptions": "optional notes"
  }
}

update_calendar_event:
{
  "update_calendar_event": {
    "event_id": "string (preferred if known)",
    "match_title": "string (fallback)",
    "match_date": "YYYY-MM-DD (optional helper)",
    "match_start_time": "HH:MM (optional helper)",
    "new_date": "YYYY-MM-DD (optional)",
    "new_start_time": "HH:MM (optional)",
    "new_end_time": "HH:MM (optional; prefer this over duration)",
    "new_duration_minutes": 60,
    "new_title": "optional",
    "new_location": "optional",
    "new_notes": "optional",
    "assumptions": "optional notes"
  }
}

delete_calendar_event:
{
  "delete_calendar_event": {
    "title": "string",                    // or event_id if known
    "date": "YYYY-MM-DD",                 // local
    "start_time": "HH:MM",                // optional but recommended
    "assumptions": "optional notes"
  }
}

user_msg:
{
  "user_msg": {
    "text": "string"                      // brief acknowledgment / follow-up / suggestion
  }
}

add_task:
{
  "add_task": {
    "title": "string",
    "due_date": "YYYY-MM-DD",             // optional; Asia/Kolkata
    "due_time": "HH:MM",                  // optional 24-hour; requires due_date
    "notes": "optional",
    "assumptions": "optional notes"
  }
}

update_task:
{
  "update_task": {
    "task_id": "optional if known",
    "match_title": "string",              // required if task_id missing unless delete_all=true
    "match_due_date": "YYYY-MM-DD",       // optional helper for matching
    "match_due_time": "HH:MM",            // optional helper for matching
    "new_title": "optional",
    "new_notes": "optional",
    "new_due_date": "YYYY-MM-DD",         // optional
    "new_due_time": "HH:MM",              // optional 24-hour
    "completed": true | false,            // optional toggle
    "assumptions": "optional notes"
  }
}

delete_task:
{
  "delete_task": {
    "task_id": "optional if known",
    "match_title": "string",              // required if task_id missing
    "match_due_date": "YYYY-MM-DD",       // optional helper for matching
    "match_due_time": "HH:MM",            // optional helper for matching
    "delete_all": true,                    // optional; true means delete every task matching filters
    "assumptions": "optional notes"
  }
}

await_user:
{
  "await_user": {
    "question": "string (the clarifying question)",
    "context": "optional short context for resuming",
    "assumptions": "optional notes"
  }
}

move_cursor:
{
  "move_cursor": {
    "x": 123,
    "y": 456
  }
}

click:
{
  "click": {
    "x": 123,
    "y": 456,
    "button": "left",
    "count": 1
  }
}

mouse_down:
{
  "mouse_down": {
    "x": 123,
    "y": 456,
    "button": "left"
  }
}

mouse_up:
{
  "mouse_up": {
    "x": 123,
    "y": 456,
    "button": "left"
  }
}

drag:
{
  "drag": {
    "start_x": 123,
    "start_y": 456,
    "end_x": 420,
    "end_y": 900,
    "duration_ms": 350,
    "button": "left"
  }
}

type_text:
{
  "type_text": {
    "text": "string"
  }
}

scroll:
{
  "scroll": {
    "dx": 0,
    "dy": 320
  }
}

press_key:
{
  "press_key": {
    "key": "ENTER"
  }
}

done:
{
  "done": {
    "summary": "optional completion note"
  }
}

external_action:
{
  "external_action": {
    "provider": "string (e.g., 'ubereats')",
    "intent": "string (e.g., 'order_food')",
    "params": { "key": "value" },
    "confirmation_question": "string (ask before any execution)",
    "auth_state": "optional: linked|missing|unknown",
    "assumptions": "optional notes"
  }
}

remember:
{
  "remember": {
    "content": "string (concise fact to store)",
    "assumptions": "optional notes"
  }
}

clear_memory:
{
  "clear_memory": {
    "reason": "optional note"
  }
}

web_search:
{
  "web_search": {
    "query": "string",
    "top_k": 1
  }
}

fetch_url:
{
  "fetch_url": {
    "url": "https://example.com",
    "max_chars": 2000
  }
}

CONSTRAINTS:
- Use 24-hour times.
- Resolve relative phrases (e.g., “afternoon”, “evening”, “morning”) into concrete
  times. Example defaults: morning=10:00, afternoon=15:00, evening=19:00,
  night=21:00 (override sensibly based on context).
- If multiple actions are requested (e.g., planning plus an email), include each as a
  separate object, in the order they should be performed.
- Do not ask questions except inside user_msg. Make reasonable assumptions and record them in "assumptions".
- Output MUST be a single JSON array. No extra text.

EXAMPLES:

1) Conversational greeting only:
[
  { "user_msg": { "text": "I'm good—how are you?" } }
]

2) Planning with follow-up message:
[
  {
    "add_calendar_event": {
      "title": "HILD 7C Study Session",
      "date": "2025-08-23",
      "start_time": "10:00",
      "end_time": "11:30",
      "notes": "Focused review",
      "assumptions": "90-minute block chosen; scheduled for today in Asia/Kolkata."
    }
  },
  {
    "user_msg": {
      "text": "Added this study session. Want me to shift it to 18:00–20:00?"
    }
  }
]

3) Rescheduling with verification:
[
  {
    "update_calendar_event": {
      "event_id": "abc123",
      "new_date": "2025-09-18",
      "new_start_time": "19:00",
      "new_end_time": "20:00",
      "assumptions": "Keeping the original title and notes."
    }
  },
  { "user_msg": { "text": "I'll confirm once I see the new 19:00–20:00 slot saved." } }
]
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

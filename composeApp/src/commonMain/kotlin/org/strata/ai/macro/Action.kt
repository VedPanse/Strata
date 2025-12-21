/*
 * Strata - Kotlin Multiplatform client.
 * Copyright (c) 2025.
 */
package org.strata.ai

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.Clock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.strata.auth.CalendarApi
import org.strata.auth.CalendarEvent
import org.strata.auth.GmailApi
import org.strata.auth.SessionStorage
import org.strata.auth.TaskItem
import org.strata.auth.TasksApi
import org.strata.ai.WebFetchApi
import org.strata.ai.WebSearchResult
import org.strata.persistence.MemoryStore
import org.strata.persistence.PlanStore
import kotlin.math.abs
import kotlin.random.Random

/**
 * Polymorphic deserializer selecting the concrete Action subtype
 * based on the single allowed top-level key in each action object.
 */
object ActionSerializer : kotlinx.serialization.json.JsonContentPolymorphicSerializer<Action>(Action::class) {
    override fun selectDeserializer(element: kotlinx.serialization.json.JsonElement) =
        when {
            "send_email" in element.jsonObject -> Action.SendEmail.serializer()
            "explain_email" in element.jsonObject -> Action.ExplainEmail.serializer()
            "reply_to_email" in element.jsonObject -> Action.ReplyToEmail.serializer()
            "forward_email" in element.jsonObject -> Action.ForwardEmail.serializer()
            "delete_email" in element.jsonObject -> Action.DeleteEmail.serializer()
            "add_calendar_event" in element.jsonObject -> Action.AddCalendarEvent.serializer()
            "update_calendar_event" in element.jsonObject -> Action.UpdateCalendarEvent.serializer()
            "delete_calendar_event" in element.jsonObject -> Action.DeleteCalendarEvent.serializer()
            "user_msg" in element.jsonObject -> Action.UserMsg.serializer()
            "add_task" in element.jsonObject -> Action.AddTask.serializer()
            "update_task" in element.jsonObject -> Action.UpdateTask.serializer()
            "delete_task" in element.jsonObject -> Action.DeleteTask.serializer()
            "await_user" in element.jsonObject -> Action.AwaitUser.serializer()
            "external_action" in element.jsonObject -> Action.ExternalAction.serializer()
            "remember" in element.jsonObject -> Action.Remember.serializer()
            "clear_memory" in element.jsonObject -> Action.ClearMemory.serializer()
            "web_search" in element.jsonObject -> Action.WebSearch.serializer()
            "fetch_url" in element.jsonObject -> Action.FetchUrl.serializer()
            else -> error("Unknown action key(s): ${element.jsonObject.keys}")
        }
}

// ───────────────────── Mail Preview Bridge ───────────────────── //

/**
 * A single-shot request to preview/edit an email before sending.
 */
data class MailPreviewRequest(
    val initial: MailPreview,
    val result: kotlinx.coroutines.CompletableDeferred<MailPreviewDecision>,
)

/** What the user chose in the preview UI. */
sealed class MailPreviewDecision {
    data class Send(
        val to: List<String>,
        val subject: String,
        val body: String,
    ) : MailPreviewDecision()

    data object Cancel : MailPreviewDecision()
}

/** Simple event bus to bridge non-UI code with a Composable overlay. */
object MailPreviewBus {
    private val _requests = kotlinx.coroutines.flow.MutableSharedFlow<MailPreviewRequest>(extraBufferCapacity = 1)
    val requests = _requests.asSharedFlow()

    suspend fun request(preview: MailPreview): MailPreviewDecision {
        val deferred = kotlinx.coroutines.CompletableDeferred<MailPreviewDecision>()
        _requests.emit(MailPreviewRequest(preview, deferred))
        return deferred.await()
    }
}

// ───────────────────── Task Confirmation Bridge ───────────────────── //

data class TaskConfirmationRequest(
    val tasks: List<TaskItem>,
    val reason: String,
    val confirmLabel: String,
    val cancelLabel: String,
    val result: kotlinx.coroutines.CompletableDeferred<Boolean>,
)

object TaskConfirmationBus {
    private val _requests = kotlinx.coroutines.flow.MutableSharedFlow<TaskConfirmationRequest>(extraBufferCapacity = 1)
    val requests = _requests.asSharedFlow()

    suspend fun request(
        tasks: List<TaskItem>,
        reason: String,
        confirmLabel: String = "Delete",
        cancelLabel: String = "Cancel",
    ): Boolean {
        val deferred = kotlinx.coroutines.CompletableDeferred<Boolean>()
        _requests.emit(TaskConfirmationRequest(tasks, reason, confirmLabel, cancelLabel, deferred))
        return deferred.await()
    }
}

data class CalendarConfirmationRequest(
    val events: List<CalendarEvent>,
    val reason: String,
    val confirmLabel: String,
    val cancelLabel: String,
    val result: kotlinx.coroutines.CompletableDeferred<CalendarEvent?>,
)

object CalendarConfirmationBus {
    private val _requests =
        kotlinx.coroutines.flow.MutableSharedFlow<CalendarConfirmationRequest>(
            extraBufferCapacity = 1,
        )
    val requests = _requests.asSharedFlow()

    suspend fun request(
        events: List<CalendarEvent>,
        reason: String,
        confirmLabel: String = "Use",
        cancelLabel: String = "Skip",
    ): CalendarEvent? {
        val deferred = kotlinx.coroutines.CompletableDeferred<CalendarEvent?>()
        _requests.emit(CalendarConfirmationRequest(events, reason, confirmLabel, cancelLabel, deferred))
        return deferred.await()
    }
}

object TaskHighlightState {
    private val _highlightedIds = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())
    val highlightedIds = _highlightedIds.asStateFlow()

    fun set(ids: Set<String>) {
        _highlightedIds.value = ids
    }

    fun clear() {
        _highlightedIds.value = emptySet()
    }
}

// ───────────────────── Sealed Actions ───────────────────── //

/**
 * Sealed action set focused on Mail & Calendar only.
 * This mirrors the agent's allowed top-level keys.
 */
@Serializable(with = ActionSerializer::class)
sealed class Action {
    @Serializable data class SendEmail(val send_email: SendEmailData) : Action()

    @Serializable data class ExplainEmail(val explain_email: ExplainEmailData) : Action()

    @Serializable data class ReplyToEmail(val reply_to_email: ReplyToEmailData) : Action()

    @Serializable data class ForwardEmail(val forward_email: ForwardEmailData) : Action()

    @Serializable data class DeleteEmail(val delete_email: DeleteEmailData) : Action()

    @Serializable data class AddCalendarEvent(val add_calendar_event: AddCalendarEventData) : Action()

    @Serializable data class UpdateCalendarEvent(val update_calendar_event: UpdateCalendarEventData) : Action()

    @Serializable data class DeleteCalendarEvent(val delete_calendar_event: DeleteCalendarEventData) : Action()

    @Serializable data class UserMsg(val user_msg: UserMsgData) : Action()

    @Serializable data class AddTask(val add_task: AddTaskData) : Action()

    @Serializable data class UpdateTask(val update_task: UpdateTaskData) : Action()

    @Serializable data class DeleteTask(val delete_task: DeleteTaskData) : Action()

    @Serializable data class AwaitUser(val await_user: AwaitUserData) : Action()

    @Serializable data class ExternalAction(val external_action: ExternalActionData) : Action()

    @Serializable data class Remember(val remember: RememberData) : Action()

    @Serializable data class ClearMemory(val clear_memory: ClearMemoryData) : Action()

    @Serializable data class WebSearch(val web_search: WebSearchData) : Action()

    @Serializable data class FetchUrl(val fetch_url: FetchUrlData) : Action()
}

// ───────────────────── Payloads ───────────────────── //

@Serializable
data class SendEmailData(
    val to: List<String>,
    val subject: String,
    val body: String,
    val cc: List<String>? = null,
    val bcc: List<String>? = null,
    val assumptions: String? = null,
)

@Serializable
data class ExplainEmailData(
    val email_id: String,
    // "tl;dr" | "bullets" | "paragraph"
    val summary_style: String? = null,
    val assumptions: String? = null,
)

@Serializable
data class ReplyToEmailData(
    val email_id: String,
    val body: String,
    val subject: String? = null,
    val assumptions: String? = null,
)

@Serializable
data class ForwardEmailData(
    val email_id: String,
    val to: List<String>,
    val preface: String? = null,
    val assumptions: String? = null,
)

@Serializable
data class DeleteEmailData(
    val email_id: String,
    val assumptions: String? = null,
)

@Serializable
data class AddCalendarEventData(
    val title: String,
    // "YYYY-MM-DD" (Asia/Kolkata)
    val date: String,
    // "HH:MM" 24h
    val start_time: String,
    // OR duration_minutes
    val end_time: String? = null,
    val duration_minutes: Int? = null,
    val location: String? = null,
    val notes: String? = null,
    val assumptions: String? = null,
)

@Serializable
data class UpdateCalendarEventData(
    val event_id: String? = null,
    val match_title: String? = null,
    // "YYYY-MM-DD"
    val match_date: String? = null,
    // "HH:MM"
    val match_start_time: String? = null,
    val new_date: String? = null,
    val new_start_time: String? = null,
    val new_end_time: String? = null,
    val new_duration_minutes: Int? = null,
    val new_title: String? = null,
    val new_location: String? = null,
    val new_notes: String? = null,
    val assumptions: String? = null,
)

@Serializable
data class DeleteCalendarEventData(
    val title: String? = null,
    // "YYYY-MM-DD"
    val date: String? = null,
    val start_time: String? = null,
    val assumptions: String? = null,
)

@Serializable
data class UserMsgData(
    val text: String,
)

@Serializable
data class AddTaskData(
    val title: String,
    val due_date: String? = null,
    val due_time: String? = null,
    val notes: String? = null,
    val assumptions: String? = null,
)

@Serializable
data class UpdateTaskData(
    val task_id: String? = null,
    val match_title: String? = null,
    val match_due_date: String? = null,
    val match_due_time: String? = null,
    val new_title: String? = null,
    val new_notes: String? = null,
    val new_due_date: String? = null,
    val new_due_time: String? = null,
    val completed: Boolean? = null,
    val assumptions: String? = null,
)

@Serializable
data class DeleteTaskData(
    val task_id: String? = null,
    val match_title: String? = null,
    val match_due_date: String? = null,
    val match_due_time: String? = null,
    val delete_all: Boolean? = null,
    val assumptions: String? = null,
)

@Serializable
data class AwaitUserData(
    val question: String,
    val context: String? = null,
    val assumptions: String? = null,
)

@Serializable
data class ExternalActionData(
    val provider: String,
    val intent: String,
    val params: JsonObject? = null,
    val confirmation_question: String? = null,
    val auth_state: String? = null,
    val assumptions: String? = null,
)

@Serializable
data class RememberData(
    val content: String,
    val assumptions: String? = null,
)

@Serializable
data class ClearMemoryData(
    val reason: String? = null,
)

@Serializable
data class WebSearchData(
    val query: String,
    val top_k: Int? = null,
)

@Serializable
data class FetchUrlData(
    val url: String,
    val max_chars: Int? = null,
)

private fun formatSearchResults(results: List<WebSearchResult>): String {
    if (results.isEmpty()) return ""
    return buildString {
        appendLine("Here are the top results:")
        results.forEachIndexed { idx, result ->
            val line = "${idx + 1}. ${result.title} - ${result.url}"
            appendLine(line)
            result.snippet?.takeIf { it.isNotBlank() }?.let { snippet ->
                appendLine("   ${snippet.trim()}")
            }
        }
    }.trim()
}

// ───────────────────── Parser ───────────────────── //

private val parser = Json { ignoreUnknownKeys = true }

/** Parse Gemini's JSON array of actions into a typed list. */
fun parseJSON(raw: String): List<Action> = parser.decodeFromString(ListSerializer(ActionSerializer), raw)

// ───────────── Optional UI bridge models (helpers) ───────────── //

/** Mail UI preview model. */
data class MailPreview(
    val subject: String,
    val body: String,
    val recipients: List<String>,
)

/** Calendar UI preview model. */
data class CalendarEventPreview(
    val title: String,
    // "YYYY-MM-DD"
    val date: String,
    // "HH:MM"
    val startTime: String,
    // nullable if using duration
    val endTime: String?,
    val durationMinutes: Int?,
    val location: String?,
    val notes: String?,
)

fun Action.toMailPreviewOrNull(): MailPreview? =
    when (this) {
        is Action.SendEmail ->
            MailPreview(
                subject = send_email.subject,
                body = send_email.body,
                recipients = send_email.to,
            )
        is Action.ReplyToEmail ->
            MailPreview(
                subject = reply_to_email.subject ?: "Re:",
                body = reply_to_email.body,
                // Resolved by email_id downstream.
                recipients = emptyList(),
            )
        is Action.ForwardEmail ->
            MailPreview(
                subject = "Fwd:",
                body = forward_email.preface.orEmpty(),
                recipients = forward_email.to,
            )
        else -> null
    }

fun Action.toCalendarPreviewOrNull(): CalendarEventPreview? =
    when (this) {
        is Action.AddCalendarEvent ->
            CalendarEventPreview(
                title = add_calendar_event.title,
                date = add_calendar_event.date,
                startTime = add_calendar_event.start_time,
                endTime = add_calendar_event.end_time,
                durationMinutes = add_calendar_event.duration_minutes,
                location = add_calendar_event.location,
                notes = add_calendar_event.notes,
            )
        else -> null
    }

// ───────────────────── Execution / Handlers ───────────────────── //

data class ExecutionSummary(val sentEmails: Int = 0, val failedEmails: Int = 0)

data class RefreshSignals(
    val refreshMail: Boolean = false,
    val refreshCalendar: Boolean = false,
    val refreshTasks: Boolean = false,
    val refreshSummary: Boolean = false,
)

data class GeminiRunResult(
    val summary: ExecutionSummary = ExecutionSummary(),
    val userMessages: List<String> = emptyList(),
    val refreshSignals: RefreshSignals = RefreshSignals(),
)

data class RewriteResult(val subject: String, val body: String)

/**
 * Extracts a JSON object (optionally inside a ```json ... ``` code block)
 * and returns exactly two fields: subject and body. Returns null if parsing fails.
 */
fun handleRewrite(response: String?): RewriteResult? {
    if (response.isNullOrBlank()) {
        println("[handleRewrite] empty response")
        return null
    }

    val jsonString =
        JSON_FENCE_REGEX.find(response)?.groupValues?.get(1)?.trim()
            ?: response.trim()

    return try {
        val parsed = Json.parseToJsonElement(jsonString).jsonObject
        val subject = parsed["subject"]?.jsonPrimitive?.content
        val body = parsed["body"]?.jsonPrimitive?.content
        if (subject == null || body == null) {
            println("[handleRewrite] Missing 'subject' or 'body'")
            null
        } else {
            val extras = parsed.keys - setOf("subject", "body")
            if (extras.isNotEmpty()) println("[handleRewrite] Ignoring extra keys: $extras")

            val minimal: JsonObject =
                buildJsonObject {
                    put("subject", JsonPrimitive(subject))
                    put("body", JsonPrimitive(body))
                }
            println(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), minimal))

            RewriteResult(subject, body)
        }
    } catch (e: Exception) {
        println("[handleRewrite] Failed to parse JSON: ${e.message}")
        null
    }
}

suspend fun handleGeminiResponse(response: String?): GeminiRunResult {
    println("[Gemini raw]: ${response ?: "null"}")

    if (response.isNullOrBlank()) {
        println("[Gemini] Empty response string.")
        return GeminiRunResult()
    }

    val jsonArrayStr =
        extractJsonArray(response) ?: run {
            println("[Gemini] Could not locate a JSON array in the response.")
            return GeminiRunResult()
        }

    val actions: List<Action> =
        try {
            parseJSON(jsonArrayStr)
        } catch (e: SerializationException) {
            println("[Gemini] Failed to parse actions: ${e.message}")
            return GeminiRunResult()
        } catch (t: Throwable) {
            println("[Gemini] Unexpected parse error: ${t.message}")
            return GeminiRunResult()
        }

    if (actions.isEmpty()) {
        println("[Gemini] Parsed 0 actions.")
        return GeminiRunResult()
    }

    println("[Gemini] Parsed ${actions.size} action(s). Executing…")

    val token = runCatching { SessionStorage.read()?.accessToken }.getOrNull()
    var sent = 0
    var failed = 0
    val userMessages = mutableListOf<String>()
    var suppressNextUserMsg = false

    var calendarMutated = false
    var tasksMutated = false
    var mailMutated = false

    var cachedTasksForOps: List<TaskItem>? = null

    suspend fun loadTasksForOps(
        tokenValue: String,
        forceReload: Boolean = false,
    ): List<TaskItem> {
        if (!forceReload) cachedTasksForOps?.let { return it }
        val result = TasksApi.fetchTopTasks(tokenValue)
        result.onFailure { e -> println("[tasks] fetch failed: ${e.message}") }
        return result.getOrElse { emptyList() }.also { cachedTasksForOps = it }
    }

    fun parseDateOrNull(raw: String?): LocalDate? =
        raw?.trim()?.takeIf { it.isNotEmpty() }?.let { value ->
            runCatching { LocalDate.parse(value) }.getOrNull()
        }

    fun parseTimeOrNull(raw: String?): Pair<Int, Int>? =
        raw?.trim()?.takeIf { it.isNotEmpty() }?.let { value ->
            val parts = value.split(":")
            if (parts.size != 2) return@let null
            val hour = parts[0].toIntOrNull() ?: return@let null
            val minute = parts[1].toIntOrNull() ?: return@let null
            if (hour !in 0..23 || minute !in 0..59) return@let null
            hour to minute
        }

    fun toLocalDateTime(
        date: LocalDate?,
        time: Pair<Int, Int>?,
    ): LocalDateTime? {
        if (date == null) return null
        val (hour, minute) = time ?: (0 to 0)
        return runCatching { LocalDateTime(date.year, date.monthNumber, date.dayOfMonth, hour, minute) }.getOrNull()
    }

    fun isRetryableCalendarError(error: Throwable?): Boolean {
        if (error == null) return false
        val message = error.message?.lowercase() ?: return false
        return message.contains(" 429") || message.startsWith("429") || message.contains(" 5")
    }

    suspend fun <T> withCalendarRetry(
        label: String,
        mutationKey: String,
        block: suspend () -> Result<T>,
    ): Result<T> {
        val maxAttempts = 2
        var attempt = 1
        var lastFailure: Throwable? = null
        while (attempt <= maxAttempts) {
            println("[$label] attempt $attempt (key=$mutationKey)")
            val result = block()
            if (result.isSuccess) return result
            lastFailure = result.exceptionOrNull()
            if (!isRetryableCalendarError(lastFailure) || attempt == maxAttempts) {
                return result
            }
            val backoffMs = 500L * attempt
            println("[$label] retrying after ${backoffMs}ms → ${lastFailure?.message}")
            delay(backoffMs)
            attempt++
        }
        return Result.failure(lastFailure ?: IllegalStateException("[$label] calendar mutation failed"))
    }

    fun LocalDateTime.formatHm(): String = "%02d:%02d".format(hour, minute)

    fun dayRange(date: LocalDate): Pair<LocalDateTime, LocalDateTime> {
        val start = LocalDateTime(date.year, date.monthNumber, date.dayOfMonth, 0, 0)
        val end = LocalDateTime(date.year, date.monthNumber, date.dayOfMonth, 23, 59, 59)
        return start to end
    }

    suspend fun listEventsForDay(
        tokenNonNull: String,
        date: LocalDate,
        titleFilter: String? = null,
    ): List<CalendarEvent> {
        val (start, end) = dayRange(date)
        val result = CalendarApi.listEvents(tokenNonNull, start, end, titleFilter)
        result.onFailure { e -> println("[calendar] listEvents failed for $date: ${e.message}") }
        return result.getOrElse { emptyList() }
    }

    suspend fun findEventById(
        tokenNonNull: String,
        eventId: String,
        hintDates: List<LocalDate>,
    ): CalendarEvent? {
        for (date in hintDates) {
            val events = listEventsForDay(tokenNonNull, date, null)
            events.firstOrNull { it.id == eventId }?.let { return it }
        }
        // fallback: search wider (today ±30 days)
        val tz = TimeZone.currentSystemDefault()
        val today = Clock.System.now().toLocalDateTime(tz).date
        val startDate = today.minus(DatePeriod(days = 1))
        val endDate = today.plus(DatePeriod(days = 30))
        val start = dayRange(startDate).first
        val end = dayRange(endDate).second
        val result = CalendarApi.listEvents(tokenNonNull, start, end, null)
        return result.getOrNull()?.firstOrNull { it.id == eventId }
    }

    val bulkAllKeywords = setOf("all", "every", "everything")
    val bulkEventKeywords =
        setOf(
            "events", "event", "calendar", "appointments", "schedule", "agenda", "meetings", "meeting", "plans", "entries",
        )
    val bulkAllowedTokens =
        setOf(
            "all", "every", "everything", "clear", "delete", "remove", "reset", "wipe",
            "my", "calendar", "events", "event", "appointments", "appointment", "schedule",
            "today", "for", "the", "day", "this", "entire", "whole", "on", "please",
            "agenda", "meetings", "meeting", "entries", "plan", "plans",
        )

    fun tokenizeForBulk(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.lowercase()
            .replace("[^a-z0-9 ]".toRegex(), " ")
            .split(' ')
            .mapNotNull { it.trim().takeIf(String::isNotEmpty) }
    }

    fun isGenericDeleteAllCalendarIntent(
        title: String?,
        assumptions: String?,
    ): Boolean {
        val titleTokens = tokenizeForBulk(title)
        val assumptionTokens = tokenizeForBulk(assumptions)
        val combined = titleTokens + assumptionTokens
        if (combined.isEmpty()) return false
        val hasAllKeyword = combined.any { it in bulkAllKeywords }
        val hasEventKeyword = combined.any { it in bulkEventKeywords }
        if (!hasAllKeyword) return false
        if (!hasEventKeyword && !(titleTokens.size == 1 && titleTokens.first() in bulkAllKeywords)) return false
        if (titleTokens.isNotEmpty() && titleTokens.any { it !in bulkAllowedTokens }) return false
        return true
    }

    fun formatRange(
        start: LocalDateTime,
        end: LocalDateTime,
    ): String = "${start.formatHm()}–${end.formatHm()}"

    fun dueMatches(
        item: TaskItem,
        matchDate: LocalDate?,
        matchTime: Pair<Int, Int>?,
    ): Boolean {
        val due = item.due
        if (matchDate != null) {
            if (due == null) return false
            if (due.year != matchDate.year || due.monthNumber != matchDate.monthNumber || due.dayOfMonth != matchDate.dayOfMonth) return false
        }
        if (matchTime != null) {
            if (due == null) return false
            if (due.hour != matchTime.first || due.minute != matchTime.second) return false
        }
        return true
    }

    suspend fun resolveTaskReference(
        idx: Int,
        actionName: String,
        tokenNonNull: String,
        taskIdRaw: String?,
        matchTitle: String?,
        matchDate: LocalDate?,
        matchTime: Pair<Int, Int>?,
        requireTaskDetails: Boolean,
    ): Pair<String, TaskItem?>? {
        val cleanedId = taskIdRaw?.trim()?.takeIf { it.isNotEmpty() }
        val needsLookup = requireTaskDetails || cleanedId == null
        val tasks: List<TaskItem>? = if (needsLookup) loadTasksForOps(tokenNonNull) else null
        if (needsLookup && tasks == null) println("[$idx] $actionName failed to load tasks for resolution.")

        if (cleanedId != null) {
            val match = tasks?.firstOrNull { it.id == cleanedId }
            if (match != null) return cleanedId to match
            if (tasks != null) {
                println(
                    "[$idx] $actionName warning: task_id '$cleanedId' not found among fetched tasks; proceeding with id only.",
                )
            }
            return cleanedId to null
        }

        val title = matchTitle?.trim()?.takeIf { it.isNotEmpty() }
        if (title == null) {
            println("[$idx] $actionName skipped: missing match_title and task_id.")
            return null
        }

        val searchSpace = tasks ?: loadTasksForOps(tokenNonNull)

        if (searchSpace.isNullOrEmpty()) {
            println("[$idx] $actionName skipped: no tasks available to match '$title'.")
            return null
        }

        val exact = searchSpace.filter { it.title.equals(title, ignoreCase = true) }
        val partial =
            if (exact.isNotEmpty()) {
                exact
            } else {
                searchSpace.filter {
                    it.title.contains(
                        title,
                        ignoreCase = true,
                    )
                }
            }
        val filtered =
            if (matchDate != null || matchTime != null) {
                partial.filter {
                    dueMatches(it, matchDate, matchTime)
                }
            } else {
                partial
            }
        val candidates =
            when {
                filtered.isNotEmpty() -> filtered
                partial.isNotEmpty() -> partial
                else -> emptyList()
            }

        if (candidates.isEmpty()) {
            println("[$idx] $actionName skipped: no task matched title '$title'.")
            return null
        }

        val farFuture = LocalDateTime(9999, 12, 31, 23, 59)
        val chosen = candidates.minByOrNull { it.due ?: farFuture }
        if (chosen == null) {
            println("[$idx] $actionName skipped: unable to resolve candidate for '$title'.")
            return null
        }
        return chosen.id to chosen
    }

    fun titleMatches(
        task: TaskItem,
        query: String,
    ): Boolean {
        val normalizedQuery = normalizedTaskString(query)
        if (normalizedQuery.isEmpty()) return false
        if (normalizedQuery == "*" || normalizedQuery == "all") return true

        val normalizedTitle = normalizedTaskString(task.title)
        if (normalizedTitle.isEmpty()) return false

        if (normalizedTitle.contains(normalizedQuery) || normalizedQuery.contains(normalizedTitle)) return true

        val queryTokens = normalizeTaskTokens(query)
        val titleTokens = normalizeTaskTokens(task.title)
        if (queryTokens.isEmpty() || titleTokens.isEmpty()) {
            return normalizedTitle.contains(normalizedQuery)
        }

        val matches =
            queryTokens.count { token ->
                titleTokens.any {
                    it == token || it.startsWith(token) || token.startsWith(it)
                }
            }
        val ratio = matches.toDouble() / queryTokens.size
        if (ratio >= 0.6) return true

        // fallback: partial overlap with at least two shared tokens for longer queries
        if (queryTokens.size >= 3 && matches >= 2) return true

        return false
    }

    fun dueMatchesForDelete(
        task: TaskItem,
        latestDate: LocalDate?,
        exactTime: Pair<Int, Int>?,
    ): Boolean {
        if (latestDate == null && exactTime == null) return true
        val due = task.due ?: return false
        val dueDate = LocalDate(due.year, due.monthNumber, due.dayOfMonth)
        if (latestDate != null && (
                dueDate.year > latestDate.year ||
                    (
                        dueDate.year == latestDate.year && (
                            dueDate.monthNumber > latestDate.monthNumber ||
                                (dueDate.monthNumber == latestDate.monthNumber && dueDate.dayOfMonth > latestDate.dayOfMonth)
                        )
                    )
            )
        ) {
            return false
        }
        if (exactTime != null && (due.hour != exactTime.first || due.minute != exactTime.second)) {
            return false
        }
        return true
    }

    fun formatTaskSummary(task: TaskItem): String {
        val due = task.due
        return if (due != null) {
            val datePart = "%04d-%02d-%02d".format(due.year, due.monthNumber, due.dayOfMonth)
            val timePart = "%02d:%02d".format(due.hour, due.minute)
            "${task.title} ($datePart $timePart)"
        } else {
            task.title
        }
    }

    fun shouldPreferEmptyNotes(vararg sources: String?): Boolean {
        val combined =
            sources.filterNotNull()
                .joinToString(" ")
                .lowercase()
                .trim()
        if (combined.isEmpty()) return false
        val keywords =
            listOf(
                "without description",
                "without notes",
                "no description",
                "no notes",
                "missing description",
                "missing notes",
                "empty description",
                "empty notes",
            )
        return keywords.any { combined.contains(it) }
    }

    fun candidateScore(
        task: TaskItem,
        preferMissingNotes: Boolean,
        preferredDate: LocalDate?,
        preferredTime: Pair<Int, Int>?,
    ): Int {
        var score = 0
        if (preferMissingNotes && task.notes.isNullOrBlank()) score += 5
        if (preferredDate != null && task.due != null) {
            if (task.due.year == preferredDate.year &&
                task.due.monthNumber == preferredDate.monthNumber &&
                task.due.dayOfMonth == preferredDate.dayOfMonth
            ) {
                score += 3
            }
        }
        if (preferredTime != null && task.due != null) {
            if (task.due.hour == preferredTime.first && task.due.minute == preferredTime.second) {
                score += 2
            }
        }
        if (task.notes.isNullOrBlank()) score += 1
        return score
    }

    fun sortCandidates(
        tasks: List<TaskItem>,
        preferMissingNotes: Boolean,
        preferredDate: LocalDate?,
        preferredTime: Pair<Int, Int>?,
    ): List<TaskItem> {
        if (tasks.isEmpty()) return tasks
        return tasks.sortedWith(
            Comparator { a, b ->
                val scoreA = candidateScore(a, preferMissingNotes, preferredDate, preferredTime)
                val scoreB = candidateScore(b, preferMissingNotes, preferredDate, preferredTime)
                when {
                    scoreA != scoreB -> scoreB.compareTo(scoreA)
                    else -> a.title.compareTo(b.title, ignoreCase = true)
                }
            },
        )
    }

    var pendingSet = false
    var shouldClearPending = false
    val pendingEncoder = Json { encodeDefaults = true }

    actions.forEachIndexed { idx, action ->
        if (pendingSet) return@forEachIndexed
        when (action) {
            is Action.Remember -> {
                val content = action.remember.content.trim()
                if (content.isNotEmpty()) {
                    MemoryStore.add(content)
                    userMessages += "Got it - I'll remember that."
                    suppressNextUserMsg = true
                }
            }
            is Action.ClearMemory -> {
                MemoryStore.clear()
                userMessages += "Done. I've cleared your saved memory."
                suppressNextUserMsg = true
            }
            is Action.WebSearch -> {
                val query = action.web_search.query.trim()
                if (query.isEmpty()) return@forEachIndexed
                val limit = action.web_search.top_k?.coerceIn(1, 5) ?: 3
                val results = WebFetchApi.search(query, limit).getOrElse { error ->
                    userMessages += "I couldn't search the web just now (${error.message})."
                    suppressNextUserMsg = true
                    return@forEachIndexed
                }
                val formatted =
                    formatSearchResults(results).ifBlank {
                        "I didn't find any good results for \"$query\"."
                    }
                userMessages += formatted
                suppressNextUserMsg = true
            }
            is Action.FetchUrl -> {
                val url = action.fetch_url.url.trim()
                if (url.isEmpty()) return@forEachIndexed
                val maxChars = action.fetch_url.max_chars?.coerceIn(500, 4000) ?: 2000
                val content = WebFetchApi.fetch(url, maxChars).getOrElse { error ->
                    userMessages += "I couldn't fetch that page (${error.message})."
                    suppressNextUserMsg = true
                    return@forEachIndexed
                }
                val snippet = content.trim().take(maxChars)
                userMessages += "Here's a quick summary from $url:\n${snippet}"
                suppressNextUserMsg = true
            }
            is Action.AwaitUser -> {
                val question =
                    action.await_user.question.trim().ifBlank {
                        "I need a bit more detail before I proceed. Could you clarify?"
                    }
                PlanStore.savePending(
                    status = "await_user",
                    question = question,
                    context = action.await_user.context,
                    actionJson = null,
                )
                userMessages += question
                suppressNextUserMsg = true
                pendingSet = true
            }
            is Action.ExternalAction -> {
                val data = action.external_action
                val question =
                    data.confirmation_question?.trim().takeIf { !it.isNullOrBlank() }
                        ?: "I can help with ${data.intent} via ${data.provider}. Want me to proceed?"
                val actionJson =
                    runCatching {
                        pendingEncoder.encodeToString(ExternalActionData.serializer(), data)
                    }.getOrNull()
                PlanStore.savePending(
                    status = "external_action",
                    question = question,
                    context = data.intent,
                    actionJson = actionJson,
                )
                userMessages += question
                suppressNextUserMsg = true
                pendingSet = true
            }
            is Action.SendEmail -> {
                shouldClearPending = true
                val data = action.send_email
                val recipients = data.to.filter { it.isNotBlank() }
                if (recipients.isEmpty()) {
                    println("[$idx] send_email skipped: empty recipients.")
                    return@forEachIndexed
                }
                if (token.isNullOrBlank()) {
                    println("[$idx] send_email failed: missing Gmail token.")
                    failed += recipients.size
                    return@forEachIndexed
                }

                // 1) Ask UI for preview & edits
                val decision =
                    MailPreviewBus.request(
                        MailPreview(
                            subject = data.subject,
                            body = data.body,
                            recipients = recipients,
                        ),
                    )

                // 2) Act on user choice
                when (decision) {
                    is MailPreviewDecision.Cancel -> println("[$idx] send_email canceled by user.")
                    is MailPreviewDecision.Send -> {
                        val toList = decision.to
                        if (toList.isEmpty()) {
                            println("[$idx] send_email aborted: no 'To' recipients after edit.")
                            return@forEachIndexed
                        }

                        toList.forEach { toEmail ->
                            GmailApi.sendEmail(token, toEmail, decision.subject, decision.body)
                                .onSuccess {
                                    println("[$idx] send_email success → $toEmail (subject=\"${decision.subject}\")")
                                    sent++
                                    mailMutated = true
                                }
                                .onFailure { e ->
                                    println("[$idx] send_email failed → $toEmail : ${e.message}")
                                    failed++
                                }
                        }
                    }
                }
            }
            is Action.ExplainEmail -> {
                shouldClearPending = true
                println("[$idx] explain_email → email_id=${action.explain_email.email_id}")
            }
            is Action.ReplyToEmail -> {
                shouldClearPending = true
                println("[$idx] reply_to_email → email_id=${action.reply_to_email.email_id}")
            }
            is Action.ForwardEmail -> {
                shouldClearPending = true
                println("[$idx] forward_email → email_id=${action.forward_email.email_id}")
            }
            is Action.DeleteEmail -> {
                shouldClearPending = true
                println("[$idx] delete_email → email_id=${action.delete_email.email_id}")
            }
            is Action.UserMsg -> {
                println("[$idx] user_msg → ${action.user_msg.text}")
                val text = action.user_msg.text.trim()
                if (suppressNextUserMsg) {
                    println("[$idx] user_msg suppressed due to post-verification guard")
                    if (text.isNotEmpty()) {
                        if (userMessages.isNotEmpty()) {
                            userMessages[userMessages.lastIndex] = text
                        } else {
                            userMessages += text
                        }
                    }
                    suppressNextUserMsg = false
                } else if (text.isNotEmpty()) {
                    userMessages += text
                }
            }
            is Action.AddCalendarEvent -> {
                shouldClearPending = true
                val ev = action.add_calendar_event
                val contextLabel = "add_calendar_event#$idx"
                val date = runCatching { LocalDate.parse(ev.date) }.getOrNull()
                if (date == null) {
                    println("[$contextLabel] invalid date '${ev.date}'")
                    userMessages += "I couldn't schedule '${ev.title}' because the date looked invalid. Could you confirm it?"
                    suppressNextUserMsg = true
                    return@forEachIndexed
                }

                val canonical = canonicalizeTimeWindow(contextLabel, ev.start_time, ev.end_time, ev.duration_minutes)
                if (canonical == null) {
                    userMessages += "I wasn't able to parse the time window for '${ev.title}'. Mind sharing exact start and end?"
                    suppressNextUserMsg = true
                    return@forEachIndexed
                }

                if (token.isNullOrBlank()) {
                    println("[$contextLabel] missing Google token")
                    userMessages += "I need you to reconnect Google before I can add '${ev.title}' to your calendar."
                    suppressNextUserMsg = true
                    return@forEachIndexed
                }

                val startDt =
                    LocalDateTime(
                        date.year,
                        date.monthNumber,
                        date.dayOfMonth,
                        canonical.startMinutes / 60,
                        canonical.startMinutes % 60,
                    )
                val endDt =
                    LocalDateTime(
                        date.year,
                        date.monthNumber,
                        date.dayOfMonth,
                        canonical.endMinutes / 60,
                        canonical.endMinutes % 60,
                    )

                val mutationKey = generateIdempotencyKey()
                val createResult =
                    withCalendarRetry(contextLabel, mutationKey) {
                        CalendarApi.createEvent(
                            accessToken = token,
                            title = ev.title,
                            start = startDt,
                            end = endDt,
                            location = ev.location,
                            notes = ev.notes,
                        )
                    }

                val eventId =
                    createResult.getOrElse { error ->
                        println("[$contextLabel] create failed: ${error.message}")
                        userMessages += "I tried to schedule '${ev.title}' but Google Calendar returned an error (${error.message}). Want me to retry?"
                        suppressNextUserMsg = true
                        return@forEachIndexed
                    }

                println(
                    "[$contextLabel] created eventId=$eventId for '${ev.title}' on ${ev.date} ${canonical.startLabel}-${canonical.endLabel}",
                )

                val matches = listEventsForDay(token, date, ev.title)
                val verified =
                    matches.firstOrNull { it.id == eventId } ?: matches.firstOrNull { candidate ->
                        candidate.title.equals(ev.title, ignoreCase = true) &&
                            candidate.start.hour == startDt.hour && candidate.start.minute == startDt.minute &&
                            candidate.end.hour == endDt.hour && candidate.end.minute == endDt.minute
                    }

                if (verified == null) {
                    println("[$contextLabel] verification failed for eventId=$eventId")
                    userMessages += "I attempted to add '${ev.title}' on ${ev.date} ${formatRange(startDt, endDt)}, but I couldn't confirm it in Google Calendar. Should I try again or adjust the details?"
                    suppressNextUserMsg = true
                    return@forEachIndexed
                }

                calendarMutated = true
                val userMessage = "Scheduled '${verified.title}' on ${ev.date} ${formatRange(
                    verified.start,
                    verified.end,
                )}. Need any tweaks?"
                userMessages += userMessage
                suppressNextUserMsg = true

                // Mirror as task only after verification
                val taskTitle = verified.title
                val taskNotes =
                    buildString {
                        if (!ev.notes.isNullOrBlank()) append(ev.notes)
                        if (!ev.location.isNullOrBlank()) {
                            if (isNotEmpty()) append("\n")
                            append("Location: ").append(ev.location)
                        }
                        if (!ev.assumptions.isNullOrBlank()) {
                            if (isNotEmpty()) append("\n")
                            append("Assumptions: ").append(ev.assumptions)
                        }
                        if (isNotEmpty()) append("\n")
                        append("Calendar event id: ").append(eventId)
                    }
                val deadlineKeywords =
                    listOf(
                        "deadline", "submit", "submission", "deliver", "deliverable", "handoff",
                        "handover", "final", "due", "review", "report", "proposal", "presentation",
                        "invoice", "milestone",
                    )
                val shouldMirrorTask =
                    run {
                        val titleLower = verified.title.lowercase()
                        val notesLower = ev.notes?.lowercase().orEmpty()
                        deadlineKeywords.any { keyword ->
                            titleLower.contains(keyword) || notesLower.contains(keyword)
                        }
                    }

                if (shouldMirrorTask) {
                    TasksApi.createTask(
                        accessToken = token,
                        title = taskTitle,
                        notes = taskNotes,
                        due = verified.end,
                    ).onSuccess { id ->
                        println("[$contextLabel] mirrored taskId=$id for eventId=$eventId")
                        tasksMutated = true
                    }.onFailure { e ->
                        println("[$contextLabel] task mirror failed: ${e.message}")
                    }
                } else {
                    println(
                        "[$contextLabel] skipped task mirror for '${verified.title}' (no deadline keywords detected)",
                    )
                }
            }
            is Action.UpdateCalendarEvent -> {
                shouldClearPending = true
                val data = action.update_calendar_event
                val contextLabel = "update_calendar_event#$idx"

                if (token.isNullOrBlank()) {
                    println("[$contextLabel] missing Google token")
                    userMessages += "I need Google access before I can update that calendar event."
                    suppressNextUserMsg = true
                    return@forEachIndexed
                }

                val tokenValue = token!!

                val eventIdRaw = data.event_id?.trim()?.takeIf { it.isNotEmpty() }
                val matchTitle = data.match_title?.trim()?.takeIf { it.isNotEmpty() }
                val matchDate = parseDateOrNull(data.match_date)
                val newDateOverride = parseDateOrNull(data.new_date)
                val matchStartMinutes =
                    data.match_start_time?.let {
                        canonicalizeTimeValue(
                            it,
                            contextLabel,
                            "match_start",
                        )?.first
                    }

                val datesToInspect =
                    linkedSetOf<LocalDate>().apply {
                        matchDate?.let { ref ->
                            add(ref.minus(DatePeriod(days = 1)))
                            add(ref)
                            add(ref.plus(DatePeriod(days = 1)))
                        }
                        newDateOverride?.let { ref ->
                            add(ref.minus(DatePeriod(days = 1)))
                            add(ref)
                            add(ref.plus(DatePeriod(days = 1)))
                        }
                    }
                if (datesToInspect.isEmpty()) {
                    val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
                    datesToInspect += today.minus(DatePeriod(days = 1))
                    datesToInspect += today
                    datesToInspect += today.plus(DatePeriod(days = 1))
                }

                var targetEvent: CalendarEvent? = null
                if (eventIdRaw != null) {
                    targetEvent = findEventById(tokenValue, eventIdRaw, datesToInspect.toList())
                    if (targetEvent == null) {
                        println(
                            "[$contextLabel] eventId=$eventIdRaw not resolved via hint dates; falling back to matching",
                        )
                    }
                }

                val aggregated = linkedMapOf<String, CalendarEvent>()
                datesToInspect.forEach { day ->
                    listEventsForDay(tokenValue, day, null).forEach { event ->
                        aggregated.putIfAbsent(event.id, event)
                    }
                }
                val candidateEvents = aggregated.values.toList()

                if (targetEvent == null && eventIdRaw != null) {
                    targetEvent = candidateEvents.firstOrNull { it.id == eventIdRaw }
                }

                if (targetEvent == null) {
                    if (candidateEvents.isEmpty()) {
                        userMessages += "I couldn't find any calendar events near that time to update. Could you share a bit more detail?"
                        suppressNextUserMsg = true
                        return@forEachIndexed
                    }

                    val scored =
                        candidateEvents.map { event ->
                            val titleScore = matchTitle?.let { eventTitleSimilarityScore(event.title, it) } ?: 0
                            val timeScore =
                                matchStartMinutes?.let { targetMinutes ->
                                    val eventMinutes = event.start.hour * 60 + event.start.minute
                                    timeProximityScore(targetMinutes, eventMinutes)
                                } ?: 0
                            val dateScore =
                                matchDate?.let { desired ->
                                    val delta = desired.daysUntil(event.start.date)
                                    when (abs(delta)) {
                                        0 -> 40
                                        1 -> 25
                                        2 -> 10
                                        else -> 0
                                    }
                                } ?: 0
                            val total = titleScore + timeScore + dateScore
                            Triple(event, total, timeScore)
                        }

                    val bestScore = scored.maxOfOrNull { it.second } ?: 0
                    if (matchTitle != null && bestScore < 25) {
                        userMessages += "I couldn't confidently match '$matchTitle' to any event. Could you clarify the title or time?"
                        suppressNextUserMsg = true
                        return@forEachIndexed
                    }

                    val topCandidates = scored.filter { it.second == bestScore && bestScore > 0 }
                    targetEvent =
                        when {
                            bestScore <= 0 -> scored.maxByOrNull { it.second }?.first
                            topCandidates.size == 1 -> topCandidates.first().first
                            else -> {
                                val chosen =
                                    CalendarConfirmationBus.request(
                                        topCandidates.map { it.first },
                                        reason = "Multiple events matched '${matchTitle ?: "your description"}'",
                                        confirmLabel = "Use this",
                                        cancelLabel = "Skip",
                                    )
                                if (chosen == null) {
                                    userMessages += "I wasn't sure which event to adjust. Could you specify the title or time?"
                                    suppressNextUserMsg = true
                                    return@forEachIndexed
                                }
                                chosen
                            }
                        }
                }

                if (targetEvent == null) {
                    userMessages += "I couldn't identify the calendar event to update. Could you share the exact title or time?"
                    suppressNextUserMsg = true
                    return@forEachIndexed
                }

                val targetDate = newDateOverride ?: targetEvent.start.date
                val wantsTimeChange = data.new_start_time != null || data.new_end_time != null || data.new_duration_minutes != null
                val wantsDateChange = data.new_date != null
                val titleUpdate = data.new_title?.takeIf { it.isNotBlank() }
                val locationUpdate = data.new_location?.takeIf { it.isNotBlank() }
                val notesUpdate = data.new_notes?.takeIf { it.isNotBlank() }

                if (!wantsTimeChange && !wantsDateChange && titleUpdate == null && locationUpdate == null && notesUpdate == null) {
                    userMessages += "I didn't see any new details to update for '${targetEvent.title}'. Want me to adjust the time or title?"
                    suppressNextUserMsg = true
                    return@forEachIndexed
                }

                val startSourceLabel = targetEvent.start.formatHm()
                val endSourceLabel = targetEvent.end.formatHm()
                val existingDurationMinutes =
                    ((targetEvent.end.hour * 60 + targetEvent.end.minute) - (targetEvent.start.hour * 60 + targetEvent.start.minute)).coerceAtLeast(
                        5,
                    )

                val startRaw = data.new_start_time ?: startSourceLabel
                val endRaw = data.new_end_time ?: if (data.new_duration_minutes != null) null else endSourceLabel
                val canonical =
                    if (wantsTimeChange) {
                        canonicalizeTimeWindow(
                            contextLabel,
                            startRaw,
                            endRaw,
                            data.new_duration_minutes ?: existingDurationMinutes,
                        )
                    } else {
                        null
                    }

                if (wantsTimeChange && canonical == null) {
                    userMessages += "I couldn't parse the new time window. Could you restate the start and end?"
                    suppressNextUserMsg = true
                    return@forEachIndexed
                }

                val newStart =
                    canonical?.let {
                        LocalDateTime(targetDate.year, targetDate.monthNumber, targetDate.dayOfMonth, it.startMinutes / 60, it.startMinutes % 60)
                    } ?: if (wantsDateChange) {
                        LocalDateTime(targetDate.year, targetDate.monthNumber, targetDate.dayOfMonth, targetEvent.start.hour, targetEvent.start.minute)
                    } else {
                        targetEvent.start
                    }

                val newEnd =
                    canonical?.let {
                        LocalDateTime(targetDate.year, targetDate.monthNumber, targetDate.dayOfMonth, it.endMinutes / 60, it.endMinutes % 60)
                    } ?: if (wantsDateChange) {
                        LocalDateTime(targetDate.year, targetDate.monthNumber, targetDate.dayOfMonth, targetEvent.end.hour, targetEvent.end.minute)
                    } else {
                        targetEvent.end
                    }

                val shouldUpdateTimes = wantsTimeChange || wantsDateChange

                val mutationKey = generateIdempotencyKey()
                val updateResult =
                    withCalendarRetry(contextLabel, mutationKey) {
                        CalendarApi.updateEvent(
                            accessToken = tokenValue,
                            eventId = targetEvent.id,
                            newStart = if (shouldUpdateTimes) newStart else null,
                            newEnd = if (shouldUpdateTimes) newEnd else null,
                            newTitle = titleUpdate,
                            newLocation = locationUpdate,
                            newNotes = notesUpdate,
                        )
                    }

                updateResult.onFailure { error ->
                    println("[$contextLabel] update failed: ${error.message}")
                }

                updateResult.getOrElse { error ->
                    userMessages += "I hit an error updating '${targetEvent.title}': ${error.message}. Want me to try again?"
                    suppressNextUserMsg = true
                    return@forEachIndexed
                }

                val verificationDates =
                    mutableSetOf<LocalDate>().apply {
                        add(newStart.date)
                        val originalDate = targetEvent.start.date
                        if (originalDate != newStart.date) add(originalDate)
                    }

                val verifiedEvent =
                    findEventById(tokenValue, targetEvent.id, verificationDates.toList())
                        ?: verificationDates.firstNotNullOfOrNull { day ->
                            listEventsForDay(tokenValue, day, null).firstOrNull { it.id == targetEvent.id }
                        }

                if (verifiedEvent == null) {
                    println("[$contextLabel] verification failed post-update for id=${targetEvent.id}")
                    userMessages += "I updated the event, but couldn't confirm the new details in Google Calendar. Could you check and let me know?"
                    suppressNextUserMsg = true
                    return@forEachIndexed
                }

                val expectedTitle = titleUpdate ?: targetEvent.title
                val titleOk = verifiedEvent.title == expectedTitle
                val timesOk =
                    if (shouldUpdateTimes) {
                        verifiedEvent.start == newStart && verifiedEvent.end == newEnd
                    } else {
                        true
                    }
                if (!titleOk || !timesOk) {
                    val actualRange = formatRange(verifiedEvent.start, verifiedEvent.end)
                    userMessages += "Google shows '${verifiedEvent.title}' at ${verifiedEvent.start.date} $actualRange. Want me to adjust again?"
                    suppressNextUserMsg = true
                    return@forEachIndexed
                }

                val finalRange = formatRange(verifiedEvent.start, verifiedEvent.end)
                val finalDate = verifiedEvent.start.date
                calendarMutated = true
                userMessages += "Rescheduled '${verifiedEvent.title}' to $finalRange on $finalDate."
                suppressNextUserMsg = true
            }

            is Action.DeleteCalendarEvent -> {
                shouldClearPending = true
                val ev = action.delete_calendar_event
                val contextLabel = "delete_calendar_event#$idx"
                val assumptionsRaw = ev.assumptions
                val assumptions = assumptionsRaw.orEmpty()
                val rawTitleFilter = ev.title?.takeIf { it.isNotBlank() }?.trim()
                val treatAsBulkClear = isGenericDeleteAllCalendarIntent(rawTitleFilter, assumptionsRaw)
                val titleFilter = if (treatAsBulkClear) null else rawTitleFilter
                val dateStrDetected = ev.date ?: Regex("(\\d{4}-\\d{2}-\\d{2})").find(assumptions)?.groupValues?.getOrNull(1)
                val startTimeRaw = if (treatAsBulkClear) null else ev.start_time ?: Regex("\\b(\\d{2}:\\d{2})\\b").find(assumptions)?.groupValues?.getOrNull(1)

                val date =
                    when {
                        !dateStrDetected.isNullOrBlank() -> runCatching { LocalDate.parse(dateStrDetected) }.getOrNull()
                        treatAsBulkClear -> Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
                        else -> null
                    }

                if (date == null) {
                    println("[$contextLabel] no valid date provided for delete request")
                    userMessages += "I need the date to remove that calendar event. Could you share it?"
                    suppressNextUserMsg = true
                    return@forEachIndexed
                }

                val dateLabel = dateStrDetected ?: date.toString()

                if (token.isNullOrBlank()) {
                    println("[$contextLabel] missing Google token")
                    userMessages += "I need Google access before I can remove that event."
                    suppressNextUserMsg = true
                    return@forEachIndexed
                }

                val startTimeCanonical = startTimeRaw?.let { canonicalizeTimeValue(it, contextLabel, "start") }
                val startLabel = startTimeCanonical?.second

                fun matchesFilter(event: CalendarEvent): Boolean {
                    val titleOk = titleFilter?.let { event.title.contains(it, ignoreCase = true) } ?: true
                    val timeOk =
                        startTimeCanonical?.let { (minutes, _) ->
                            val eventMinutes = event.start.hour * 60 + event.start.minute
                            eventMinutes == minutes
                        } ?: true
                    return titleOk && timeOk && event.start.date == date
                }

                val eventIdFromAssumptions =
                    Regex("""event[_-]?id\s*[:=]\s*([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE)
                        .find(assumptions)?.groupValues?.getOrNull(1)

                val beforeEvents = listEventsForDay(token, date, titleFilter)

                val resolvedEventForId: CalendarEvent? =
                    if (!eventIdFromAssumptions.isNullOrBlank()) {
                        beforeEvents.firstOrNull {
                            it.id == eventIdFromAssumptions
                        } ?: findEventById(token, eventIdFromAssumptions, listOf(date))
                    } else {
                        null
                    }

                if (eventIdFromAssumptions.isNullOrBlank() && beforeEvents.none { matchesFilter(it) }) {
                    val message =
                        if (treatAsBulkClear) {
                            "There aren't any events on $dateLabel, so there's nothing to clear."
                        } else {
                            "I couldn't locate ${titleFilter?.let { "'$it'" } ?: "that event"} on $dateLabel. Could you confirm the time?"
                        }
                    userMessages += message
                    suppressNextUserMsg = true
                    return@forEachIndexed
                }

                val mutationKey = generateIdempotencyKey()
                val deleteResult =
                    if (!eventIdFromAssumptions.isNullOrBlank()) {
                        withCalendarRetry(contextLabel, mutationKey) {
                            CalendarApi.deleteEventById(token, eventIdFromAssumptions)
                        }
                    } else {
                        val (startOfDay, endOfDay) = dayRange(date)
                        withCalendarRetry(contextLabel, mutationKey) {
                            CalendarApi.deleteEventsInRange(
                                accessToken = token,
                                start = startOfDay,
                                end = endOfDay,
                                titleFilter = titleFilter,
                                startTimeFilter = startLabel,
                            ).map { }
                        }
                    }

                deleteResult.onFailure { error ->
                    println("[$contextLabel] delete failed: ${error.message}")
                }

                deleteResult.getOrElse { error ->
                    userMessages += "I couldn't remove that event: ${error.message}. Want me to try again?"
                    suppressNextUserMsg = true
                    return@forEachIndexed
                }

                val verificationDates =
                    buildSet {
                        add(date)
                        resolvedEventForId?.start?.date?.let { add(it) }
                    }

                val afterEventsByDate = verificationDates.associateWith { d -> listEventsForDay(token, d, titleFilter) }

                val verificationPassed =
                    if (!eventIdFromAssumptions.isNullOrBlank()) {
                        val existedBefore =
                            verificationDates.any { d ->
                                (
                                    if (d == date) {
                                        beforeEvents
                                    } else {
                                        listEventsForDay(
                                            token,
                                            d,
                                            titleFilter,
                                        )
                                    }
                                ).any { it.id == eventIdFromAssumptions }
                            }
                        val existsAfter =
                            afterEventsByDate.any {
                                    (_, list) ->
                                list.any { it.id == eventIdFromAssumptions }
                            }
                        existedBefore && !existsAfter
                    } else {
                        val beforeMatches = beforeEvents.count { matchesFilter(it) }
                        val afterMatches = afterEventsByDate[date]?.count { matchesFilter(it) } ?: 0
                        afterMatches < beforeMatches
                    }

                if (!verificationPassed) {
                    val stillThere = afterEventsByDate.values.asSequence().flatten().firstOrNull { matchesFilter(it) }
                    val description =
                        when {
                            stillThere != null -> "'${stillThere.title}' at ${stillThere.start.formatHm()}"
                            treatAsBulkClear -> "those events"
                            else -> "that event"
                        }
                    userMessages += "I tried to delete $description on $dateLabel, but it still shows in Google Calendar. Should I try again or pick a different one?"
                    suppressNextUserMsg = true
                    return@forEachIndexed
                }

                val summaryTarget =
                    when {
                        treatAsBulkClear -> "all events"
                        titleFilter != null -> "'$titleFilter'"
                        else -> beforeEvents.firstOrNull()?.let { "'${it.title}'" } ?: "that event"
                    }
                val timeSummary = if (treatAsBulkClear) "" else startLabel?.let { " at $it" } ?: ""
                calendarMutated = true
                userMessages += "Removed $summaryTarget on $dateLabel$timeSummary."
                suppressNextUserMsg = true
            }
            is Action.AddTask -> {
                shouldClearPending = true
                val task = action.add_task
                val title = task.title.trim()
                if (title.isEmpty()) {
                    println("[$idx] add_task skipped: empty title.")
                    return@forEachIndexed
                }
                if (token.isNullOrBlank()) {
                    println("[$idx] add_task failed: missing Google token.")
                    return@forEachIndexed
                }

                val dueDate = parseDateOrNull(task.due_date)
                val dueTime = parseTimeOrNull(task.due_time)
                if (dueTime != null && dueDate == null) {
                    println("[$idx] add_task skipped: due_time requires due_date.")
                    return@forEachIndexed
                }

                val due = toLocalDateTime(dueDate, dueTime)

                TasksApi.createTask(
                    accessToken = token,
                    title = title,
                    notes = task.notes,
                    due = due,
                ).onSuccess { id ->
                    println("[$idx] add_task created → taskId=$id title=\"$title\"")
                    tasksMutated = true
                }.onFailure { e ->
                    println("[$idx] add_task failed: ${e.message}")
                }
            }
            is Action.UpdateTask -> {
                shouldClearPending = true
                val data = action.update_task
                if (token.isNullOrBlank()) {
                    println("[$idx] update_task failed: missing Google token.")
                    return@forEachIndexed
                }

                val matchDate = parseDateOrNull(data.match_due_date)
                val matchTime = parseTimeOrNull(data.match_due_time)
                val requireDetails = data.new_due_time != null && data.new_due_date == null
                val preferMissingNotes = shouldPreferEmptyNotes(data.assumptions, data.match_title)

                val rawCandidates: List<TaskItem> =
                    if (data.task_id != null) {
                        val resolved =
                            resolveTaskReference(
                                idx = idx,
                                actionName = "update_task",
                                tokenNonNull = token,
                                taskIdRaw = data.task_id,
                                matchTitle = data.match_title,
                                matchDate = matchDate,
                                matchTime = matchTime,
                                requireTaskDetails = true,
                            )
                        if (resolved != null) {
                            val (resolvedId, resolvedTask) = resolved
                            val task = resolvedTask ?: cachedTasksForOps?.firstOrNull { it.id == resolvedId }
                            task?.let { listOf(it) } ?: listOf(TaskItem(resolvedId, data.match_title ?: ""))
                        } else {
                            emptyList()
                        }
                    } else {
                        val tasks = loadTasksForOps(token)
                        if (!data.match_title.isNullOrBlank()) {
                            tasks.filter {
                                    task ->
                                titleMatches(task, data.match_title!!) && dueMatchesForDelete(task, matchDate, matchTime)
                            }
                        } else {
                            tasks
                        }
                    }

                val candidatesList = sortCandidates(rawCandidates, preferMissingNotes, matchDate, matchTime)

                if (candidatesList.isEmpty()) {
                    println("[$idx] update_task skipped: no matching task for update.")
                    return@forEachIndexed
                }

                val targetTask = candidatesList.first()
                val taskId = targetTask.id
                val existingTask = targetTask

                val newDueDate = parseDateOrNull(data.new_due_date)
                val newDueTime = parseTimeOrNull(data.new_due_time)

                val due =
                    when {
                        newDueDate != null -> toLocalDateTime(newDueDate, newDueTime)
                        newDueTime != null -> {
                            val baseDate = existingTask?.due?.let { LocalDate(it.year, it.monthNumber, it.dayOfMonth) }
                            if (baseDate == null) {
                                println(
                                    "[$idx] update_task skipped: new_due_time provided but current due date unknown for taskId=$taskId.",
                                )
                                null
                            } else {
                                toLocalDateTime(baseDate, newDueTime)
                            }
                        }
                        else -> null
                    }

                if (data.new_title == null && data.new_notes == null && due == null && data.completed == null) {
                    println("[$idx] update_task skipped: nothing to update for taskId=$taskId.")
                    return@forEachIndexed
                }

                TasksApi.pushTaskChanges(
                    accessToken = token,
                    taskId = taskId,
                    title = data.new_title,
                    notes = data.new_notes,
                    due = due,
                    completed = data.completed,
                ).onSuccess {
                    println("[$idx] update_task success → taskId=$taskId")
                    tasksMutated = true
                }.onFailure { e ->
                    println("[$idx] update_task failed → taskId=$taskId : ${e.message}")
                }
            }
            is Action.DeleteTask -> {
                shouldClearPending = true
                val data = action.delete_task
                if (token.isNullOrBlank()) {
                    println("[$idx] delete_task failed: missing Google token.")
                    return@forEachIndexed
                }

                val tokenValue = token!!

                val explicitId = data.task_id?.trim()?.takeIf { it.isNotEmpty() }
                val deleteAll =
                    data.delete_all == true ||
                        (data.match_title?.trim()?.equals("all", ignoreCase = true) == true) ||
                        (data.assumptions?.contains("delete all", ignoreCase = true) == true)

                val tasks = loadTasksForOps(tokenValue)
                if (tasks.isEmpty()) {
                    println("[$idx] delete_task skipped: no tasks available.")
                    return@forEachIndexed
                }

                val matchDate = parseDateOrNull(data.match_due_date)
                val matchTime = parseTimeOrNull(data.match_due_time)
                val matchTitle = data.match_title?.trim()
                val preferMissingNotes = shouldPreferEmptyNotes(data.assumptions, matchTitle)

                val candidates: List<TaskItem> =
                    when {
                        deleteAll -> tasks
                        explicitId != null -> {
                            val matchFromList = tasks.filter { it.id == explicitId }
                            if (matchFromList.isNotEmpty()) {
                                matchFromList
                            } else {
                                val placeholderTitle = data.match_title?.takeIf { it.isNotBlank() } ?: "Selected reminder"
                                listOf(TaskItem(id = explicitId, title = placeholderTitle))
                            }
                        }
                        !matchTitle.isNullOrEmpty() ->
                            tasks.filter { task ->
                                titleMatches(task, matchTitle) && dueMatchesForDelete(task, matchDate, matchTime)
                            }
                        else -> emptyList()
                    }

                if (candidates.isEmpty()) {
                    println(
                        "[$idx] delete_task skipped: no task matched title '${matchTitle ?: explicitId ?: "<unspecified>"}'.",
                    )
                    return@forEachIndexed
                }

                val orderedCandidates = sortCandidates(candidates, preferMissingNotes, matchDate, matchTime)

                val reason =
                    if (orderedCandidates.size == 1) {
                        val task = orderedCandidates.first()
                        val dueSummary =
                            task.due?.let {
                                val time = "%02d:%02d".format(it.hour, it.minute)
                                val date = "%04d-%02d-%02d".format(it.year, it.monthNumber, it.dayOfMonth)
                                "scheduled at $time on $date"
                            } ?: "with no due date"
                        "Delete reminder \"${task.title}\" $dueSummary?"
                    } else {
                        "Delete ${orderedCandidates.size} reminders that look related to your request?"
                    }

                val confirmed =
                    TaskConfirmationBus.request(
                        tasks = orderedCandidates,
                        reason = reason,
                        confirmLabel = "Delete",
                        cancelLabel = "Keep",
                    )

                if (!confirmed) {
                    println("[$idx] delete_task canceled by user confirmation.")
                    userMessages += "I highlighted the reminders I found—let me know if you'd still like me to delete them."
                    return@forEachIndexed
                }

                orderedCandidates.forEach { task ->
                    TasksApi.deleteTask(accessToken = tokenValue, taskId = task.id)
                        .onSuccess {
                            println("[$idx] delete_task success → taskId=${task.id} title=\"${task.title}\"")
                            cachedTasksForOps = cachedTasksForOps?.filterNot { it.id == task.id }
                            tasksMutated = true
                        }
                        .onFailure { e -> println("[$idx] delete_task failed → taskId=${task.id} : ${e.message}") }
                }

                if (deleteAll || orderedCandidates.size > 1) {
                    cachedTasksForOps = null // refresh for future actions after bulk delete
                }
            }
        }
    }

    if (!pendingSet && shouldClearPending) {
        PlanStore.clearPending()
    }

    val refreshSignals =
        RefreshSignals(
            refreshMail = mailMutated,
            refreshCalendar = calendarMutated,
            refreshTasks = tasksMutated,
            refreshSummary = mailMutated || calendarMutated || tasksMutated,
        )

    return GeminiRunResult(
        summary = ExecutionSummary(sentEmails = sent, failedEmails = failed),
        userMessages = userMessages,
        refreshSignals = refreshSignals,
    )
}

// ───────────────────── JSON Array Extraction ───────────────────── //

private val JSON_FENCE_REGEX = Regex("```(?:json)?\\s*([\\s\\S]*?)\\s*```", RegexOption.IGNORE_CASE)
private val JSON_ARRAY_FENCE_REGEX =
    Regex(
        "```(?:json)?\\s*(\\[.*?\\])\\s*```",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

/**
 * Extract a JSON array string from a Gemini response that may include code fences or extra prose.
 */
private fun extractJsonArray(text: String): String? {
    // 1) Prefer fenced JSON arrays
    JSON_ARRAY_FENCE_REGEX.find(text)?.groupValues?.getOrNull(1)?.trim()?.let { return it }

    // 2) Any fenced block that looks like an array
    JSON_FENCE_REGEX.find(text)?.groupValues?.getOrNull(1)?.trim()?.let { block ->
        if (block.startsWith('[') && block.endsWith(']')) return block
    }

    // 3) Fallback: bracket depth scan
    val start = text.indexOf('[')
    if (start == -1) return null
    var depth = 0
    for (i in start until text.length) {
        when (text[i]) {
            '[' -> depth++
            ']' -> {
                depth--
                if (depth == 0) return text.substring(start, i + 1).trim()
            }
        }
    }
    return null
}

// Builds a friendly assistant message summarizing intended actions from the model's JSON and execution summary.
fun buildAssistantMessageFromResponse(
    raw: String?,
    exec: ExecutionSummary,
    userMessages: List<String> = emptyList(),
): String {
    if (userMessages.isNotEmpty()) {
        return userMessages.last()
    }

    if (raw.isNullOrBlank()) return "I didn't detect any actions to take. Let me know how I can help."
    val jsonArrayStr = runCatching { extractJsonArray(raw) }.getOrNull()
    val actions: List<Action> =
        if (jsonArrayStr != null) {
            runCatching {
                parseJSON(jsonArrayStr)
            }.getOrNull() ?: emptyList()
        } else {
            emptyList()
        }

    var addCal = 0
    var delCal = 0
    var updateCal = 0
    var explain = 0
    var replies = 0
    var forwards = 0
    var deletes = 0
    var addTasks = 0
    var updateTasks = 0
    var deleteTasks = 0

    actions.forEach { a ->
        when (a) {
            is Action.AddCalendarEvent -> addCal++
            is Action.UpdateCalendarEvent -> updateCal++
            is Action.DeleteCalendarEvent -> delCal++
            is Action.ExplainEmail -> explain++
            is Action.ReplyToEmail -> replies++
            is Action.ForwardEmail -> forwards++
            is Action.DeleteEmail -> deletes++
            is Action.SendEmail -> { /* counted via exec */ }
            is Action.UserMsg -> { /* conversational, not counted */ }
            is Action.AddTask -> addTasks++
            is Action.UpdateTask -> updateTasks++
            is Action.DeleteTask -> deleteTasks++
            is Action.AwaitUser -> { /* awaiting clarification */ }
            is Action.ExternalAction -> { /* external actions require confirmation */ }
            is Action.Remember -> { /* memory action */ }
            is Action.ClearMemory -> { /* memory action */ }
            is Action.WebSearch -> { /* web search */ }
            is Action.FetchUrl -> { /* url fetch */ }
        }
    }

    val parts = mutableListOf<String>()
    if (addCal > 0) parts += "added $addCal calendar event${if (addCal == 1) "" else "s"}"
    if (updateCal > 0) parts += "updated $updateCal calendar event${if (updateCal == 1) "" else "s"}"
    if (delCal > 0) parts += "removed $delCal calendar event${if (delCal == 1) "" else "s"}"
    if (exec.sentEmails > 0) parts += "sent ${exec.sentEmails} email${if (exec.sentEmails == 1) "" else "s"}"
    if (exec.failedEmails > 0) parts += "(${exec.failedEmails} email${if (exec.failedEmails == 1) "" else "s"} failed)"
    if (replies > 0) parts += "prepared $replies email repl${if (replies == 1) "y" else "ies"}"
    if (forwards > 0) parts += "prepared $forwards forward${if (forwards == 1) "" else "s"}"
    if (deletes > 0) parts += "deleted $deletes email${if (deletes == 1) "" else "s"}"
    if (addTasks > 0) parts += "created $addTasks task${if (addTasks == 1) "" else "s"}"
    if (updateTasks > 0) parts += "updated $updateTasks task${if (updateTasks == 1) "" else "s"}"
    if (deleteTasks > 0) parts += "removed $deleteTasks task${if (deleteTasks == 1) "" else "s"}"

    return if (parts.isEmpty()) {
        "I can line up the next steps for you. Want me to go ahead?"
    } else {
        val list = parts.joinToString(", ")
        "Done and dusted - I $list. Want me to tweak anything?"
    }
}

private val TASK_TITLE_STOPWORDS =
    setOf(
        "a", "an", "the", "to", "at", "on", "in", "for", "with", "and", "or", "of", "my", "your", "their",
    )

private fun normalizeTaskTokens(input: String): List<String> {
    if (input.isBlank()) return emptyList()
    val normalized =
        java.text.Normalizer.normalize(input, java.text.Normalizer.Form.NFD)
            .replace("\\p{Mn}".toRegex(), "")
            .lowercase()
            .replace("[^a-z0-9 ]".toRegex(), " ")
    return normalized.split(' ')
        .mapNotNull { raw ->
            val token = raw.trim()
            if (token.isEmpty() || token in TASK_TITLE_STOPWORDS) null else token
        }
}

private fun normalizedTaskString(input: String): String =
    java.text.Normalizer.normalize(input, java.text.Normalizer.Form.NFD)
        .replace("\\p{Mn}".toRegex(), "")
        .lowercase()
        .replace("[^a-z0-9 ]".toRegex(), " ")
        .replace(" +".toRegex(), " ")
        .trim()

// ───────────────────── Time Canonicalization Helpers ───────────────────── //

private data class CanonicalizedTimeWindow(
    val startMinutes: Int,
    val endMinutes: Int,
    val startLabel: String,
    val endLabel: String,
)

private fun canonicalizeTimeWindow(
    context: String,
    startTimeRaw: String,
    endTimeRaw: String?,
    durationMinutes: Int?,
): CanonicalizedTimeWindow? {
    val (startMinutes, startLabel) = canonicalizeTimeValue(startTimeRaw, context, "start") ?: return null

    val endPair: Pair<Int, String> =
        when {
            !endTimeRaw.isNullOrBlank() ->
                canonicalizeTimeValue(endTimeRaw, context, "end")
                    ?.let { (value, label) ->
                        if (value < startMinutes) {
                            println("[$context] adjusted end time up to match start ($label < $startLabel); using start+5m")
                            val bumped = (startMinutes + 5).coerceAtMost(23 * 60 + 59)
                            bumped to minutesToLabel(bumped)
                        } else {
                            value to label
                        }
                    }
            durationMinutes != null && durationMinutes > 0 -> {
                val candidate = startMinutes + durationMinutes
                if (candidate >= 24 * 60) {
                    println("[$context] duration overflow (${durationMinutes}m) from $startLabel; clamping end to 23:59")
                    (23 * 60 + 59) to "23:59"
                } else {
                    val rounded = roundToNearestFive(candidate)
                    val final = rounded.coerceAtMost(23 * 60 + 55)
                    val label = minutesToLabel(final)
                    if (final != candidate) println("[$context] duration-derived end ${minutesToLabel(candidate)} → $label")
                    final to label
                }
            }
            else -> {
                val fallback = (startMinutes + 60).coerceAtMost(23 * 60 + 59)
                if (fallback == startMinutes) {
                    println("[$context] unable to infer end time; start at day boundary $startLabel")
                }
                fallback to minutesToLabel(fallback)
            }
        } ?: return null

    val (endMinutes, endLabel) = endPair

    return CanonicalizedTimeWindow(startMinutes, endMinutes, startLabel, endLabel)
}

private fun canonicalizeTimeValue(
    raw: String,
    context: String,
    label: String,
): Pair<Int, String>? {
    val trimmed = raw.trim()
    val parts = trimmed.split(":")
    if (parts.size != 2) {
        println("[$context] $label time '$raw' malformed (expected HH:MM)")
        return null
    }
    val hours = parts[0].toIntOrNull()
    val minutes = parts[1].toIntOrNull()
    if (hours == null || minutes == null) {
        println("[$context] $label time '$raw' contained non-numeric parts")
        return null
    }
    val total = hours * 60 + minutes
    var rounded = roundToNearestFive(total)
    if (rounded < 0) {
        println("[$context] $label time '$raw' underflow → 00:00")
        rounded = 0
    }
    if (rounded >= 24 * 60) {
        println("[$context] $label time '$raw' overflow → 23:55")
        rounded = 23 * 60 + 55
    }
    if (rounded != total) {
        println("[$context] $label time '$raw' canonicalized → ${minutesToLabel(rounded)}")
    }
    return rounded to minutesToLabel(rounded)
}

private fun roundToNearestFive(minutes: Int): Int {
    val remainder = minutes % 5
    return if (remainder < 0) minutes - remainder else minutes + if (remainder >= 3) 5 - remainder else -remainder
}

private fun minutesToLabel(totalMinutes: Int): String {
    val clamped = totalMinutes.coerceIn(0, 23 * 60 + 59)
    val hour = clamped / 60
    val minute = clamped % 60
    return "%02d:%02d".format(hour, minute)
}

private fun generateIdempotencyKey(): String {
    val bytes = ByteArray(16)
    Random.nextBytes(bytes)
    return bytes.joinToString(separator = "") { b -> "%02x".format(b) }
}

private fun eventTitleSimilarityScore(
    title: String,
    query: String,
): Int {
    val normalizedTitle = normalizedTaskString(title)
    val normalizedQuery = normalizedTaskString(query)
    if (normalizedTitle.isEmpty() || normalizedQuery.isEmpty()) return 0
    if (normalizedTitle == normalizedQuery) return 100
    if (normalizedTitle.contains(normalizedQuery) || normalizedQuery.contains(normalizedTitle)) return 90

    val titleTokens = normalizeTaskTokens(title)
    val queryTokens = normalizeTaskTokens(query)
    if (titleTokens.isEmpty() || queryTokens.isEmpty()) return 0

    val exactMatches = queryTokens.count { token -> titleTokens.any { it == token } }
    val partialMatches = queryTokens.count { token -> titleTokens.any { it.startsWith(token) || token.startsWith(it) } }

    val coverage = exactMatches.toDouble() / queryTokens.size
    val partialCoverage = partialMatches.toDouble() / queryTokens.size

    val base = (coverage * 70).toInt()
    val partial = (partialCoverage * 20).toInt()
    val bonus = if (exactMatches == queryTokens.size) 5 else 0

    return (base + partial + bonus).coerceAtMost(95)
}

private fun timeProximityScore(
    targetMinutes: Int,
    actualMinutes: Int,
): Int {
    val diff = abs(actualMinutes - targetMinutes)
    return when {
        diff == 0 -> 40
        diff <= 5 -> 30
        diff <= 15 -> 20
        diff <= 30 -> 10
        diff <= 60 -> 5
        else -> 0
    }
}

/*
 * Strata - Kotlin Multiplatform client.
 * Copyright (c) 2025.
 */
package org.strata.auth

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

actual object TasksApi {
    private val http = HttpClient(CIO)
    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun authedGetWithRefresh(
        url: String,
        accessToken: String,
    ): HttpResponse {
        var resp: HttpResponse = http.get(url) { header("Authorization", "Bearer $accessToken") }
        if (resp.status.value != 401 && resp.status.value != 403) return resp
        val newToken = TokenManager.refreshAccessToken()
        if (newToken.isNullOrBlank()) return resp
        return http.get(url) { header("Authorization", "Bearer $newToken") }
    }

    private fun withApiKey(url: String): String {
        val key = System.getenv("GOOGLE_API_KEY")?.trim().orEmpty()
        if (key.isBlank()) return url
        val sep = if (url.contains('?')) '&' else '?'
        return "$url${sep}key=${java.net.URLEncoder.encode(key, "UTF-8") }"
    }

    private suspend fun authedPostJsonWithRefresh(
        url: String,
        accessToken: String,
        jsonBody: String,
    ): Pair<Int, String?> {
        fun postOnce(token: String): Pair<Int, String?> {
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val body =
                try {
                    conn.inputStream?.bufferedReader()?.readText()
                } catch (_: Throwable) {
                    try {
                        conn.errorStream?.bufferedReader()?.readText()
                    } catch (_: Throwable) {
                        null
                    }
                }
            conn.disconnect()
            return code to body
        }
        val (code, body) = postOnce(accessToken)
        if (code == 401 || code == 403) {
            val newToken = TokenManager.refreshAccessToken()
            if (!newToken.isNullOrBlank()) return postOnce(newToken)
        }
        return code to body
    }

    @Serializable
    private data class TaskListsResponse(val items: List<TaskListItem> = emptyList())

    @Serializable
    private data class TaskListItem(val id: String, val title: String? = null)

    @Serializable
    private data class TasksResponse(val items: List<TaskItemDto>? = null)

    @Serializable
    private data class TaskItemDto(
        val id: String,
        val title: String? = null,
        val notes: String? = null,
        val due: String? = null,
        // Needs to be "completed" or "needsAction".
        val status: String? = null,
    )

    private fun toRfc3339(due: LocalDateTime?): String? {
        if (due == null) return null
        // If time is 00:00 treat as date-only per Google Tasks API.
        return try {
            if (due.hour == 0 && due.minute == 0) {
                "%04d-%02d-%02d".format(due.year, due.monthNumber, due.dayOfMonth)
            } else {
                val ldt =
                    java.time.LocalDateTime.of(
                        due.year,
                        due.monthNumber,
                        due.dayOfMonth,
                        due.hour,
                        due.minute,
                    )
                val zdt = ldt.atZone(java.time.ZoneId.systemDefault())
                java.time.format.DateTimeFormatter.ISO_INSTANT.format(zdt.toInstant())
            }
        } catch (t: Throwable) {
            null
        }
    }

    private suspend fun resolveDefaultListId(accessToken: String): String? {
        val listsUrl = withApiKey("https://tasks.googleapis.com/tasks/v1/users/@me/lists")
        val listsResp: HttpResponse = authedGetWithRefresh(listsUrl, accessToken)
        val listsText = listsResp.bodyAsText()
        val lists =
            runCatching { json.decodeFromString(TaskListsResponse.serializer(), listsText) }
                .getOrNull()
        return lists?.items?.firstOrNull()?.id
    }

    actual suspend fun fetchTopTasks(accessToken: String?): Result<List<TaskItem>> {
        if (accessToken.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Not signed in"))
        }
        return try {
            // 1) Fetch task lists, pick the first (commonly the default "My Tasks")
            val listsUrl = withApiKey("https://tasks.googleapis.com/tasks/v1/users/@me/lists")
            val listsResp: HttpResponse = authedGetWithRefresh(listsUrl, accessToken)
            val listsStatus = listsResp.status.value
            val listsText = listsResp.bodyAsText()
            val lists =
                runCatching { json.decodeFromString(TaskListsResponse.serializer(), listsText) }
                    .getOrNull()
            val firstList =
                lists?.items?.firstOrNull()
                    ?: return Result.success(emptyList())

            // 2) Fetch tasks from that list, show only not completed
            val tasksUrl =
                withApiKey(
                    "https://tasks.googleapis.com/tasks/v1/lists/${firstList.id}/tasks" +
                        "?showCompleted=false&maxResults=100",
                )
            val tasksResp: HttpResponse = authedGetWithRefresh(tasksUrl, accessToken)
            val tasksStatus = tasksResp.status.value
            val tasksText = tasksResp.bodyAsText()
            val parsed =
                runCatching { json.decodeFromString(TasksResponse.serializer(), tasksText) }
                    .getOrNull()

            val tz = TimeZone.currentSystemDefault()
            val tasks = mutableListOf<TaskItem>()
            val seenIds = mutableSetOf<String>()

            parsed?.items.orEmpty()
                .filter { it.title?.isNotBlank() == true }
                .forEach { dto ->
                    val parsedDue = dto.due?.let { parseDue(it, tz) }
                    val storedDue = TaskDueTimeStore.read(dto.id)
                    val resolvedDue = resolveDue(parsedDue, storedDue)

                    when {
                        resolvedDue != null -> TaskDueTimeStore.save(dto.id, resolvedDue)
                        storedDue != null -> TaskDueTimeStore.save(dto.id, null)
                    }

                    tasks +=
                        TaskItem(
                            id = dto.id,
                            title = dto.title ?: "(no title)",
                            notes = dto.notes,
                            due = resolvedDue,
                            completed = dto.status?.equals("completed", true) == true,
                            listTitle = firstList.title,
                        )
                    seenIds += dto.id
                }

            if (seenIds.isNotEmpty()) TaskDueTimeStore.prune(seenIds)

            val sorted = tasks.sortedWith(compareBy<TaskItem> { it.due == null }.thenBy { it.due })

            Result.success(sorted)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    private data class ParsedDue(val value: LocalDateTime?, val isDateOnly: Boolean)

    private fun parseDue(
        due: String,
        tz: TimeZone,
    ): ParsedDue? {
        val raw = due.trim()
        if (raw.isEmpty()) return null
        val isDateOnly = !raw.contains('T')
        if (isDateOnly) {
            return runCatching {
                val date = LocalDate.parse(raw)
                ParsedDue(LocalDateTime(date.year, date.monthNumber, date.dayOfMonth, 0, 0), true)
            }.getOrNull()
        }

        val timePortion = raw.substringAfter('T')
        val normalized =
            if (raw.endsWith("Z", ignoreCase = true) || raw.contains('+') || timePortion.contains('-')) {
                raw
            } else {
                raw + "Z"
            }
        val instant = runCatching { Instant.parse(normalized) }.getOrNull() ?: return null
        val local = instant.toLocalDateTime(tz)
        val treatAsDateOnly = timePortion.startsWith("00:00")
        return ParsedDue(
            value =
                if (treatAsDateOnly) {
                    LocalDateTime(
                        local.date.year,
                        local.date.monthNumber,
                        local.date.dayOfMonth,
                        0,
                        0,
                    )
                } else {
                    local
                },
            isDateOnly = treatAsDateOnly,
        )
    }

    private fun resolveDue(
        remote: ParsedDue?,
        stored: LocalDateTime?,
    ): LocalDateTime? {
        remote ?: return null
        val remoteValue = remote.value ?: return null
        if (!remote.isDateOnly) return remoteValue
        val storedHasTime = stored?.hasExplicitTime() == true
        if (!storedHasTime) return remoteValue
        val storedValue = stored!!
        val remoteDate = remoteValue.date
        return if (storedValue.date == remoteDate) {
            storedValue
        } else {
            LocalDateTime(
                remoteDate.year,
                remoteDate.monthNumber,
                remoteDate.dayOfMonth,
                storedValue.hour,
                storedValue.minute,
                storedValue.second,
                storedValue.nanosecond,
            )
        }
    }

    private fun LocalDateTime.hasExplicitTime(): Boolean {
        return hour != 0 || minute != 0 || second != 0 || nanosecond != 0
    }

    actual suspend fun createTask(
        accessToken: String?,
        title: String,
        notes: String?,
        due: LocalDateTime?,
    ): Result<String> {
        if (accessToken.isNullOrBlank()) {
            return Result.failure<String>(IllegalStateException("Not signed in"))
        }
        return try {
            val listId =
                resolveDefaultListId(accessToken)
                    ?: return Result.failure<String>(IllegalStateException("No task list"))
            val url = withApiKey("https://tasks.googleapis.com/tasks/v1/lists/$listId/tasks")

            fun toRfc3339(ldt: LocalDateTime?): String? =
                if (ldt == null) {
                    null
                } else {
                    try {
                        val jt =
                            java.time.LocalDateTime.of(
                                ldt.year,
                                ldt.monthNumber,
                                ldt.dayOfMonth,
                                ldt.hour,
                                ldt.minute,
                            )
                        val zdt = jt.atZone(java.time.ZoneId.systemDefault())
                        java.time.format.DateTimeFormatter.ISO_INSTANT.format(zdt.toInstant())
                    } catch (_: Throwable) {
                        null
                    }
                }
            val jsonBody =
                kotlinx.serialization.json.buildJsonObject {
                    put("title", kotlinx.serialization.json.JsonPrimitive(title))
                    if (!notes.isNullOrBlank()) {
                        put("notes", kotlinx.serialization.json.JsonPrimitive(notes))
                    }
                    toRfc3339(due)?.let { put("due", kotlinx.serialization.json.JsonPrimitive(it)) }
                }.toString()
            val (code, body) = authedPostJsonWithRefresh(url, accessToken, jsonBody)
            if (code in 200..299) {
                // parse id
                val id =
                    runCatching {
                        val root = Json.parseToJsonElement(body ?: "{}").jsonObject
                        root["id"]?.jsonPrimitive?.content
                    }.getOrNull()
                if (!id.isNullOrBlank()) {
                    if (due != null) {
                        TaskDueTimeStore.save(id, due)
                    } else {
                        TaskDueTimeStore.save(id, null)
                    }
                    Result.success(id)
                } else {
                    Result.failure(IllegalStateException("No task id returned"))
                }
            } else {
                Result.failure(
                    IllegalStateException("Create task failed: $code ${body?.take(200)}"),
                )
            }
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    actual suspend fun pushTaskChanges(
        accessToken: String?,
        taskId: String,
        title: String?,
        notes: String?,
        due: LocalDateTime?,
        completed: Boolean?,
    ): Result<Unit> {
        if (accessToken.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Not signed in"))
        }
        return try {
            val listId =
                resolveDefaultListId(accessToken)
                    ?: return Result.failure(IllegalStateException("No task list"))
            val url = withApiKey("https://tasks.googleapis.com/tasks/v1/lists/$listId/tasks/$taskId")
            val bodyMap = mutableMapOf<String, Any>()
            if (title != null) bodyMap["title"] = title
            if (notes != null) bodyMap["notes"] = notes
            if (due != null) {
                val rfc = toRfc3339(due)
                if (rfc != null) bodyMap["due"] = rfc
            }
            if (completed != null) {
                bodyMap["status"] = if (completed) "completed" else "needsAction"
            }
            if (bodyMap.isEmpty()) return Result.success(Unit)
            val jsonBody =
                kotlinx.serialization.json.buildJsonObject {
                    bodyMap.forEach { (k, v) ->
                        when (v) {
                            is String -> put(k, kotlinx.serialization.json.JsonPrimitive(v))
                            is Number -> put(k, kotlinx.serialization.json.JsonPrimitive(v.toString()))
                            else -> put(k, kotlinx.serialization.json.JsonPrimitive(v.toString()))
                        }
                    }
                }.toString()

            // Use PATCH via X-HTTP-Method-Override to avoid client DSL issues
            fun patchOnce(bearer: String): Pair<Int, String?> {
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("X-HTTP-Method-Override", "PATCH")
                conn.setRequestProperty("Authorization", "Bearer $bearer")
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.outputStream.use { os ->
                    val bytes = jsonBody.toByteArray(Charsets.UTF_8)
                    os.write(bytes)
                }
                val code = conn.responseCode
                val body =
                    try {
                        conn.inputStream?.bufferedReader()?.readText()
                    } catch (_: Throwable) {
                        try {
                            conn.errorStream?.bufferedReader()?.readText()
                        } catch (_: Throwable) {
                            null
                        }
                    }
                conn.disconnect()
                return code to body
            }

            val (code, body) = patchOnce(accessToken)
            if (code in 200..299) {
                if (due != null) TaskDueTimeStore.save(taskId, due)
                Result.success(Unit)
            } else if (code == 401 || code == 403) {
                val newToken = TokenManager.refreshAccessToken()
                if (!newToken.isNullOrBlank()) {
                    val (c2, b2) = patchOnce(newToken)
                    if (c2 in 200..299) {
                        if (due != null) TaskDueTimeStore.save(taskId, due)
                        return Result.success(Unit)
                    }
                    return Result.failure(
                        IllegalStateException("Update failed: $c2 ${b2?.take(200)}"),
                    )
                }
                Result.failure(IllegalStateException("Update failed: $code ${body?.take(200)}"))
            } else {
                Result.failure(IllegalStateException("Update failed: $code ${body?.take(200)}"))
            }
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    actual suspend fun deleteTask(
        accessToken: String?,
        taskId: String,
    ): Result<Unit> {
        if (accessToken.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Not signed in"))
        }
        return try {
            val listId =
                resolveDefaultListId(accessToken)
                    ?: return Result.failure(IllegalStateException("No task list"))
            val url = withApiKey("https://tasks.googleapis.com/tasks/v1/lists/$listId/tasks/$taskId")

            fun deleteOnce(token: String): Pair<Int, String?> {
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "DELETE"
                conn.setRequestProperty("Authorization", "Bearer $token")
                val code = conn.responseCode
                val body =
                    try {
                        conn.inputStream?.bufferedReader()?.readText()
                    } catch (_: Throwable) {
                        try {
                            conn.errorStream?.bufferedReader()?.readText()
                        } catch (_: Throwable) {
                            null
                        }
                    }
                conn.disconnect()
                return code to body
            }

            val (code, body) = deleteOnce(accessToken)
            when {
                code in 200..299 || code == 204 -> {
                    TaskDueTimeStore.save(taskId, null)
                    Result.success(Unit)
                }
                code == 401 || code == 403 -> {
                    val newToken = TokenManager.refreshAccessToken()
                    if (!newToken.isNullOrBlank()) {
                        val (c2, b2) = deleteOnce(newToken)
                        if (c2 in 200..299 || c2 == 204) {
                            TaskDueTimeStore.save(taskId, null)
                            Result.success(Unit)
                        } else {
                            Result.failure(IllegalStateException("Delete failed: $c2 ${b2?.take(200)}"))
                        }
                    } else {
                        Result.failure(IllegalStateException("Delete failed: $code ${body?.take(200)}"))
                    }
                }
                else -> Result.failure(IllegalStateException("Delete failed: $code ${body?.take(200)}"))
            }
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }
}

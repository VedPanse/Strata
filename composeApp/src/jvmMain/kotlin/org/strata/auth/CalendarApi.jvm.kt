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
import kotlinx.datetime.Clock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

actual object CalendarApi {
    private val http = HttpClient(CIO)
    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun authedGetWithRefresh(
        url: String,
        accessToken: String,
    ): HttpResponse {
        // First attempt
        var resp: HttpResponse = http.get(url) { header("Authorization", "Bearer $accessToken") }
        if (resp.status.value != 401 && resp.status.value != 403) return resp
        // Try refresh on unauthorized
        val newToken = TokenManager.refreshAccessToken()
        if (newToken.isNullOrBlank()) return resp
        return http.get(url) { header("Authorization", "Bearer $newToken") }
    }

    private fun withApiKey(url: String): String {
        val key = System.getenv("GOOGLE_API_KEY")?.trim().orEmpty()
        if (key.isBlank()) return url
        val sep = if (url.contains('?')) '&' else '?'
        return "$url${sep}key=${java.net.URLEncoder.encode(key, "UTF-8")}"
    }

    @Serializable
    private data class EventsResponse(
        val items: List<EventItem> = emptyList(),
    )

    @Serializable
    private data class EventItem(
        val id: String = "",
        val summary: String? = null,
        val location: String? = null,
        val start: EventDateTime? = null,
        val end: EventDateTime? = null,
        val eventType: String? = null,
        val description: String? = null,
    )

    @Serializable
    private data class EventDateTime(
        // RFC3339 date-time with zone
        val dateTime: String? = null,
        // All-day date (YYYY-MM-DD)
        val date: String? = null,
        val timeZone: String? = null,
    )

    actual suspend fun fetchToday(accessToken: String?): Result<List<CalendarEvent>> {
        if (accessToken.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Not signed in"))
        }
        val tz = TimeZone.currentSystemDefault()
        val now = Clock.System.now().toLocalDateTime(tz)
        val startOfDay = LocalDateTime(now.year, now.monthNumber, now.dayOfMonth, 0, 0, 0, 0)
        val endOfDay =
            LocalDateTime(
                now.year,
                now.monthNumber,
                now.dayOfMonth,
                23,
                59,
                59,
                999_000_000,
            )
        val timeMin = startOfDay.toInstant(tz).toString() // RFC3339 UTC like 2025-08-21T00:00:00Z
        val timeMax = endOfDay.toInstant(tz).toString()

        val baseUrl =
            buildString {
                append("https://www.googleapis.com/calendar/v3/calendars/primary/events")
                append("?singleEvents=true") // expand recurring
                append("&orderBy=startTime")
                append("&timeMin=").append(encode(timeMin))
                append("&timeMax=").append(encode(timeMax))
            }
        val url = withApiKey(baseUrl)
        return fetchAndMap(accessToken, url)
    }

    actual suspend fun fetchUpcomingFromGmail(
        accessToken: String?,
        daysAhead: Int,
    ): Result<List<CalendarEvent>> {
        if (accessToken.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Not signed in"))
        }
        val tz = TimeZone.currentSystemDefault()
        val nowInstant = Clock.System.now()
        val timeMin = nowInstant.toString()
        // Compute end of day at horizon in local tz, then to Instant
        val horizonLocalDate = nowInstant.toLocalDateTime(tz).date.plus(DatePeriod(days = daysAhead))
        val endLocal =
            LocalDateTime(
                horizonLocalDate.year,
                horizonLocalDate.monthNumber,
                horizonLocalDate.dayOfMonth,
                23,
                59,
                59,
            )
        val timeMax = endLocal.toInstant(tz).toString()

        val baseUrl =
            buildString {
                append("https://www.googleapis.com/calendar/v3/calendars/primary/events")
                append("?singleEvents=true")
                append("&orderBy=startTime")
                append("&timeMin=").append(encode(timeMin))
                append("&timeMax=").append(encode(timeMax))
            }
        val url = withApiKey(baseUrl)
        return try {
            val resp: HttpResponse = authedGetWithRefresh(url, accessToken)
            val body = resp.bodyAsText()
            val parsed =
                runCatching { json.decodeFromString(EventsResponse.serializer(), body) }.getOrNull()
                    ?: return Result.failure(IllegalStateException("Failed to parse Calendar events"))
            val items = parsed.items.filter { it.eventType == "fromGmail" }
            println("[DEBUG_LOG][CalendarApi] fromGmail events fetched: ${items.size}")
            items.forEach { i ->
                println(
                    "[DEBUG_LOG][CalendarApi] event -> summary='${i.summary}', location='${i.location}', " +
                        "start='${i.start?.dateTime ?: i.start?.date}', end='${i.end?.dateTime ?: i.end?.date}'",
                )
            }
            val events =
                items.mapNotNull { item ->
                    val range = parseRange(item.start, item.end, tz) ?: return@mapNotNull null
                    val id = item.id.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    CalendarEvent(
                        id = id,
                        title = item.summary ?: "(no title)",
                        start = range.first,
                        end = range.second,
                        location = item.location,
                        notes = item.description,
                    )
                }.sortedBy { it.start }
            Result.success(events)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    actual suspend fun listEvents(
        accessToken: String?,
        start: LocalDateTime,
        end: LocalDateTime,
        titleFilter: String?,
    ): Result<List<CalendarEvent>> {
        if (accessToken.isNullOrBlank()) return Result.failure(IllegalStateException("Not signed in"))
        val tz = TimeZone.currentSystemDefault()
        val timeMin = start.toInstant(tz).toString()
        val timeMax = end.toInstant(tz).toString()
        val baseUrl =
            buildString {
                append("https://www.googleapis.com/calendar/v3/calendars/primary/events")
                append("?singleEvents=true")
                append("&orderBy=startTime")
                append("&timeMin=").append(encode(timeMin))
                append("&timeMax=").append(encode(timeMax))
            }
        val url = withApiKey(baseUrl)
        return fetchAndMap(accessToken, url, titleFilter)
    }

    private fun parseRange(
        start: EventDateTime?,
        end: EventDateTime?,
        tz: TimeZone,
    ): Pair<LocalDateTime, LocalDateTime>? {
        if (start == null || end == null) return null
        // If dateTime present, parse as Instant -> local
        start.dateTime?.let { s ->
            val sLdt = Instant.parse(s).toLocalDateTime(tz)
            val eLdt = Instant.parse(end.dateTime ?: s).toLocalDateTime(tz)
            return sLdt to eLdt
        }
        // All-day events with date only
        start.date?.let { sd ->
            val sDate = LocalDate.parse(sd)
            val sLdt = LocalDateTime(sDate.year, sDate.monthNumber, sDate.dayOfMonth, 0, 0)
            val eDate = end.date?.let { LocalDate.parse(it) } ?: sDate
            val eLdt = LocalDateTime(eDate.year, eDate.monthNumber, eDate.dayOfMonth, 23, 59, 59)
            return sLdt to eLdt
        }
        return null
    }

    private fun encode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    private fun toRfc3339(ldt: LocalDateTime): String {
        val jt =
            java.time.LocalDateTime.of(
                ldt.year,
                ldt.monthNumber,
                ldt.dayOfMonth,
                ldt.hour,
                ldt.minute,
            )
        val zdt = jt.atZone(java.time.ZoneId.systemDefault())
        return java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(zdt)
    }

    private fun buildEventMutationBody(
        title: String,
        start: LocalDateTime,
        end: LocalDateTime,
        location: String?,
        notes: String?,
    ): String {
        return buildJsonObject {
            put("summary", JsonPrimitive(title))
            if (!notes.isNullOrBlank()) put("description", JsonPrimitive(notes))
            if (!location.isNullOrBlank()) put("location", JsonPrimitive(location))
            put(
                "start",
                buildJsonObject {
                    put("dateTime", JsonPrimitive(toRfc3339(start)))
                },
            )
            put(
                "end",
                buildJsonObject {
                    put("dateTime", JsonPrimitive(toRfc3339(end)))
                },
            )
        }.toString()
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
        val (code, body) = postOnce(accessToken)
        if (code == 401 || code == 403) {
            val newToken = TokenManager.refreshAccessToken()
            if (!newToken.isNullOrBlank()) return postOnce(newToken)
        }
        return code to body
    }

    private suspend fun authedPatchJsonWithRefresh(
        url: String,
        accessToken: String,
        jsonBody: String,
    ): Pair<Int, String?> {
        fun patchOnce(token: String): Pair<Int, String?> {
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            // HttpURLConnection pre-Java 11 has no PATCH support; use method override.
            conn.requestMethod = "POST"
            conn.setRequestProperty("X-HTTP-Method-Override", "PATCH")
            conn.setRequestProperty("Authorization", "Bearer $token")
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
        if (code == 401 || code == 403) {
            val newToken = TokenManager.refreshAccessToken()
            if (!newToken.isNullOrBlank()) return patchOnce(newToken)
        }
        return code to body
    }

    actual suspend fun createEvent(
        accessToken: String?,
        title: String,
        start: LocalDateTime,
        end: LocalDateTime,
        location: String?,
        notes: String?,
    ): Result<String> {
        if (accessToken.isNullOrBlank()) return Result.failure(IllegalStateException("Not signed in"))
        return try {
            val url = withApiKey("https://www.googleapis.com/calendar/v3/calendars/primary/events")
            val jsonBody = buildEventMutationBody(title, start, end, location, notes)
            val (code, body) = authedPostJsonWithRefresh(url, accessToken, jsonBody)
            if (code !in 200..299) {
                return Result.failure(IllegalStateException("Calendar create failed: $code ${body?.take(200)}"))
            }
            val eventId =
                body
                    ?.let {
                        runCatching {
                            json.parseToJsonElement(it).jsonObject["id"]?.jsonPrimitive?.content
                        }.getOrNull()
                    }
                    ?.takeIf { it.isNotBlank() }
                    ?: return Result.failure(IllegalStateException("Calendar create response missing id"))
            Result.success(eventId)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    actual suspend fun updateEvent(
        accessToken: String?,
        eventId: String,
        newStart: LocalDateTime?,
        newEnd: LocalDateTime?,
        newTitle: String?,
        newLocation: String?,
        newNotes: String?,
    ): Result<Unit> {
        if (accessToken.isNullOrBlank()) return Result.failure(IllegalStateException("Not signed in"))
        if (eventId.isBlank()) return Result.failure(IllegalArgumentException("Missing eventId"))

        val payload =
            buildJsonObject {
                newTitle?.let { put("summary", JsonPrimitive(it)) }
                newNotes?.let { put("description", JsonPrimitive(it)) }
                newLocation?.let { put("location", JsonPrimitive(it)) }
                newStart?.let { start ->
                    put(
                        "start",
                        buildJsonObject {
                            put("dateTime", JsonPrimitive(toRfc3339(start)))
                        },
                    )
                }
                newEnd?.let { end ->
                    put(
                        "end",
                        buildJsonObject {
                            put("dateTime", JsonPrimitive(toRfc3339(end)))
                        },
                    )
                }
            }

        if (payload.entries.isEmpty()) return Result.success(Unit)

        return try {
            val url = withApiKey("https://www.googleapis.com/calendar/v3/calendars/primary/events/${encode(eventId)}")
            val (code, body) = authedPatchJsonWithRefresh(url, accessToken, payload.toString())
            if (code in 200..299) {
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException("Calendar update failed: $code ${body?.take(200)}"))
            }
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    actual suspend fun deleteEventById(
        accessToken: String?,
        eventId: String,
    ): Result<Unit> {
        if (accessToken.isNullOrBlank()) return Result.failure(IllegalStateException("Not signed in"))
        if (eventId.isBlank()) return Result.failure(IllegalArgumentException("Missing eventId"))
        return try {
            val url = withApiKey("https://www.googleapis.com/calendar/v3/calendars/primary/events/${encode(eventId)}")
            val (code, body) = authedDeleteWithRefresh(url, accessToken)
            if (code in 200..299 || code == 204) {
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException("Calendar delete failed: $code ${body?.take(200)}"))
            }
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    private suspend fun fetchAndMap(
        accessToken: String,
        url: String,
        titleFilter: String? = null,
    ): Result<List<CalendarEvent>> {
        return try {
            val resp: HttpResponse = authedGetWithRefresh(url, accessToken)
            val body = resp.bodyAsText()
            val parsed =
                runCatching { json.decodeFromString(EventsResponse.serializer(), body) }
                    .getOrNull()
                    ?: return Result.failure(IllegalStateException("Failed to parse Calendar events"))
            val tz = TimeZone.currentSystemDefault()
            val events =
                parsed.items.mapNotNull { item ->
                    try {
                        val id = item.id.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        val (startLdt, endLdt) = parseRange(item.start, item.end, tz) ?: return@mapNotNull null
                        CalendarEvent(
                            id = id,
                            title = item.summary ?: "(no title)",
                            start = startLdt,
                            end = endLdt,
                            location = item.location,
                            notes = item.description,
                        )
                    } catch (_: Throwable) {
                        null
                    }
                }
                    .filter { ev ->
                        titleFilter.isNullOrBlank() || ev.title.contains(titleFilter, ignoreCase = true)
                    }
                    .sortedBy { it.start }
            Result.success(events)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    actual suspend fun deleteEventsInRange(
        accessToken: String?,
        start: LocalDateTime,
        end: LocalDateTime,
        titleFilter: String?,
        startTimeFilter: String?,
    ): Result<Int> {
        if (accessToken.isNullOrBlank()) return Result.failure(IllegalStateException("Not signed in"))
        return try {
            val tz = TimeZone.currentSystemDefault()
            val timeMin = start.toInstant(tz).toString()
            val timeMax = end.toInstant(tz).toString()
            val baseUrl =
                buildString {
                    append("https://www.googleapis.com/calendar/v3/calendars/primary/events")
                    append("?singleEvents=true")
                    append("&orderBy=startTime")
                    append("&timeMin=").append(encode(timeMin))
                    append("&timeMax=").append(encode(timeMax))
                }
            val url = withApiKey(baseUrl)
            val resp: HttpResponse = authedGetWithRefresh(url, accessToken)
            val body = resp.bodyAsText()
            val parsed =
                runCatching { json.decodeFromString(EventsResponse.serializer(), body) }.getOrNull()
                    ?: return Result.failure(IllegalStateException("Failed to parse Calendar events for delete"))
            val items = parsed.items
            var deleted = 0
            for (item in items) {
                val title = item.summary ?: ""
                if (!titleFilter.isNullOrBlank()) {
                    if (!title.contains(titleFilter, ignoreCase = true)) continue
                }
                // If startTimeFilter specified, ensure the local start time matches HH:MM
                if (!startTimeFilter.isNullOrBlank()) {
                    val range = parseRange(item.start, item.end, tz) ?: continue
                    val hh = range.first.hour.toString().padStart(2, '0')
                    val mm = range.first.minute.toString().padStart(2, '0')
                    val hhmm = "$hh:$mm"
                    if (!hhmm.equals(startTimeFilter)) continue
                }
                if (item.id.isBlank()) continue
                val delUrl =
                    withApiKey("https://www.googleapis.com/calendar/v3/calendars/primary/events/${encode(item.id)}")
                val (code, _) = authedDeleteWithRefresh(delUrl, accessToken)
                if (code in 200..299 || code == 204) {
                    deleted++
                } else {
                    // keep going on failure
                }
            }
            Result.success(deleted)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    private suspend fun authedDeleteWithRefresh(
        url: String,
        accessToken: String,
    ): Pair<Int, String?> {
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
        if (code == 401 || code == 403) {
            val newToken = TokenManager.refreshAccessToken()
            if (!newToken.isNullOrBlank()) return deleteOnce(newToken)
        }
        return code to body
    }
}

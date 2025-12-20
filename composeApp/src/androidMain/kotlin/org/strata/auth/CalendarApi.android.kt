/*
 * Strata - Kotlin Multiplatform client.
 * Copyright (c) 2025.
 */
package org.strata.auth

actual object CalendarApi {
    actual suspend fun fetchToday(accessToken: String?): Result<List<CalendarEvent>> {
        return Result.failure(UnsupportedOperationException("Calendar fetch not implemented on Android yet"))
    }

    actual suspend fun fetchUpcomingFromGmail(
        accessToken: String?,
        daysAhead: Int,
    ): Result<List<CalendarEvent>> {
        return Result.failure(
            UnsupportedOperationException("Calendar fetch (fromGmail) not implemented on Android yet"),
        )
    }

    actual suspend fun listEvents(
        accessToken: String?,
        start: kotlinx.datetime.LocalDateTime,
        end: kotlinx.datetime.LocalDateTime,
        titleFilter: String?,
    ): Result<List<CalendarEvent>> {
        return Result.failure(UnsupportedOperationException("Calendar list not implemented on Android yet"))
    }

    actual suspend fun createEvent(
        accessToken: String?,
        title: String,
        start: kotlinx.datetime.LocalDateTime,
        end: kotlinx.datetime.LocalDateTime,
        location: String?,
        notes: String?,
    ): Result<String> {
        return Result.failure(UnsupportedOperationException("Calendar create not implemented on Android yet"))
    }

    actual suspend fun updateEvent(
        accessToken: String?,
        eventId: String,
        newStart: kotlinx.datetime.LocalDateTime?,
        newEnd: kotlinx.datetime.LocalDateTime?,
        newTitle: String?,
        newLocation: String?,
        newNotes: String?,
    ): Result<Unit> {
        return Result.failure(UnsupportedOperationException("Calendar update not implemented on Android yet"))
    }

    actual suspend fun deleteEventById(
        accessToken: String?,
        eventId: String,
    ): Result<Unit> {
        return Result.failure(UnsupportedOperationException("Calendar delete by ID not implemented on Android yet"))
    }

    actual suspend fun deleteEventsInRange(
        accessToken: String?,
        start: kotlinx.datetime.LocalDateTime,
        end: kotlinx.datetime.LocalDateTime,
        titleFilter: String?,
        startTimeFilter: String?,
    ): Result<Int> {
        return Result.failure(UnsupportedOperationException("Calendar delete not implemented on Android yet"))
    }
}

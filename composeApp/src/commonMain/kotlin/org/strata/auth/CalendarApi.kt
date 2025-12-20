/*
 * Strata - Kotlin Multiplatform client.
 * Copyright (c) 2025.
 */
package org.strata.auth

import kotlinx.datetime.LocalDateTime

/** Model for rendering calendar events in UI. */
data class CalendarEvent(
    val id: String,
    val title: String,
    val start: LocalDateTime,
    val end: LocalDateTime,
    val location: String? = null,
    val notes: String? = null,
)

/** Platform bridge for Google Calendar API. */
expect object CalendarApi {
    /**
     * Fetch all events happening today (in the user's local timezone) from the primary calendar.
     * Returns a list sorted by start time. If [accessToken] is null/blank, returns failure.
     */
    suspend fun fetchToday(accessToken: String?): Result<List<CalendarEvent>>

    /**
     * Fetch upcoming reservation events created "from Gmail" (eventType = "fromGmail").
     * Uses the user's primary calendar, from now until [daysAhead] days in the future (default 365).
     * Returns a list sorted by start time. If [accessToken] is null/blank, returns failure.
     */
    suspend fun fetchUpcomingFromGmail(
        accessToken: String?,
        daysAhead: Int = 365,
    ): Result<List<CalendarEvent>>

    /** List calendar events in an arbitrary window (inclusive of [start], exclusive of [end]). */
    suspend fun listEvents(
        accessToken: String?,
        start: LocalDateTime,
        end: LocalDateTime,
        titleFilter: String? = null,
    ): Result<List<CalendarEvent>>

    /**
     * Create a calendar event on the user's primary calendar. Returns the Google eventId.
     */
    suspend fun createEvent(
        accessToken: String?,
        title: String,
        start: LocalDateTime,
        end: LocalDateTime,
        location: String? = null,
        notes: String? = null,
    ): Result<String>

    /** Update fields on an existing calendar event. */
    suspend fun updateEvent(
        accessToken: String?,
        eventId: String,
        newStart: LocalDateTime? = null,
        newEnd: LocalDateTime? = null,
        newTitle: String? = null,
        newLocation: String? = null,
        newNotes: String? = null,
    ): Result<Unit>

    /** Optional direct delete helper when eventId is known. */
    suspend fun deleteEventById(
        accessToken: String?,
        eventId: String,
    ): Result<Unit>

    /**
     * Delete events in the given local datetime range on the user's primary calendar.
     * If [titleFilter] is provided, only delete events whose title contains it (case-insensitive).
     * If [startTimeFilter] is provided (HH:MM), only delete events starting exactly at that time.
     * Returns the count of deleted events.
     */
    suspend fun deleteEventsInRange(
        accessToken: String?,
        start: LocalDateTime,
        end: LocalDateTime,
        titleFilter: String? = null,
        startTimeFilter: String? = null,
    ): Result<Int>
}

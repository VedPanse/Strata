/*
 * Strata - Kotlin Multiplatform client.
 * Copyright (c) 2025.
 */
package org.strata.ai

import org.strata.auth.CalendarEvent
import org.strata.auth.GmailMail

/**
 * Multiplatform AI interface for extracting travel tickets from Gmail and Calendar.
 */
expect object TicketsAi {
    /**
     * Extract travel tickets (flight, train, bus) from the user's unread mails and today's calendar events.
     * Returns normalized ticket payloads that UI can render.
     */
    suspend fun extractTickets(
        unreadMails: List<GmailMail>,
        todayEvents: List<CalendarEvent>,
    ): Result<List<ExtractedTicket>>
}

/**
 * Normalized representation of a ticket returned by AI.
 */
data class ExtractedTicket(
    // FLIGHT | TRAIN | BUS
    val type: String,
    val number: String = "",
    // ON_TIME | DELAYED | CANCELLED
    val status: String = "ON_TIME",
    val departureName: String,
    val departureAbbr: String = "",
    val arrivalName: String,
    val arrivalAbbr: String = "",
    // ISO-8601 local date-time (e.g., 2025-08-03T10:00)
    val departureIso: String,
    val arrivalIso: String,
)

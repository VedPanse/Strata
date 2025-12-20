/*
 * Strata - Kotlin Multiplatform client.
 * Copyright (c) 2025.
 */
package org.strata.ai

import org.strata.auth.CalendarEvent
import org.strata.auth.GmailMail

/**
 * Android stub for Gemini tickets; desktop handles the actual implementation.
 */
actual object TicketsAi {
    actual suspend fun extractTickets(
        unreadMails: List<GmailMail>,
        todayEvents: List<CalendarEvent>,
    ): Result<List<ExtractedTicket>> {
        return Result.failure(UnsupportedOperationException("Gemini tickets not implemented on Android in this build"))
    }
}

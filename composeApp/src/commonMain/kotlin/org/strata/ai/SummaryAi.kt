/*
 * Strata - Kotlin Multiplatform client.
 * Copyright (c) 2025.
 */
package org.strata.ai

import org.strata.auth.CalendarEvent
import org.strata.auth.GmailMail
import org.strata.auth.TaskItem

/**
 * Multiplatform AI summary interface.
 */
expect object SummaryAi {
    /**
     * Builds a concise 4–5 sentence summary of the user's day based on unread mail,
     * today's calendar events, and upcoming reminders.
     *
     * Implementations should call Google Gemini using an API key and return the
     * generated text. On failure, return a failure Result with a meaningful message.
     */
    suspend fun summarizeDay(
        unreadMails: List<GmailMail>,
        todayEvents: List<CalendarEvent>,
        tasks: List<TaskItem>,
    ): Result<String>
}

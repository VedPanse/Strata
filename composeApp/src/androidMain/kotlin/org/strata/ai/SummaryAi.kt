/*
 * Strata - Kotlin Multiplatform client.
 * Copyright (c) 2025.
 */
package org.strata.ai

import org.strata.auth.CalendarEvent
import org.strata.auth.GmailMail
import org.strata.auth.TaskItem

/**
 * Android stub for Gemini summary; desktop handles the actual implementation.
 */
actual object SummaryAi {
    actual suspend fun summarizeDay(
        unreadMails: List<GmailMail>,
        todayEvents: List<CalendarEvent>,
        tasks: List<TaskItem>,
    ): Result<String> {
        return Result.failure(UnsupportedOperationException("Gemini summary not implemented on Android in this build"))
    }
}

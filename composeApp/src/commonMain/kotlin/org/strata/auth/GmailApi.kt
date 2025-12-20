/*
 * Strata - Kotlin Multiplatform client.
 * Copyright (c) 2025.
 */
package org.strata.auth

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Data model for displaying an email in UI.
 * Note: [snippet] is a short preview from Gmail; [body] carries the decoded full text we render.
 */
data class GmailMail(
    val id: String,
    val subject: String,
    val snippet: String,
    val from: String,
    val sentTime: LocalDateTime,
    val body: String = "",
    val attachments: List<String> = emptyList(),
    val inlineImageCount: Int = 0,
)

/** Platform bridge for Gmail API. */
expect object GmailApi {
    /**
     * Fetch top 5 unread messages for the current user.
     * @param accessToken OAuth access token with Gmail scope; if null or blank, returns failure.
     */
    suspend fun fetchTop5Unread(accessToken: String?): Result<List<GmailMail>>

    /**
     * Sends an email on behalf of the signed-in user.
     * Implemented minimally for plain-text body composition.
     */
    suspend fun sendEmail(
        accessToken: String?,
        to: String,
        subject: String,
        body: String,
    ): Result<Unit>

    /** Moves a message to Trash (Apple Mail's Delete behavior). */
    suspend fun deleteMessage(
        accessToken: String?,
        messageId: String,
    ): Result<Unit>

    /** Marks a message as read (removes the UNREAD label) without deleting it. */
    suspend fun markAsRead(
        accessToken: String?,
        messageId: String,
    ): Result<Unit>
}

internal fun epochMillisToLocal(ms: Long): LocalDateTime {
    return Instant.fromEpochMilliseconds(ms).toLocalDateTime(TimeZone.currentSystemDefault())
}

/*
 * Strata - Kotlin Multiplatform client.
 * Copyright (c) 2025.
 */
package org.strata.auth

actual object GmailApi {
    actual suspend fun fetchTop5Unread(accessToken: String?): Result<List<GmailMail>> {
        return Result.failure(UnsupportedOperationException("Gmail fetch not implemented on Android yet"))
    }

    actual suspend fun sendEmail(
        accessToken: String?,
        to: String,
        subject: String,
        body: String,
    ): Result<Unit> {
        return Result.failure(UnsupportedOperationException("Gmail send not implemented on Android yet"))
    }

    actual suspend fun deleteMessage(
        accessToken: String?,
        messageId: String,
    ): Result<Unit> {
        return Result.failure(UnsupportedOperationException("Gmail delete not implemented on Android yet"))
    }

    actual suspend fun markAsRead(
        accessToken: String?,
        messageId: String,
    ): Result<Unit> {
        return Result.failure(UnsupportedOperationException("Gmail markAsRead not implemented on Android yet"))
    }
}

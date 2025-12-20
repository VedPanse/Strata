/*
 * Strata - Kotlin Multiplatform client.
 * Copyright (c) 2025.
 */
package org.strata.ai

/**
 * Android stub for Gemini chat; desktop handles the actual implementation.
 */
actual object ChatAi {
    actual suspend fun sendPrompt(prompt: String): Result<String> {
        return Result.failure(UnsupportedOperationException("Gemini chat not implemented on Android in this build"))
    }

    actual suspend fun mailRewrite(
        subject: String,
        body: String,
        request: String,
    ): Result<String> {
        return Result.failure(UnsupportedOperationException("Gemini chat not implemented on Android in this build"))
    }
}

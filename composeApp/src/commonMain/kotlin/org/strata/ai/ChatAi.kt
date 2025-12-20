/*
 * Strata - Kotlin Multiplatform client.
 * Copyright (c) 2025.
 */
package org.strata.ai

/**
 * Multiplatform AI chat interface to send a free-form user prompt to Google Gemini
 * and return the generated text.
 */
expect object ChatAi {
    /**
     * Sends a free-form prompt to Google Gemini and returns the model's response text.
     * On failure, returns a failure Result with a meaningful message.
     */
    suspend fun sendPrompt(prompt: String): Result<String>

    /**
     * Rewrites an email body using the supplied user request.
     */
    suspend fun mailRewrite(
        subject: String,
        body: String,
        request: String,
    ): Result<String>
}

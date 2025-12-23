/*
 * Strata - Kotlin Multiplatform client.
 * Copyright (c) 2025.
 */
package org.strata.ai

data class ScreenAttachment(
    val base64Jpeg: String,
    val width: Int,
    val height: Int,
)

/**
 * Multiplatform AI chat interface to send a free-form user prompt and return
 * the generated text. The JVM implementation uses OpenAI for text-only chat
 * and Gemini when screen attachments are present.
 */
expect object ChatAi {
    /**
     * Sends a free-form prompt and returns the model's response text.
     * On failure, returns a failure Result with a meaningful message.
     */
    suspend fun sendPrompt(
        prompt: String,
        screen: ScreenAttachment? = null,
    ): Result<String>

    /**
     * Rewrites an email body using the supplied user request.
     */
    suspend fun mailRewrite(
        subject: String,
        body: String,
        request: String,
    ): Result<String>
}

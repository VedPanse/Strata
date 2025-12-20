/*
 * Strata - Kotlin Multiplatform client.
 * Copyright (c) 2025.
 */
package org.strata.mail.gmail

import java.util.Base64

/** Minimal shim matching Gmail payload tree we already deserialize. */
data class MessageBody(
    val data: String? = null,
)

data class MessagePart(
    val mimeType: String? = null,
    val filename: String? = null,
    val body: MessageBody? = null,
    val parts: List<MessagePart>? = null,
)

data class ExtractedBodies(
    // null if not present
    val html: String?,
    // null if not present
    val text: String?,
)

fun decodeBase64Url(s: String): String =
    try {
        val pad = (4 - s.length % 4) % 4
        val fixed = s.replace('-', '+').replace('_', '/') + "=".repeat(pad)
        String(Base64.getDecoder().decode(fixed))
    } catch (_: Exception) {
        ""
    }

/**
 * Walk a Gmail MessagePart tree and collect text/html and text/plain.
 * Prefer multipart/alternative semantics: take the deepest html if present,
 * otherwise fallback to the deepest text.
 */
fun extractBodies(part: MessagePart): ExtractedBodies {
    var html: String? = null
    var text: String? = null

    fun dfs(p: MessagePart) {
        val mime = (p.mimeType ?: "").lowercase()
        when {
            mime.startsWith("multipart/") -> p.parts?.forEach { dfs(it) }
            mime == "text/html" -> {
                val data = p.body?.data ?: ""
                val decoded = decodeBase64Url(data)
                if (decoded.isNotBlank()) html = decoded
            }
            mime == "text/plain" -> {
                val data = p.body?.data ?: ""
                val decoded = decodeBase64Url(data)
                if (decoded.isNotBlank()) text = decoded
            }
        }
    }
    dfs(part)
    return ExtractedBodies(html = html, text = text)
}

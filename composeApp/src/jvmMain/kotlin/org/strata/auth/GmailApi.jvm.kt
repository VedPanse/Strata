/*
 * Strata - Kotlin Multiplatform client.
 * Copyright (c) 2025.
 */
package org.strata.auth

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

actual object GmailApi {
    private val http = HttpClient(CIO)
    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun authedGetWithRefresh(
        url: String,
        accessToken: String,
    ): HttpResponse {
        var resp: HttpResponse = http.get(url) { header("Authorization", "Bearer $accessToken") }
        if (resp.status.value != 401 && resp.status.value != 403) return resp
        val newToken = TokenManager.refreshAccessToken()
        if (newToken.isNullOrBlank()) return resp
        return http.get(url) { header("Authorization", "Bearer $newToken") }
    }

    private fun withApiKey(url: String): String {
        val key = System.getenv("GOOGLE_API_KEY")?.trim().orEmpty()
        if (key.isBlank()) return url
        val sep = if (url.contains('?')) '&' else '?'
        return "$url${sep}key=${java.net.URLEncoder.encode(key, "UTF-8")}"
    }

    @Serializable
    private data class MessageListResponse(
        val messages: List<MessageRef>? = null,
        @SerialName("nextPageToken") val nextPageToken: String? = null,
        @SerialName("resultSizeEstimate") val estimate: Int? = null,
    )

    @Serializable
    private data class MessageRef(
        val id: String,
        @SerialName("threadId") val threadId: String? = null,
    )

    @Serializable
    private data class MessagePayload(
        val mimeType: String? = null,
        val filename: String? = null,
        val headers: List<MessageHeader>? = null,
        val body: MessageBody? = null,
        val parts: List<MessagePayload>? = null,
    )

    @Serializable
    private data class MessageHeader(val name: String, val value: String)

    @Serializable
    private data class MessageBody(
        val size: Int? = null,
        val data: String? = null,
        @SerialName("attachmentId") val attachmentId: String? = null,
    )

    @Serializable
    private data class MessageDetail(
        val id: String,
        val snippet: String = "",
        @SerialName("internalDate") val internalDate: String = "0",
        val payload: MessagePayload? = null,
    )

    actual suspend fun fetchTop5Unread(accessToken: String?): Result<List<GmailMail>> {
        if (accessToken.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Not signed in"))
        }
        val masked = accessToken.take(8) + "… (len=" + accessToken.length + ")"
        return try {
            // 1) List top unread message IDs using system label UNREAD (more reliable than search-only)
            val listUrlPrimary =
                withApiKey(
                    "https://gmail.googleapis.com/gmail/v1/users/me/messages" +
                        "?maxResults=5&labelIds=UNREAD",
                )
            val respPrimary: HttpResponse = authedGetWithRefresh(listUrlPrimary, accessToken)
            val statusPrimary = respPrimary.status.value
            val listTextPrimary = respPrimary.bodyAsText()
            val listPrimary =
                runCatching { json.decodeFromString<MessageListResponse>(listTextPrimary) }
                    .getOrNull()
            var ids = listPrimary?.messages?.map { it.id } ?: emptyList()

            // Fallback: use search query if label-based listing returns nothing
            if (ids.isEmpty()) {
                val listUrlFallback =
                    withApiKey(
                        "https://gmail.googleapis.com/gmail/v1/users/me/messages" +
                            "?q=is%3Aunread&maxResults=5",
                    )
                val respFallback: HttpResponse = authedGetWithRefresh(listUrlFallback, accessToken)
                val statusFallback = respFallback.status.value
                val listTextFallback = respFallback.bodyAsText()
                val listFallback =
                    runCatching { json.decodeFromString<MessageListResponse>(listTextFallback) }
                        .getOrNull()
                ids = listFallback?.messages?.map { it.id } ?: emptyList()
            }

            if (ids.isEmpty()) {
                return Result.success(emptyList())
            }

            // 2) For each id, fetch metadata (Subject, From, Date) and snippet
            val mails =
                ids.mapNotNull { id ->
                    runCatching {
                        val msgUrl =
                            withApiKey("https://gmail.googleapis.com/gmail/v1/users/me/messages/$id?format=full")
                        val msgResp: HttpResponse = authedGetWithRefresh(msgUrl, accessToken)
                        val msgStatus = msgResp.status.value
                        val msgText = msgResp.bodyAsText()
                        val detail = json.decodeFromString<MessageDetail>(msgText)

                        val headers = detail.payload?.headers.orEmpty()
                        val subject = headers.firstOrNull { it.name.equals("Subject", true) }?.value ?: "(no subject)"
                        val from = headers.firstOrNull { it.name.equals("From", true) }?.value ?: "Unknown"
                        val millis = detail.internalDate.toLongOrNull() ?: 0L
                        val time = epochMillisToLocal(millis)

                        // Extract full body, attachments, and inline images
                        val (bodyText, attachmentNames, inlineImages) = extractMessageContent(detail.payload)
                        GmailMail(
                            id = detail.id,
                            subject = subject,
                            snippet = detail.snippet,
                            from = from,
                            sentTime = time,
                            body = bodyText,
                            attachments = attachmentNames,
                            inlineImageCount = inlineImages,
                        )
                    }.getOrElse { null }
                }
            Result.success(mails)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    actual suspend fun sendEmail(
        accessToken: String?,
        to: String,
        subject: String,
        body: String,
    ): Result<Unit> {
        if (accessToken.isNullOrBlank()) return Result.failure(IllegalStateException("Not signed in"))
        return try {
            // Build RFC 2822 message
            val rawMessage =
                buildString {
                    append("To: ").append(to).append('\n')
                    append("Subject: ").append(subject).append('\n')
                    append("Content-Type: text/plain; charset=UTF-8\n")
                    append("MIME-Version: 1.0\n")
                    append('\n')
                    append(body)
                }
            val base64 = java.util.Base64.getEncoder().encodeToString(rawMessage.toByteArray(Charsets.UTF_8))
            val base64Url = base64.replace('+', '-').replace('/', '_').trimEnd('=')
            val url = withApiKey("https://gmail.googleapis.com/gmail/v1/users/me/messages/send")
            val resp: HttpResponse =
                http.post(url) {
                    header("Authorization", "Bearer $accessToken")
                    contentType(ContentType.Application.Json)
                    setBody("""{"raw":"$base64Url"}""")
                }
            val ok = resp.status.value in 200..299
            if (ok) {
                Result.success(
                    Unit,
                )
            } else {
                Result.failure(
                    IllegalStateException("Gmail send failed: ${resp.status.value} ${resp.bodyAsText()}"),
                )
            }
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    actual suspend fun deleteMessage(
        accessToken: String?,
        messageId: String,
    ): Result<Unit> {
        if (accessToken.isNullOrBlank()) return Result.failure(IllegalStateException("Not signed in"))
        return try {
            val url = withApiKey("https://gmail.googleapis.com/gmail/v1/users/me/messages/$messageId/trash")
            val resp: HttpResponse =
                http.post(url) {
                    header("Authorization", "Bearer $accessToken")
                    contentType(ContentType.Application.Json)
                    setBody("{}")
                }
            val ok = resp.status.value in 200..299
            if (ok) {
                Result.success(
                    Unit,
                )
            } else {
                Result.failure(
                    IllegalStateException("Gmail delete failed: ${resp.status.value} ${resp.bodyAsText()}"),
                )
            }
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    actual suspend fun markAsRead(
        accessToken: String?,
        messageId: String,
    ): Result<Unit> {
        if (accessToken.isNullOrBlank()) return Result.failure(IllegalStateException("Not signed in"))
        return try {
            val url = withApiKey("https://gmail.googleapis.com/gmail/v1/users/me/messages/$messageId/modify")
            val resp: HttpResponse =
                http.post(url) {
                    header("Authorization", "Bearer $accessToken")
                    contentType(ContentType.Application.Json)
                    setBody("""{"removeLabelIds":["UNREAD"]}""")
                }
            val ok = resp.status.value in 200..299
            if (ok) {
                Result.success(
                    Unit,
                )
            } else {
                Result.failure(
                    IllegalStateException("Gmail modify failed: ${resp.status.value} ${resp.bodyAsText()}"),
                )
            }
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    // ---- Helpers to extract content ----
    private fun extractMessageContent(payload: MessagePayload?): Triple<String, List<String>, Int> {
        if (payload == null) return Triple("", emptyList(), 0)
        val plainTexts = mutableListOf<String>()
        val htmlTexts = mutableListOf<String>()
        val attachments = mutableListOf<String>()
        var inlineImages = 0

        fun headerValue(
            part: MessagePayload,
            key: String,
        ): String? =
            part.headers
                ?.firstOrNull { it.name.equals(key, ignoreCase = true) }
                ?.value

        fun isInlineImage(part: MessagePayload): Boolean {
            val mt = part.mimeType?.lowercase() ?: ""
            if (!mt.startsWith("image/")) return false
            val cid = headerValue(part, "Content-ID")
            val disp = headerValue(part, "Content-Disposition")?.lowercase()
            return cid != null || (disp?.contains("inline") == true)
        }

        fun visit(part: MessagePayload) {
            val mime = part.mimeType?.lowercase() ?: ""
            val data = part.body?.data
            val filename = part.filename ?: ""
            val hasAttachment = !filename.isBlank() && part.body?.attachmentId != null

            if (data != null) {
                when {
                    mime.contains("text/plain") -> decodeBase64Url(data)?.let { plainTexts += it }
                    mime.contains("text/html") -> decodeBase64Url(data)?.let { htmlTexts += it }
                }
            }

            if (isInlineImage(part)) {
                inlineImages++
            } else if (hasAttachment) {
                attachments += filename
            }

            part.parts?.forEach { visit(it) }
        }

        visit(payload)

        val body =
            when {
                plainTexts.isNotEmpty() -> plainTexts.joinToString("\n\n").trim()
                htmlTexts.isNotEmpty() -> htmlTexts.joinToString("\n\n") { htmlToPlainText(it) }.trim()
                else -> ""
            }
        return Triple(body, attachments, inlineImages)
    }

    private fun decodeBase64Url(b64: String): String? =
        try {
            val cleaned =
                b64.replace('-', '+').replace('_', '/').let {
                    val pad = (4 - it.length % 4) % 4
                    it + "=".repeat(pad)
                }
            val bytes = java.util.Base64.getDecoder().decode(cleaned)
            String(bytes, Charsets.UTF_8)
        } catch (_: Throwable) {
            null
        }

    private fun htmlToPlainText(html: String): String {
        // Basic conversion to preserve newlines from <br> and paragraphs
        var s = html
        s =
            s.replace("<br>", "\n", ignoreCase = true)
                .replace("<br/>", "\n", ignoreCase = true)
                .replace("<br />", "\n", ignoreCase = true)
                .replace("</p>", "\n\n", ignoreCase = true)
                .replace("<p>", "", ignoreCase = true)
        // Strip all other tags
        s = s.replace(Regex("<[^>]+>"), "")
        // Decode a few common HTML entities
        s =
            s.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&#39;", "'")
                .replace("&quot;", "\"")
        return s
    }
}

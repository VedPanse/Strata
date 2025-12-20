/*
 * Strata - Kotlin Multiplatform client.
 * Copyright (c) 2025.
 */
package org.strata.auth

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Parameters
import io.ktor.http.contentType
import io.ktor.http.formUrlEncode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

actual object GoogleAuth {
    private val http = HttpClient(CIO)

    actual suspend fun signIn(scopes: List<String>): AuthResult {
        val clientId = readEnv("GOOGLE_DESKTOP_CLIENT_ID") ?: ""
        if (clientId.isBlank()) return AuthResult(false, error = "Missing GOOGLE_DESKTOP_CLIENT_ID")

        // 1) Pick a free loopback port and start a tiny callback server
        val port = findFreePort()
        val redirectUri = "http://127.0.0.1:$port/callback"
        val codeVerifier = randomUrlSafe(64)
        val codeChallenge = codeChallengeS256(codeVerifier)
        val codeDeferred = CompletableDeferred<String?>()
        val server = startCallbackServer(port) { code -> codeDeferred.complete(code) }

        try {
            // 2) Build auth URL (Desktop PKCE)
            val authUrl =
                buildString {
                    append("https://accounts.google.com/o/oauth2/v2/auth?")
                    append("client_id=").append(URLEncoder.encode(clientId, "UTF-8")).append("&")
                    append("redirect_uri=").append(URLEncoder.encode(redirectUri, "UTF-8")).append("&")
                    append("response_type=code&")
                    append("scope=").append(URLEncoder.encode(scopes.joinToString(" "), "UTF-8")).append("&")
                    append("code_challenge=").append(codeChallenge).append("&")
                    append("code_challenge_method=S256&")
                    append("access_type=offline&") // ask for refresh_token
                    append("prompt=consent") // ensure refresh_token first time
                }

            // 3) Open the browser
            try {
                java.awt.Desktop.getDesktop().browse(java.net.URI(authUrl))
            } catch (_: Throwable) {
                println("Open this URL in your browser:\n$authUrl")
            }

            // 4) Wait for the /callback?code=...
            val code = withTimeout(180_000) { codeDeferred.await() } // 3 minutes
            if (code.isNullOrBlank()) return AuthResult(false, error = "No authorization code received")

            // 5) Exchange code for tokens (TEMP: web client path includes client_secret)
            val tokenResponse: HttpResponse =
                http.post("https://oauth2.googleapis.com/token") {
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(
                        Parameters.build {
                            append("client_id", clientId)
                            // Optional: client_secret if configured via env; avoid hardcoding in apps.
                            readEnv("GOOGLE_DESKTOP_CLIENT_SECRET")?.takeIf { it.isNotBlank() }?.let {
                                append("client_secret", it)
                            }
                            append("code", code)
                            append("code_verifier", codeVerifier)
                            append("redirect_uri", redirectUri)
                            append("grant_type", "authorization_code")
                        }.formUrlEncode(),
                    )
                }

            val tokenText = tokenResponse.bodyAsText()
            val tok = parseTokenResponseSafe(tokenText)

            if (tokenResponse.status.value in 200..299 && !tok.access_token.isNullOrBlank()) {
                return AuthResult(
                    success = true,
                    accessToken = tok.access_token,
                    refreshToken = tok.refresh_token,
                    idToken = tok.id_token,
                )
            }

            val err =
                buildString {
                    append("OAuth failed")
                    if (!tok.error.isNullOrBlank()) {
                        append(": ").append(tok.error)
                        if (!tok.error_description.isNullOrBlank()) append(" — ").append(tok.error_description)
                    } else {
                        append(": ").append(tokenText)
                    }
                }
            return AuthResult(false, error = err)
        } finally {
            server.stop(0)
        }
    }
}

// ---------- helpers ----------
private fun findFreePort(): Int = ServerSocket(0).use { it.localPort }

private fun startCallbackServer(
    port: Int,
    onCode: (String?) -> Unit,
): HttpServer {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
    server.createContext("/callback") { ex: HttpExchange ->
        val query = ex.requestURI.rawQuery ?: ""
        val params =
            if (query.isBlank()) {
                emptyMap()
            } else {
                query.split("&").associate {
                    val (k, v) = (it.split("=", limit = 2) + "").take(2)
                    k to URLDecoder.decode(v, "UTF-8")
                }
            }
        val code = params["code"]
        val body = if (code != null) "You can close this window." else "No code received."
        ex.sendResponseHeaders(200, body.toByteArray().size.toLong())
        ex.responseBody.use { it.write(body.toByteArray()) }
        onCode(code)
    }
    server.executor = null
    server.start()
    return server
}

private fun randomUrlSafe(len: Int): String {
    val bytes = ByteArray(len)
    SecureRandom().nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).replace("=", "")
}

private fun codeChallengeS256(verifier: String): String {
    val md = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())
    return Base64.getUrlEncoder().withoutPadding().encodeToString(md)
}

// Robust token parser with error surfacing
private val json = Json { ignoreUnknownKeys = true }

@Serializable
private data class OAuthTokenResponse(
    val access_token: String? = null,
    val refresh_token: String? = null,
    val id_token: String? = null,
    val token_type: String? = null,
    val expires_in: Int? = null,
    val scope: String? = null,
    val error: String? = null,
    val error_description: String? = null,
)

private fun parseTokenResponseSafe(text: String): OAuthTokenResponse {
    val trimmed = text.trim()

    // 1) Try structured JSON decode first
    val decoded = runCatching { json.decodeFromString<OAuthTokenResponse>(trimmed) }.getOrNull()
    if (decoded != null && (
            !decoded.access_token.isNullOrBlank() ||
                !decoded.error.isNullOrBlank()
        )
    ) {
        return decoded
    }

    // 2) Try to parse as a JSON object manually (covers cases with number/string mismatch etc.)
    runCatching {
        val obj = json.parseToJsonElement(trimmed).jsonObject
        return OAuthTokenResponse(
            access_token = obj["access_token"]?.jsonPrimitive?.content,
            refresh_token = obj["refresh_token"]?.jsonPrimitive?.content,
            id_token = obj["id_token"]?.jsonPrimitive?.content,
            token_type = obj["token_type"]?.jsonPrimitive?.content,
            expires_in = obj["expires_in"]?.jsonPrimitive?.content?.toIntOrNull(),
            scope = obj["scope"]?.jsonPrimitive?.content,
            error = obj["error"]?.jsonPrimitive?.content,
            error_description = obj["error_description"]?.jsonPrimitive?.content,
        )
    }.getOrNull()?.let { return it }

    // 3) Try to parse as application/x-www-form-urlencoded (key=value&key2=value2)
    fun parseFormEncoded(body: String): Map<String, String> {
        if (body.isBlank()) return emptyMap()
        return body.split("&")
            .mapNotNull { pair ->
                if (pair.isBlank()) return@mapNotNull null
                val parts = pair.split("=", limit = 2)
                val k = parts.getOrNull(0)?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val raw = parts.getOrNull(1) ?: ""
                val v = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrElse { raw }
                k to v
            }
            .toMap()
    }

    // Accept both raw query-string and bodies that may include leading text
    val maybeQuery =
        when {
            // Some servers respond with leading messages, try to find first k=v
            '=' in trimmed && '&' in trimmed -> trimmed
            // Single pair like "error=invalid_grant"
            '=' in trimmed -> trimmed
            else -> ""
        }

    if (maybeQuery.isNotBlank()) {
        val map = parseFormEncoded(maybeQuery)
        if (map.isNotEmpty()) {
            return OAuthTokenResponse(
                access_token = map["access_token"],
                refresh_token = map["refresh_token"],
                id_token = map["id_token"],
                token_type = map["token_type"],
                expires_in = map["expires_in"]?.toIntOrNull(),
                scope = map["scope"],
                error = map["error"],
                error_description = map["error_description"] ?: map["error_message"],
            )
        }
    }

    // 4) Last resort: try to surface something meaningful from plain text
    // If the text contains typical OAuth error hints, expose them
    val lowered = trimmed.lowercase()
    val genericError =
        when {
            "invalid_grant" in lowered -> "invalid_grant"
            "invalid_client" in lowered -> "invalid_client"
            "invalid_request" in lowered -> "invalid_request"
            "unauthorized_client" in lowered -> "unauthorized_client"
            else -> null
        }
    if (genericError != null) {
        return OAuthTokenResponse(error = genericError, error_description = trimmed.take(500))
    }

    // Fallback to empty, but keep raw text in error_description to aid debugging upstream
    return OAuthTokenResponse(error = null, error_description = trimmed.take(500))
}

private fun readEnv(key: String): String? {
    // Prefer real environment variable
    System.getenv(key)?.let { if (it.isNotBlank()) return it }

    // Try to read from local .env files commonly used in this project
    val candidates =
        listOf(
            // when running from composeApp
            "src/.env",
            // when running from project root
            "composeApp/src/.env",
            // fallback
            ".env",
        )
    for (path in candidates) {
        val file = java.io.File(path)
        if (file.exists()) {
            val map =
                file.readLines()
                    .asSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .map { line ->
                        val idx = line.indexOf('=')
                        if (idx <= 0) "" to "" else line.substring(0, idx) to line.substring(idx + 1)
                    }
                    .toMap()
            map[key]?.let { return it }
        }
    }
    return null
}

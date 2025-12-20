/*
 * Strata - Kotlin Multiplatform client.
 * Copyright (c) 2025.
 */
package org.strata.auth

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Parameters
import io.ktor.http.contentType
import io.ktor.http.formUrlEncode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * JVM token manager that can refresh an expired Google OAuth access token
 * using a persisted refresh_token. Updates SessionStorage on success.
 */
internal object TokenManager {
    private val http = HttpClient(CIO)
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
        val decoded = runCatching { json.decodeFromString<OAuthTokenResponse>(trimmed) }.getOrNull()
        if (decoded != null && (!decoded.access_token.isNullOrBlank() || !decoded.error.isNullOrBlank())) return decoded
        return runCatching {
            val obj = json.parseToJsonElement(trimmed).jsonObject
            OAuthTokenResponse(
                access_token = obj["access_token"]?.jsonPrimitive?.content,
                refresh_token = obj["refresh_token"]?.jsonPrimitive?.content,
                id_token = obj["id_token"]?.jsonPrimitive?.content,
                token_type = obj["token_type"]?.jsonPrimitive?.content,
                expires_in = obj["expires_in"]?.jsonPrimitive?.content?.toIntOrNull(),
                scope = obj["scope"]?.jsonPrimitive?.content,
                error = obj["error"]?.jsonPrimitive?.content,
                error_description = obj["error_description"]?.jsonPrimitive?.content,
            )
        }.getOrElse { OAuthTokenResponse(error_description = trimmed.take(500)) }
    }

    /**
     * Attempts to refresh access token using stored refresh_token.
     * Returns new access token on success, or null on failure.
     */
    suspend fun refreshAccessToken(): String? {
        val session = SessionStorage.read()
        val refresh = session?.refreshToken?.takeIf { !it.isNullOrBlank() }
        if (refresh.isNullOrBlank()) {
            println("[AUTH_REFRESH] No refresh token available; cannot refresh.")
            return null
        }
        val clientId = readEnv("GOOGLE_DESKTOP_CLIENT_ID").orEmpty()
        if (clientId.isBlank()) {
            println("[AUTH_REFRESH] Missing GOOGLE_DESKTOP_CLIENT_ID; cannot refresh.")
            return null
        }
        val clientSecret =
            readEnv(
                "GOOGLE_DESKTOP_CLIENT_SECRET",
            ) // optional; avoid shipping secret, but allow if configured
        return try {
            val response =
                http.post("https://oauth2.googleapis.com/token") {
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(
                        Parameters.build {
                            append("client_id", clientId)
                            if (!clientSecret.isNullOrBlank()) append("client_secret", clientSecret)
                            append("grant_type", "refresh_token")
                            append("refresh_token", refresh)
                        }.formUrlEncode(),
                    )
                }
            val body = response.bodyAsText()
            val tok = parseTokenResponseSafe(body)
            val access = tok.access_token
            if (!access.isNullOrBlank()) {
                // Persist updated tokens (keep existing refresh token if server did not return a new one)
                val newSession =
                    AuthSession(
                        accessToken = access,
                        refreshToken = tok.refresh_token ?: session.refreshToken,
                        idToken = tok.id_token ?: session.idToken,
                    )
                SessionStorage.save(newSession)
                println("[AUTH_REFRESH] Successfully refreshed access token. New token length=${access.length}")
                return access
            } else {
                println("[AUTH_REFRESH] Refresh failed: ${tok.error ?: "unknown error"} ${tok.error_description ?: ""}")
                null
            }
        } catch (t: Throwable) {
            println("[AUTH_REFRESH] Exception while refreshing token: ${t.message}")
            null
        }
    }

    private fun readEnv(key: String): String? {
        // Prefer real environment variable
        System.getenv(key)?.let { if (it.isNotBlank()) return it }
        // Try local .env files (same logic as GoogleAuthDesktop)
        val candidates = listOf("src/.env", "composeApp/src/.env", ".env")
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
}

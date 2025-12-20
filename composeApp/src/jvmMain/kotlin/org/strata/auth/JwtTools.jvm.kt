/*
 * Strata - Kotlin Multiplatform client.
 * Copyright (c) 2025.
 */
package org.strata.auth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64

actual object JwtTools {
    actual fun extractName(idToken: String): String? {
        return try {
            val parts = idToken.split('.')
            if (parts.size < 2) return null
            val payloadB64 = parts[1]
            val normalized = payloadB64.replace('-', '+').replace('_', '/')
            val padded =
                when (normalized.length % 4) {
                    2 -> normalized + "=="
                    3 -> normalized + "="
                    else -> normalized
                }
            val decoded = Base64.getDecoder().decode(padded)
            val json = Json.parseToJsonElement(decoded.decodeToString()).jsonObject
            val nameEl = json["name"]?.jsonPrimitive
            val name = if (nameEl != null && !nameEl.isString) null else nameEl?.content
            if (!name.isNullOrBlank()) {
                name
            } else {
                val givenEl = json["given_name"]?.jsonPrimitive
                val familyEl = json["family_name"]?.jsonPrimitive
                val given = if (givenEl != null && !givenEl.isString) null else givenEl?.content
                val family = if (familyEl != null && !familyEl.isString) null else familyEl?.content
                when {
                    !given.isNullOrBlank() && !family.isNullOrBlank() -> "$given $family"
                    !given.isNullOrBlank() -> given
                    !family.isNullOrBlank() -> family
                    else -> null
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    actual fun extractPicture(idToken: String): String? {
        return try {
            val parts = idToken.split('.')
            if (parts.size < 2) return null
            val payloadB64 = parts[1]
            val normalized = payloadB64.replace('-', '+').replace('_', '/')
            val padded =
                when (normalized.length % 4) {
                    2 -> normalized + "=="
                    3 -> normalized + "="
                    else -> normalized
                }
            val decoded = Base64.getDecoder().decode(padded)
            val json = Json.parseToJsonElement(decoded.decodeToString()).jsonObject
            val picEl = json["picture"]?.jsonPrimitive
            val url = if (picEl != null && !picEl.isString) null else picEl?.content
            if (url.isNullOrBlank()) null else url
        } catch (_: Throwable) {
            null
        }
    }

    actual fun extractEmail(idToken: String): String? {
        return try {
            val parts = idToken.split('.')
            if (parts.size < 2) return null
            val payloadB64 = parts[1]
            val normalized = payloadB64.replace('-', '+').replace('_', '/')
            val padded =
                when (normalized.length % 4) {
                    2 -> normalized + "=="
                    3 -> normalized + "="
                    else -> normalized
                }
            val decoded = Base64.getDecoder().decode(padded)
            val json = Json.parseToJsonElement(decoded.decodeToString()).jsonObject
            val emailEl = json["email"]?.jsonPrimitive
            val email = if (emailEl != null && !emailEl.isString) null else emailEl?.content
            if (email.isNullOrBlank()) null else email
        } catch (_: Throwable) {
            null
        }
    }
}

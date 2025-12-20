/*
 * Strata - Kotlin Multiplatform client.
 * Copyright (c) 2025.
 */
package org.strata.auth

import android.util.Base64
import org.json.JSONObject

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
            val decoded = Base64.decode(padded, Base64.DEFAULT)
            val json = JSONObject(String(decoded, Charsets.UTF_8))
            val name = json.optString("name", null)
            if (!name.isNullOrBlank()) {
                name
            } else {
                val given = json.optString("given_name", null)
                val family = json.optString("family_name", null)
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
            val decoded = Base64.decode(padded, Base64.DEFAULT)
            val json = JSONObject(String(decoded, Charsets.UTF_8))
            val picture = json.optString("picture", null)
            if (picture.isNullOrBlank()) null else picture
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
            val decoded = Base64.decode(padded, Base64.DEFAULT)
            val json = JSONObject(String(decoded, Charsets.UTF_8))
            val email = json.optString("email", null)
            if (email.isNullOrBlank()) null else email
        } catch (_: Throwable) {
            null
        }
    }
}

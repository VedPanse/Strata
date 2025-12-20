/*
 * Strata - Kotlin Multiplatform client.
 * Copyright (c) 2025.
 */
package org.strata.auth

import android.content.Context
import android.content.SharedPreferences
import org.strata.platform.AppContext

actual object SessionStorage {
    private fun prefs(): SharedPreferences {
        val ctx: Context = AppContext.context
        return ctx.getSharedPreferences("strata_session", Context.MODE_PRIVATE)
    }

    actual fun isSignedIn(): Boolean = read() != null

    actual fun read(): AuthSession? {
        return try {
            val p = prefs()
            val access = p.getString("accessToken", null)
            val refresh = p.getString("refreshToken", null)
            val id = p.getString("idToken", null)
            if ((access == null || access.isEmpty()) && (id == null || id.isEmpty())) {
                null
            } else {
                AuthSession(access, refresh, id)
            }
        } catch (_: Exception) {
            null
        }
    }

    actual fun save(session: AuthSession) {
        try {
            prefs().edit()
                .putString("accessToken", session.accessToken)
                .putString("refreshToken", session.refreshToken)
                .putString("idToken", session.idToken)
                .apply()
        } catch (_: Exception) {
        }
    }

    actual fun clear() {
        try {
            prefs().edit().clear().apply()
        } catch (_: Exception) {
        }
    }
}

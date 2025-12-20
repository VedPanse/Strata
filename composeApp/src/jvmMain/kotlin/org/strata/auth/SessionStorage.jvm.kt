/*
 * Strata - Kotlin Multiplatform client.
 * Copyright (c) 2025.
 */
package org.strata.auth

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Properties

actual object SessionStorage {
    private val sessionDir: File by lazy {
        val home = System.getProperty("user.home") ?: "."
        File(home, ".strata").apply { if (!exists()) mkdirs() }
    }
    private val sessionFile: File by lazy { File(sessionDir, "session.properties") }

    actual fun isSignedIn(): Boolean = read() != null

    actual fun read(): AuthSession? {
        if (!sessionFile.exists()) return null
        return try {
            val props = Properties().apply { FileInputStream(sessionFile).use { load(it) } }
            val access = props.getProperty("accessToken")
            val refresh = props.getProperty("refreshToken")
            val id = props.getProperty("idToken")
            if (access.isNullOrEmpty() && id.isNullOrEmpty()) null else AuthSession(access, refresh, id)
        } catch (_: Exception) {
            null
        }
    }

    actual fun save(session: AuthSession) {
        val props =
            Properties().apply {
                setProperty("accessToken", session.accessToken ?: "")
                setProperty("refreshToken", session.refreshToken ?: "")
                setProperty("idToken", session.idToken ?: "")
            }
        try {
            FileOutputStream(sessionFile).use { props.store(it, "Strata session") }
        } catch (_: Exception) {
            // ignore
        }
    }

    actual fun clear() {
        if (sessionFile.exists()) {
            runCatching { sessionFile.delete() }
        }
    }
}

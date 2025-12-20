/*
 * Strata - Kotlin Multiplatform client.
 * Copyright (c) 2025.
 */
package org.strata.auth

/** Simple session model storing tokens we get back from sign-in. */
data class AuthSession(
    val accessToken: String?,
    val refreshToken: String?,
    val idToken: String?,
)

/** Multiplatform storage to persist the session between app runs. */
expect object SessionStorage {
    /** Returns true when a valid session is available. */
    fun isSignedIn(): Boolean

    /** Reads the current persisted session, if any. */
    fun read(): AuthSession?

    /** Persists the provided [session]. */
    fun save(session: AuthSession)

    /** Clears any stored session data. */
    fun clear()
}

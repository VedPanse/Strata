// commonMain/kotlin/org/strata/auth/GoogleAuth.kt
package org.strata.auth

/**
 * Result of an authentication attempt.
 * When [success] is true, at least [accessToken] is typically present depending on platform.
 */
data class AuthResult(
    val success: Boolean,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val idToken: String? = null,
    val error: String? = null,
)

/**
 * Platform bridge for Google sign-in. Implementations live in platform-specific source sets.
 */
expect object GoogleAuth {
    /** Triggers a sign-in flow for the provided OAuth [scopes]. */
    suspend fun signIn(scopes: List<String>): AuthResult
}

// androidMain/kotlin/org/strata/auth/GoogleAuthAndroid.kt
package org.strata.auth

// Minimal Android implementation to keep the project compiling.
// Wire up real Google Sign-In later with Activity Result APIs and proper client IDs.

actual object GoogleAuth {
    actual suspend fun signIn(scopes: List<String>): AuthResult {
        return AuthResult(
            success = false,
            accessToken = null,
            refreshToken = null,
            idToken = null,
            error = "Google Sign-In not configured on Android",
        )
    }
}

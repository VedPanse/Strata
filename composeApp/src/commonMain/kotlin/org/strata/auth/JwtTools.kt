/*
 * Strata - Kotlin Multiplatform client.
 * Copyright (c) 2025.
 */
package org.strata.auth

/**
 * Multiplatform helper to extract claims from a Google ID token (JWT).
 * Returns null if it cannot be parsed or the claim is missing.
 */
expect object JwtTools {
    /** Returns the "name" claim from the ID token, if present. */
    fun extractName(idToken: String): String?

    /** Returns the "picture" claim from the ID token, if present. */
    fun extractPicture(idToken: String): String?

    /** Returns the "email" claim from the ID token, if present. */
    fun extractEmail(idToken: String): String?
}

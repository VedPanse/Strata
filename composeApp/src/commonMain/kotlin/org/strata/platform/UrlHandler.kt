/*
 * Strata - Kotlin Multiplatform client.
 * Copyright (c) 2025.
 */
package org.strata.platform

expect object UrlHandler {
    suspend fun openUrl(url: String): Result<Unit>
}

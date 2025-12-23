/*
 * Strata - Kotlin Multiplatform client.
 * Copyright (c) 2025.
 */
package org.strata.platform

expect object AppLauncher {
    suspend fun openApp(
        appName: String? = null,
        packageName: String? = null,
    ): Result<Unit>
}

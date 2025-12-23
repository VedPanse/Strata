/*
 * Strata - Kotlin Multiplatform client.
 * Copyright (c) 2025.
 */
package org.strata.platform

actual object AppLauncher {
    actual suspend fun openApp(
        appName: String?,
        packageName: String?,
    ): Result<Unit> =
        runCatching {
            val name = appName?.trim().orEmpty()
            if (name.isBlank()) error("App name required on desktop.")
            val os = System.getProperty("os.name").lowercase()
            when {
                os.contains("mac") -> {
                    println("[AppLauncher] macOS open -a \"$name\"")
                    ProcessBuilder("open", "-a", name).start()
                }
                os.contains("win") -> {
                    println("[AppLauncher] Windows start \"$name\"")
                    ProcessBuilder("cmd", "/c", "start", "", name).start()
                }
                else -> {
                    println("[AppLauncher] Linux xdg-open \"$name\"")
                    ProcessBuilder("xdg-open", name).start()
                }
            }
        }
}

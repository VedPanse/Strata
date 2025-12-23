/*
 * Strata - Kotlin Multiplatform client.
 * Copyright (c) 2025.
 */
package org.strata.platform

import java.awt.Desktop
import java.net.URI

actual object UrlHandler {
    actual suspend fun openUrl(url: String): Result<Unit> =
        runCatching {
            val normalized = if (url.startsWith("http")) url else "https://$url"
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(URI(normalized))
            } else {
                val os = System.getProperty("os.name").lowercase()
                when {
                    os.contains("mac") -> ProcessBuilder("open", normalized).start()
                    os.contains("win") -> ProcessBuilder("cmd", "/c", "start", "", normalized).start()
                    else -> ProcessBuilder("xdg-open", normalized).start()
                }
            }
        }
}

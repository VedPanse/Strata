/*
 * Strata - Kotlin Multiplatform client.
 * Copyright (c) 2025.
 */
package org.strata

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main() {
    // Ensure macOS menu/title bar shows the branded name instead of the generated MainKt class name
    System.setProperty("apple.awt.application.name", "Strata")

    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Strata",
            state = rememberWindowState(placement = WindowPlacement.Maximized),
        ) {
            App()
        }
    }
}

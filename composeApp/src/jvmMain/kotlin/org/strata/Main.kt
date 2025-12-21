/*
 * Strata - Kotlin Multiplatform client.
 * Copyright (c) 2025.
 */
package org.strata

import androidx.compose.ui.Alignment
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.strata.perception.DesktopOverlayState
import org.strata.ui.ScreenOverlayPrompt

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

        if (DesktopOverlayState.visible.value) {
            Window(
                onCloseRequest = { DesktopOverlayState.visible.value = false },
                title = "Strata Overlay",
                alwaysOnTop = true,
                undecorated = true,
                resizable = false,
                transparent = true,
                state =
                    rememberWindowState(
                        size = DpSize(420.dp, 820.dp),
                        position = WindowPosition.Aligned(Alignment.BottomEnd),
                    ),
            ) {
                ScreenOverlayPrompt(
                    onClose = {
                        DesktopOverlayState.visible.value = false
                        org.strata.perception.ScreenPerception.stopStream()
                    },
                    onBeforeSend = { org.strata.perception.ScreenPerception.record(forceVision = true) },
                )
            }
        }
    }
}

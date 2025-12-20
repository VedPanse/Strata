/*
 * Strata - Kotlin Multiplatform client.
 * Copyright (c) 2025.
 */
package org.strata

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import cafe.adriel.voyager.navigator.Navigator
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.Font
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.strata.ui.OnboardingScreen
import strata.composeapp.generated.resources.Res
import strata.composeapp.generated.resources.Roboto_Regular

// Define your custom colors
private val DarkColorScheme =
    darkColorScheme(
        primary = Color.White,
        secondary = Color.Gray,
        tertiary = Color(0xFF3700B3),
        background = Color.Black,
        surface = Color(0xFF121212),
        onPrimary = Color.Black,
        onSecondary = Color.Black,
        onTertiary = Color.White,
        onBackground = Color.White,
        onSurface = Color.White,
    )

/**
 * Root Composable for the Strata app.
 * - Applies the dark Material theme and app typography.
 * - Decides the start destination based on persisted auth session.
 */
@OptIn(ExperimentalResourceApi::class)
@Composable
@Preview
fun App() {
    val robotoFamily = FontFamily(Font(Res.font.Roboto_Regular))
    val typography = createTypographyWithFont(robotoFamily)
    MaterialTheme(colorScheme = DarkColorScheme, typography = typography) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            // Decide initial screen based on persisted session
            var startReady by remember { mutableStateOf(false) }
            var startOnHome by remember { mutableStateOf(false) }

            // Synchronous check; storage access is not suspending
            LaunchedEffect(Unit) {
                startOnHome = org.strata.auth.SessionStorage.isSignedIn()
                startReady = true
            }

            if (startReady) {
                val initial = if (startOnHome) org.strata.ui.Homescreen() else OnboardingScreen()
                Navigator(initial)
            }
        }
    }
}

/**
 * Creates a [Typography] that uses the provided [fontFamily] for all text styles,
 * while preserving size/weight values from the default Material3 typography.
 */
private fun createTypographyWithFont(fontFamily: FontFamily): Typography {
    val base = Typography()
    return Typography(
        displayLarge = base.displayLarge.copy(fontFamily = fontFamily),
        displayMedium = base.displayMedium.copy(fontFamily = fontFamily),
        displaySmall = base.displaySmall.copy(fontFamily = fontFamily),
        headlineLarge = base.headlineLarge.copy(fontFamily = fontFamily),
        headlineMedium = base.headlineMedium.copy(fontFamily = fontFamily),
        headlineSmall = base.headlineSmall.copy(fontFamily = fontFamily),
        titleLarge = base.titleLarge.copy(fontFamily = fontFamily),
        titleMedium = base.titleMedium.copy(fontFamily = fontFamily),
        titleSmall = base.titleSmall.copy(fontFamily = fontFamily),
        bodyLarge = base.bodyLarge.copy(fontFamily = fontFamily),
        bodyMedium = base.bodyMedium.copy(fontFamily = fontFamily),
        bodySmall = base.bodySmall.copy(fontFamily = fontFamily),
        labelLarge = base.labelLarge.copy(fontFamily = fontFamily),
        labelMedium = base.labelMedium.copy(fontFamily = fontFamily),
        labelSmall = base.labelSmall.copy(fontFamily = fontFamily),
    )
}

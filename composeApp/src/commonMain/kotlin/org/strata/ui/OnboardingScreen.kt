/*
 * Strata - Kotlin Multiplatform client.
 * Copyright (c) 2025.
 */
package org.strata.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import org.jetbrains.compose.resources.painterResource
import org.strata.auth.AuthViewModel
import org.strata.platform.isAndroidPhone
import strata.composeapp.generated.resources.Res
import strata.composeapp.generated.resources.Wallpaper

class OnboardingScreen : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val vm = remember { AuthViewModel() }
        val isLoading by vm.isLoading.collectAsState()
        val error by vm.error.collectAsState()

        if (isAndroidPhone()) {
            // Android phones: full-screen background image with content overlaid
            Box(modifier = Modifier.fillMaxSize()) {
                Image(
                    painter = painterResource(Res.drawable.Wallpaper),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
                Column(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.Start,
                ) {
                    Text(
                        text = "Your life. Organized. Personalized.",
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 34.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "Strata brings your calendar, email, tasks, health, and finances into one smart dashboard.",
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
                        fontSize = 14.sp,
                    )
                    Spacer(Modifier.height(24.dp))
                    StrataButton(
                        text = if (isLoading) "Connecting to Google…" else "Sign up with Google",
                        enabled = !isLoading,
                    ) {
                        vm.signIn {
                            navigator.push(Homescreen())
                        }
                    }
                    if (error != null) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = error ?: "",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        } else {
            // Desktop and other platforms: keep current split layout
            Row(
                modifier = Modifier.fillMaxSize(),
            ) {
                // Left: Image takes 75% of the width
                Image(
                    painter = painterResource(Res.drawable.Wallpaper),
                    contentDescription = null,
                    modifier =
                        Modifier
                            .fillMaxHeight()
                            .weight(0.75f),
                    contentScale = ContentScale.Crop,
                )

                // Right: Text and button content (25% width)
                Column(
                    modifier =
                        Modifier
                            .fillMaxHeight()
                            .weight(0.25f)
                            .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.Start,
                ) {
                    Text(
                        text = "Your life. Organized. Personalized.",
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 34.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "Strata brings your calendar, email, tasks, health, and finances into one smart dashboard.",
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
                        fontSize = 14.sp,
                    )
                    Spacer(Modifier.height(24.dp))
                    StrataButton(
                        text = if (isLoading) "Connecting to Google…" else "Sign up with Google",
                        enabled = !isLoading,
                    ) {
                        vm.signIn {
                            navigator.push(Homescreen())
                        }
                    }
                    if (error != null) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = error ?: "",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }
}

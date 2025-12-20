/*
 * Strata - Kotlin Multiplatform client.
 * Copyright (c) 2025.
 */
package org.strata.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap

// Cross-platform simple network image loader returning an ImageBitmap or null on failure
// Implementations should cache within composition (e.g., remember) and perform IO off the main thread.
@Composable expect fun rememberNetworkImageBitmap(url: String): ImageBitmap?

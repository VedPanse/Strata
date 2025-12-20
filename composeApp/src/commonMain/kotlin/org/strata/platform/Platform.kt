/*
 * Strata - Kotlin Multiplatform client.
 * Copyright (c) 2025.
 */
package org.strata.platform

/**
 * Returns true when the current device should be treated as a handset for UI layout decisions.
 *
 * Android implementations typically gate this on `smallestScreenWidthDp < 600`.
 * Desktop and other platforms return false.
 */
expect fun isAndroidPhone(): Boolean

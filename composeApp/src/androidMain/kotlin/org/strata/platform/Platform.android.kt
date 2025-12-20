/*
 * Strata - Kotlin Multiplatform client.
 * Copyright (c) 2025.
 */
package org.strata.platform

import android.content.res.Resources

actual fun isAndroidPhone(): Boolean {
    val sw = Resources.getSystem().configuration.smallestScreenWidthDp
    // If value is undefined (0), assume phone to be safe for the requested behavior
    return sw == 0 || sw < 600
}

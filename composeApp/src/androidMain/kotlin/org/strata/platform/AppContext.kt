/*
 * Strata - Kotlin Multiplatform client.
 * Copyright (c) 2025.
 */
package org.strata.platform

import android.app.Activity
import android.content.Context
import java.lang.ref.WeakReference

object AppContext {
    lateinit var context: Context
    @Volatile var activity: WeakReference<Activity>? = null
}

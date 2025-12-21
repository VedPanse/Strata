/*
 * Strata - Kotlin Multiplatform client.
 * Copyright (c) 2025.
 */
package org.strata.perception

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

class StrataAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val root = rootInActiveWindow ?: return
        val snapshot = buildAccessibilitySnapshot(root)
        AccessibilitySnapshotStore.update(snapshot)
    }

    override fun onInterrupt() = Unit
}

/*
 * Strata - Kotlin Multiplatform client.
 * Copyright (c) 2025.
 */
package org.strata.reminder

import org.strata.auth.TaskItem

/**
 * Android implementation is currently a no-op until Tasks API support ships.
 */
actual object ReminderSystemNotifier {
    actual fun notifyDue(task: TaskItem) {
        // No-op on Android target for now.
    }
}

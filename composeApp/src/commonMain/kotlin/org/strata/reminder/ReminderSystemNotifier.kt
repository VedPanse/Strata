/*
 * Strata - Kotlin Multiplatform client.
 * Copyright (c) 2025.
 */
package org.strata.reminder

import org.strata.auth.TaskItem

/**
 * Platform-specific hook to surface system notifications for due reminders.
 */
expect object ReminderSystemNotifier {
    /**
     * Deliver a platform notification that [task] is due right now.
     */
    fun notifyDue(task: TaskItem)
}

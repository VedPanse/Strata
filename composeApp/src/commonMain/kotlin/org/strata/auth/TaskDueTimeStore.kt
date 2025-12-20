/*
 * Strata - Kotlin Multiplatform client.
 * Copyright (c) 2025.
 */
package org.strata.auth

import kotlinx.datetime.LocalDateTime

/**
 * Stores reminder due times locally so we can restore them when Google Tasks
 * returns date-only values. Each platform provides its own persistence backend.
 */
expect object TaskDueTimeStore {
    /** Returns the stored due timestamp for [taskId], if any. */
    fun read(taskId: String): LocalDateTime?

    /**
     * Persists [due] for [taskId]. Passing null removes any stored value.
     */
    fun save(
        taskId: String,
        due: LocalDateTime?,
    )

    /**
     * Optionally drop entries that are no longer needed. Platforms may ignore.
     */
    fun prune(validTaskIds: Set<String>)
}

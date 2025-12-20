/*
 * Strata - Kotlin Multiplatform client.
 * Copyright (c) 2025.
 */
package org.strata.auth

import kotlinx.datetime.LocalDateTime

actual object TaskDueTimeStore {
    private val lock = Any()
    private val memory = mutableMapOf<String, LocalDateTime>()

    actual fun read(taskId: String): LocalDateTime? = synchronized(lock) { memory[taskId] }

    actual fun save(
        taskId: String,
        due: LocalDateTime?,
    ): Unit =
        synchronized(lock) {
            if (due == null) memory.remove(taskId) else memory[taskId] = due
        }

    actual fun prune(validTaskIds: Set<String>): Unit =
        synchronized(lock) {
            if (validTaskIds.isEmpty()) return@synchronized
            memory.keys.retainAll(validTaskIds)
        }
}

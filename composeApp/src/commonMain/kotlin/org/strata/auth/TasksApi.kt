/*
 * Strata - Kotlin Multiplatform client.
 * Copyright (c) 2025.
 */
package org.strata.auth

import kotlinx.datetime.LocalDateTime

/**
 * Simple read-only model for rendering Google Tasks items in UI.
 */
data class TaskItem(
    val id: String,
    val title: String,
    val notes: String? = null,
    val due: LocalDateTime? = null,
    val completed: Boolean = false,
    val listTitle: String? = null,
)

/**
 * Platform bridge for Google Tasks API.
 */
expect object TasksApi {
    /**
     * Fetch up to 100 upcoming (not completed) tasks from the user's default task list.
     * If [accessToken] is null/blank, returns failure.
     */
    suspend fun fetchTopTasks(accessToken: String?): Result<List<TaskItem>>

    /**
     * Create a new task in the user's default list.
     */
    suspend fun createTask(
        accessToken: String?,
        title: String,
        notes: String? = null,
        due: LocalDateTime? = null,
    ): Result<String> // returns taskId

    /**
     * Push partial changes for a task. Only non-null parameters are updated.
     * Returns success on 2xx, failure otherwise.
     */
    suspend fun pushTaskChanges(
        accessToken: String?,
        taskId: String,
        title: String? = null,
        notes: String? = null,
        due: LocalDateTime? = null,
        completed: Boolean? = null,
    ): Result<Unit>

    /**
     * Delete a task from the user's default list.
     */
    suspend fun deleteTask(
        accessToken: String?,
        taskId: String,
    ): Result<Unit>
}

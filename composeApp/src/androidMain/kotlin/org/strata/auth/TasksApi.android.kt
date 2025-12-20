/*
 * Strata - Kotlin Multiplatform client.
 * Copyright (c) 2025.
 */
package org.strata.auth

actual object TasksApi {
    actual suspend fun fetchTopTasks(accessToken: String?): Result<List<TaskItem>> {
        return Result.failure(UnsupportedOperationException("Tasks fetch not implemented on Android yet"))
    }

    actual suspend fun createTask(
        accessToken: String?,
        title: String,
        notes: String?,
        due: kotlinx.datetime.LocalDateTime?,
    ): Result<String> {
        return Result.failure(UnsupportedOperationException("Tasks create not implemented on Android yet"))
    }

    actual suspend fun pushTaskChanges(
        accessToken: String?,
        taskId: String,
        title: String?,
        notes: String?,
        due: kotlinx.datetime.LocalDateTime?,
        completed: Boolean?,
    ): Result<Unit> {
        return Result.failure(UnsupportedOperationException("Tasks update not implemented on Android yet"))
    }

    actual suspend fun deleteTask(
        accessToken: String?,
        taskId: String,
    ): Result<Unit> {
        return Result.failure(UnsupportedOperationException("Tasks delete not implemented on Android yet"))
    }
}

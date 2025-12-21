/*
 * Strata - Kotlin Multiplatform client.
 * Copyright (c) 2025.
 */
package org.strata.persistence

import kotlinx.serialization.Serializable

@Serializable
data class PendingPlan(
    val id: String,
    val status: String,
    val question: String,
    val context: String?,
    val actionJson: String?,
    val updatedAt: Long,
)

object PlanStore {
    private const val ACTIVE_ID = "active"

    private val database by lazy { StrataDatabase(PlanDatabaseDriver.create()) }
    private val queries get() = database.strataDatabaseQueries

    fun getPending(): PendingPlan? {
        val row = queries.selectAll().executeAsList().firstOrNull() ?: return null
        val question = row.pending_question ?: return null
        return PendingPlan(
            id = row.id,
            status = row.status,
            question = question,
            context = row.pending_context,
            actionJson = row.pending_action_json,
            updatedAt = row.updated_at,
        )
    }

    fun savePending(
        status: String,
        question: String,
        context: String?,
        actionJson: String?,
    ) {
        queries.upsert(
            id = ACTIVE_ID,
            status = status,
            pending_question = question,
            pending_context = context,
            pending_action_json = actionJson,
            updated_at = System.currentTimeMillis(),
        )
    }

    fun clearPending() {
        queries.clearById(ACTIVE_ID)
    }
}

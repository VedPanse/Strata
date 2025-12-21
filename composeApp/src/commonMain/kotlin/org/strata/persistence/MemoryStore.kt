/*
 * Strata - Kotlin Multiplatform client.
 * Copyright (c) 2025.
 */
package org.strata.persistence

import kotlin.random.Random

object MemoryStore {
    private val database by lazy { StrataDatabase(PlanDatabaseDriver.create()) }
    private val queries get() = database.strataDatabaseQueries

    fun list(limit: Int = 20): List<String> {
        return queries.selectAllMemory().executeAsList().take(limit).map { it.content }
    }

    fun add(content: String) {
        val trimmed = content.trim()
        if (trimmed.isBlank()) return
        queries.insertMemory(
            id = generateId(),
            content = trimmed,
            created_at = System.currentTimeMillis(),
        )
    }

    fun clear() {
        queries.clearMemory()
    }

    private fun generateId(): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
        return buildString {
            repeat(12) {
                append(alphabet[Random.nextInt(alphabet.length)])
            }
        }
    }
}

/*
 * Strata - Kotlin Multiplatform client.
 * Copyright (c) 2025.
 */
package org.strata.ai

private data class CacheEntry(
    val value: String,
    val savedAtMs: Long,
)

object LlmCache {
    private const val MAX_ENTRIES = 50
    private const val TTL_MS = 5 * 60 * 1000

    private val lock = Any()
    private val entries: LinkedHashMap<String, CacheEntry> =
        object : LinkedHashMap<String, CacheEntry>(MAX_ENTRIES, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean {
                return size > MAX_ENTRIES
            }
        }

    fun get(key: String): String? =
        synchronized(lock) {
            val entry = entries[key] ?: return null
            val now = System.currentTimeMillis()
            if (now - entry.savedAtMs > TTL_MS) {
                entries.remove(key)
                return null
            }
            entry.value
        }

    fun put(
        key: String,
        value: String,
    ) {
        synchronized(lock) {
            entries[key] = CacheEntry(value = value, savedAtMs = System.currentTimeMillis())
        }
    }
}

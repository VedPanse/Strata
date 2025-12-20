/*
 * Strata - Kotlin Multiplatform client.
 * Copyright (c) 2025.
 */
package org.strata.auth

import kotlinx.datetime.LocalDateTime
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Properties

actual object TaskDueTimeStore {
    private val lock = Any()

    private val storeDir: File by lazy {
        val home = System.getProperty("user.home") ?: "."
        File(home, ".strata").apply { if (!exists()) mkdirs() }
    }

    private val storeFile: File by lazy { File(storeDir, "task_due_times.properties") }

    @Volatile
    private var cache: MutableMap<String, String>? = null

    private fun ensureLoaded(): MutableMap<String, String> {
        val existing = cache
        if (existing != null) return existing
        val loaded = loadFromDisk()
        cache = loaded
        return loaded
    }

    private fun loadFromDisk(): MutableMap<String, String> {
        if (!storeFile.exists()) return mutableMapOf()
        return runCatching {
            val props = Properties().apply { FileInputStream(storeFile).use { load(it) } }
            props.stringPropertyNames().associateWith { props.getProperty(it) }.toMutableMap()
        }.getOrElse { mutableMapOf() }
    }

    private fun persist(map: MutableMap<String, String>) {
        if (map.isEmpty()) {
            cache = mutableMapOf()
            if (storeFile.exists()) runCatching { storeFile.delete() }
            return
        }
        val props =
            Properties().apply {
                map.forEach { (k, v) -> setProperty(k, v) }
            }
        runCatching {
            FileOutputStream(storeFile).use { out ->
                props.store(out, "Strata reminder due times")
            }
        }
        cache = map
    }

    actual fun read(taskId: String): LocalDateTime? =
        synchronized(lock) {
            val stored = ensureLoaded()[taskId] ?: return@synchronized null
            runCatching { LocalDateTime.parse(stored) }.getOrNull()
        }

    actual fun save(
        taskId: String,
        due: LocalDateTime?,
    ) = synchronized(lock) {
        val map = ensureLoaded()
        val existing = map[taskId]
        val newValue = due?.toString()
        if (newValue == existing) return@synchronized
        if (newValue == null) map.remove(taskId) else map[taskId] = newValue
        persist(map)
    }

    actual fun prune(validTaskIds: Set<String>) =
        synchronized(lock) {
            if (validTaskIds.isEmpty()) return@synchronized
            val map = ensureLoaded()
            var changed = false
            val it = map.keys.iterator()
            while (it.hasNext()) {
                val key = it.next()
                if (key !in validTaskIds) {
                    it.remove()
                    changed = true
                }
            }
            if (changed) persist(map)
        }
}

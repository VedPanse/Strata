/*
 * Strata - Kotlin Multiplatform client.
 * Copyright (c) 2025.
 */
package org.strata.persistence

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File

actual object PlanDatabaseDriver {
    actual fun create(): SqlDriver {
        val baseDir = File(System.getProperty("user.home"), ".strata")
        if (!baseDir.exists()) {
            baseDir.mkdirs()
        }
        val dbFile = File(baseDir, "strata.db")
        val url = "jdbc:sqlite:${dbFile.absolutePath}"
        val driver = JdbcSqliteDriver(url)
        if (!dbFile.exists()) {
            StrataDatabase.Schema.create(driver)
        } else {
            // Ensure schema exists for first run in case file was created without tables.
            runCatching { StrataDatabase.Schema.create(driver) }
        }
        return driver
    }
}

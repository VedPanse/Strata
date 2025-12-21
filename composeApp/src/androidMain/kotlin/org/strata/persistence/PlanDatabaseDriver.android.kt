/*
 * Strata - Kotlin Multiplatform client.
 * Copyright (c) 2025.
 */
package org.strata.persistence

import app.cash.sqldelight.android.AndroidSqliteDriver
import app.cash.sqldelight.db.SqlDriver
import org.strata.platform.AppContext

actual object PlanDatabaseDriver {
    actual fun create(): SqlDriver {
        return AndroidSqliteDriver(StrataDatabase.Schema, AppContext.context, "strata.db")
    }
}

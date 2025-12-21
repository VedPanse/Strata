/*
 * Strata - Kotlin Multiplatform client.
 * Copyright (c) 2025.
 */
package org.strata.persistence

import app.cash.sqldelight.db.SqlDriver

expect object PlanDatabaseDriver {
    fun create(): SqlDriver
}

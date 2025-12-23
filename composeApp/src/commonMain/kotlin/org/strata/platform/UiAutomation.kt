/*
 * Strata - Kotlin Multiplatform client.
 * Copyright (c) 2025.
 */
package org.strata.platform

expect object UiAutomation {
    suspend fun moveCursor(
        x: Int,
        y: Int,
    ): Result<Unit>

    suspend fun click(
        x: Int,
        y: Int,
        button: String? = null,
        count: Int = 1,
    ): Result<Unit>

    suspend fun mouseDown(
        x: Int,
        y: Int,
        button: String? = null,
    ): Result<Unit>

    suspend fun mouseUp(
        x: Int,
        y: Int,
        button: String? = null,
    ): Result<Unit>

    suspend fun drag(
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        durationMs: Int? = null,
        button: String? = null,
    ): Result<Unit>

    suspend fun tap(
        x: Int,
        y: Int,
    ): Result<Unit>

    suspend fun typeText(text: String): Result<Unit>

    /**
     * Scroll by pixel deltas. Positive dy scrolls down, negative dy scrolls up.
     */
    suspend fun scroll(
        dx: Int,
        dy: Int,
    ): Result<Unit>

    suspend fun pressKey(key: String): Result<Unit>

    suspend fun keyCombo(keys: List<String>): Result<Unit>
}

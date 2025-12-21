/*
 * Strata - Kotlin Multiplatform client.
 * Copyright (c) 2025.
 */
package org.strata.ai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

data class LlmHealthStatus(
    val usedRequests: Int = 0,
    val dailyLimit: Int? = null,
    val exhausted: Boolean = false,
    val lastError: String? = null,
)

object LlmHealth {
    private val _status = MutableStateFlow(LlmHealthStatus())
    val status: StateFlow<LlmHealthStatus> = _status
    private var consecutiveFailures = 0
    private var blockedUntilMs: Long = 0
    private var lastResetDate: kotlinx.datetime.LocalDate? = null

    fun setDailyLimit(limit: Int?) {
        _status.update { it.copy(dailyLimit = limit) }
    }

    fun requestBlockReason(): String? {
        ensureDailyReset()
        val now = System.currentTimeMillis()
        val current = _status.value
        val dailyLimit = current.dailyLimit
        if (dailyLimit != null && current.usedRequests >= dailyLimit) {
            _status.update { it.copy(exhausted = true) }
            return "Daily LLM limit reached."
        }
        if (blockedUntilMs > now) {
            val seconds = ((blockedUntilMs - now + 999) / 1000).toInt()
            return "LLM cooldown active for ${seconds}s."
        }
        return null
    }

    fun recordSuccess() {
        ensureDailyReset()
        consecutiveFailures = 0
        blockedUntilMs = 0
        _status.update { current ->
            current.copy(
                usedRequests = current.usedRequests + 1,
                exhausted = false,
                lastError = null,
            )
        }
    }

    fun recordFailure(error: Throwable?) {
        ensureDailyReset()
        val message = error?.message.orEmpty()
        val exhausted =
            message.contains("rate_limit", ignoreCase = true) ||
                message.contains("quota", ignoreCase = true) ||
                message.contains("resource_exhausted", ignoreCase = true) ||
                message.contains(" 429", ignoreCase = true) ||
                message.startsWith("429") ||
                message.contains("invalid_api_key", ignoreCase = true) ||
                message.contains("expired", ignoreCase = true)
        val invalidKey =
            message.contains("invalid_api_key", ignoreCase = true) ||
                message.contains("expired", ignoreCase = true)
        val now = System.currentTimeMillis()
        consecutiveFailures += 1
        blockedUntilMs =
            when {
                invalidKey -> maxOf(blockedUntilMs, now + 10 * 60 * 1000)
                exhausted -> maxOf(blockedUntilMs, now + 2 * 60 * 1000)
                consecutiveFailures >= 3 -> maxOf(blockedUntilMs, now + 30 * 1000)
                else -> blockedUntilMs
            }
        _status.update { current ->
            current.copy(
                usedRequests = current.usedRequests + 1,
                exhausted = if (exhausted) true else current.exhausted,
                lastError = error?.message,
            )
        }
    }

    private fun ensureDailyReset() {
        val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
        if (lastResetDate == null || lastResetDate != today) {
            lastResetDate = today
            consecutiveFailures = 0
            blockedUntilMs = 0
            _status.update { current ->
                current.copy(
                    usedRequests = 0,
                    exhausted = false,
                    lastError = null,
                )
            }
        }
    }
}

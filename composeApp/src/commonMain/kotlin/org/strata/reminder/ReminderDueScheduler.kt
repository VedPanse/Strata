/*
 * Strata - Kotlin Multiplatform client.
 * Copyright (c) 2025.
 */
package org.strata.reminder

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.strata.auth.TaskItem
import kotlin.math.max

/**
 * Keeps track of upcoming reminder due times and emits notifications when they fire.
 */
object ReminderDueScheduler {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    private data class ScheduledEntry(val due: LocalDateTime, val job: Job?)

    private val scheduled = mutableMapOf<String, ScheduledEntry>()
    private val delivered = mutableMapOf<String, LocalDateTime>()

    private val _alerts = MutableSharedFlow<TaskItem>(extraBufferCapacity = 8)
    val alerts: SharedFlow<TaskItem> = _alerts

    /**
     * Update scheduler with the latest [tasks]. Any tasks without a due time or already completed
     * are ignored. Tasks removed from the list are unscheduled.
     */
    fun updateTasks(tasks: List<TaskItem>) {
        scope.launch { processUpdate(tasks) }
    }

    private suspend fun processUpdate(tasks: List<TaskItem>) {
        val relevant = tasks.filter { shouldTrack(it) }
        val incomingIds = relevant.mapTo(mutableSetOf()) { it.id }
        val jobsToCancel = mutableListOf<Job>()

        mutex.withLock {
            val removed = scheduled.keys - incomingIds
            for (id in removed) {
                scheduled.remove(id)?.job?.let { jobsToCancel += it }
                delivered.remove(id)
            }
        }

        jobsToCancel.forEach { it.cancel() }

        relevant.forEach { task -> scheduleTask(task) }
    }

    private fun shouldTrack(task: TaskItem): Boolean {
        val due = task.due ?: return false
        if (task.completed) return false
        return !due.isDateOnly()
    }

    private suspend fun scheduleTask(task: TaskItem) {
        val due = task.due ?: return
        val tz = TimeZone.currentSystemDefault()
        val dueInstant = due.toInstant(tz)
        val now = Clock.System.now()

        var alreadyScheduled = false
        var emitImmediately = false
        var scheduleDelayJob = false
        var jobToCancel: Job? = null

        mutex.withLock {
            val existing = scheduled[task.id]
            if (existing != null && existing.due == due && existing.job?.isActive == true) {
                alreadyScheduled = true
                return@withLock
            }

            if (existing != null && existing.due != due) {
                jobToCancel = existing.job
                delivered.remove(task.id)
            } else if (existing != null && existing.job?.isActive == false) {
                jobToCancel = existing.job
            } else if (existing == null) {
                delivered.remove(task.id)
            }

            if (dueInstant <= now) {
                scheduled[task.id] = ScheduledEntry(due, null)
                emitImmediately = true
            } else {
                scheduleDelayJob = true
                scheduled[task.id] = ScheduledEntry(due, null)
            }
        }

        if (alreadyScheduled) return

        jobToCancel?.cancel()

        if (emitImmediately) {
            emitAlertIfNew(task)
            return
        }

        if (scheduleDelayJob) {
            val job =
                scope.launch {
                    val delayMillis = max(0L, (dueInstant - Clock.System.now()).inWholeMilliseconds)
                    if (delayMillis > 0) delay(delayMillis)
                    val shouldEmit =
                        mutex.withLock {
                            val current = scheduled[task.id]
                            if (current == null || current.due != due) return@withLock false
                            scheduled[task.id] = ScheduledEntry(due, null)
                            true
                        }
                    if (shouldEmit) {
                        emitAlertIfNew(task)
                    }
                }

            mutex.withLock {
                val current = scheduled[task.id]
                if (current != null && current.due == due) {
                    scheduled[task.id] = ScheduledEntry(due, job)
                } else {
                    job.cancel()
                }
            }
        }
    }

    private suspend fun emitAlertIfNew(task: TaskItem) {
        val due = task.due ?: return
        val shouldEmit =
            mutex.withLock {
                val previous = delivered[task.id]
                if (previous != null && previous == due) return@withLock false
                delivered[task.id] = due
                true
            }
        if (!shouldEmit) return

        _alerts.emit(task)
        ReminderSystemNotifier.notifyDue(task)
    }

    private fun LocalDateTime.isDateOnly(): Boolean = hour == 0 && minute == 0 && second == 0 && nanosecond == 0
}

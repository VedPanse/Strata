/*
 * Strata - Kotlin Multiplatform client.
 * Copyright (c) 2025.
 */
package org.strata.ui.reminder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDateTime
import org.strata.ai.TaskHighlightState
import org.strata.auth.SessionStorage
import org.strata.auth.TaskItem
import org.strata.auth.TasksApi
import kotlin.collections.forEach

@Composable
fun Reminders(
    isLoading: Boolean,
    error: String?,
    tasks: List<TaskItem>,
) {
    val scope = rememberCoroutineScope()
    val token = SessionStorage.read()?.accessToken
    val highlighted by TaskHighlightState.highlightedIds.collectAsState()
    when {
        isLoading -> {
            Text("Loading Tasks…", color = MaterialTheme.colorScheme.secondary)
        }
        error != null -> {
            Text(error, color = Color(0xFFFFA000))
        }
        tasks.isEmpty() -> {
            Text("No tasks right now.", color = MaterialTheme.colorScheme.secondary)
        }
        else -> {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                tasks.forEach { t ->
                    var localTitle by remember(t.id) { mutableStateOf(t.title) }
                    var localNotes by remember(t.id) { mutableStateOf(t.notes ?: "") }
                    var localDue by remember(t.id) { mutableStateOf(t.due) }
                    var localCompleted by remember(t.id) { mutableStateOf(t.completed) }

                    // Track last pushed values to avoid redundant requests
                    var lastPushedTitle by remember(t.id) { mutableStateOf(t.title) }
                    var lastPushedNotes by remember(t.id) { mutableStateOf(t.notes ?: "") }

                    fun push(
                        title: String? = null,
                        notes: String? = null,
                        due: LocalDateTime? = null,
                        completed: Boolean? = null,
                    ) {
                        if (token.isNullOrBlank()) return
                        scope.launch {
                            val result =
                                TasksApi.pushTaskChanges(
                                    accessToken = token,
                                    taskId = t.id,
                                    title = title,
                                    notes = notes,
                                    due = due,
                                    completed = completed,
                                )
                            result.onSuccess {
                                // success
                            }.onFailure { e ->
                                // failure handled via Result
                            }
                        }
                    }

                    // Debounce title changes: wait for user pause before pushing
                    LaunchedEffect(localTitle) {
                        delay(600)
                        val v = localTitle
                        if (v != lastPushedTitle) {
                            push(title = v)
                            lastPushedTitle = v
                        } else {
                        }
                    }
                    // Debounce notes changes
                    LaunchedEffect(localNotes) {
                        delay(700)
                        val v = localNotes
                        if (v != lastPushedNotes) {
                            push(notes = v)
                            lastPushedNotes = v
                        } else {
                        }
                    }

                    ReminderUI(
                        date = localDue,
                        reminder = localTitle,
                        completed = localCompleted,
                        notes = localNotes,
                        highlighted = highlighted.contains(t.id),
                        onToggleCompleted = {
                            localCompleted = !localCompleted
                            push(completed = localCompleted)
                        },
                        onReminderChange = { v ->
                            localTitle = v
                            // do not push immediately; debounced above
                        },
                        onNotesChange = { v ->
                            localNotes = v
                            // do not push immediately; debounced above
                        },
                        onDateChange = { dt ->
                            localDue = dt
                            push(due = dt)
                        },
                        onTitleEditDone = {
                            val v = localTitle
                            if (v != lastPushedTitle) {
                                push(title = v)
                                lastPushedTitle = v
                            }
                        },
                        onNotesEditDone = {
                            val v = localNotes
                            if (v != lastPushedNotes) {
                                push(notes = v)
                                lastPushedNotes = v
                            }
                        },
                    )
                }
            }
        }
    }
}

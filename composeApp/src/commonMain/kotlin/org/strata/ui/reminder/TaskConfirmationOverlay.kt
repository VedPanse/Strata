/*
 * Strata - Kotlin Multiplatform client.
 * Copyright (c) 2025.
 */
package org.strata.ui.reminder

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import org.strata.ai.TaskConfirmationBus
import org.strata.ai.TaskConfirmationRequest
import org.strata.ai.TaskHighlightState
import org.strata.auth.TaskItem

@Composable
fun TaskConfirmationOverlay(
    modifier: Modifier = Modifier,
    onNavigateToReminders: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var active by remember { mutableStateOf<TaskConfirmationRequest?>(null) }

    LaunchedEffect(Unit) {
        TaskConfirmationBus.requests.collect { req ->
            active = req
            TaskHighlightState.set(req.tasks.map(TaskItem::id).toSet())
            onNavigateToReminders()
        }
    }

    LaunchedEffect(active) {
        if (active == null) {
            TaskHighlightState.clear()
        }
    }

    val request = active ?: return

    Dialog(
        onDismissRequest = {
            scope.launch {
                request.result.complete(false)
                active = null
            }
        },
        properties =
            DialogProperties(
                dismissOnBackPress = true,
                dismissOnClickOutside = true,
                usePlatformDefaultWidth = false,
            ),
    ) {
        Box(
            modifier =
                modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f)),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                tonalElevation = 6.dp,
                shape = MaterialTheme.shapes.large,
                modifier =
                    Modifier
                        .fillMaxWidth(0.45f)
                        .fillMaxHeight(0.55f),
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = "Please confirm",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        text = request.reason,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(
                        modifier =
                            Modifier
                                .weight(1f, fill = true)
                                .fillMaxWidth(),
                    ) {
                        items(request.tasks) { task ->
                            TaskRowSummary(task)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Button(
                            onClick = {
                                scope.launch {
                                    request.result.complete(true)
                                    active = null
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(request.confirmLabel)
                        }
                        Button(
                            onClick = {
                                scope.launch {
                                    request.result.complete(false)
                                    active = null
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors =
                                ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                ),
                        ) {
                            Text(request.cancelLabel)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskRowSummary(task: TaskItem) {
    val due = task.due
    val dueText =
        if (due != null) {
            val date = "%04d-%02d-%02d".format(due.year, due.monthNumber, due.dayOfMonth)
            val time = "%02d:%02d".format(due.hour, due.minute)
            "$date • $time"
        } else {
            "No due date"
        }

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(Color.White.copy(alpha = 0.02f), MaterialTheme.shapes.medium)
                .padding(12.dp),
    ) {
        Text(
            text = task.title,
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = dueText,
            style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.secondary),
        )
        task.notes?.takeIf { it.isNotBlank() }?.let { notes ->
            Spacer(Modifier.height(4.dp))
            Text(
                text = notes,
                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.secondary),
            )
        }
    }
}

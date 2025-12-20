/*
 * Strata - Kotlin Multiplatform client.
 * Copyright (c) 2025.
 */
package org.strata.ui.reminder

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.painterResource
import org.strata.auth.SessionStorage
import org.strata.auth.TaskItem
import org.strata.auth.TasksApi
import org.strata.reminder.ReminderDueScheduler
import strata.composeapp.generated.resources.Res
import strata.composeapp.generated.resources.StrataNoText

/**
 * Simple in-app notification host that displays due reminders.
 */
@Composable
fun ReminderDueNotifications(
    modifier: Modifier = Modifier,
    onNavigateToReminders: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val alerts = remember { mutableStateListOf<ReminderAlert>() }

    LaunchedEffect(Unit) {
        ReminderDueScheduler.alerts.collect { task ->
            alerts += ReminderAlert(task = task)
        }
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        alerts.forEach { alert ->
            ReminderDueCard(
                task = alert.task,
                onDismiss = { alerts.remove(alert) },
                onOpenReminders = {
                    alerts.remove(alert)
                    onNavigateToReminders()
                },
                onMarkDone = {
                    alerts.remove(alert)
                    scope.launch {
                        val token = SessionStorage.read()?.accessToken
                        if (!token.isNullOrBlank()) {
                            TasksApi.pushTaskChanges(
                                accessToken = token,
                                taskId = alert.task.id,
                                completed = true,
                            )
                        }
                    }
                },
            )

            LaunchedEffect(alert.key) {
                delay(12_000)
                alerts.remove(alert)
            }
        }
    }
}

private data class ReminderAlert(
    val task: TaskItem,
    val createdAtMillis: Long = Clock.System.now().toEpochMilliseconds(),
) {
    val key: String = "${task.id}:${task.due}:$createdAtMillis"
}

@Composable
private fun ReminderDueCard(
    task: TaskItem,
    onDismiss: () -> Unit,
    onOpenReminders: () -> Unit,
    onMarkDone: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF14191F),
        tonalElevation = 8.dp,
        shadowElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(18.dp).width(320.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier =
                        Modifier
                            .background(Color(0x332D9CDB), RoundedCornerShape(12.dp))
                            .padding(8.dp),
                ) {
                    androidx.compose.foundation.Image(
                        painter = painterResource(Res.drawable.StrataNoText),
                        contentDescription = "Strata",
                        modifier =
                            Modifier
                                .size(24.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = task.title.ifBlank { "Reminder due" },
                        style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = formatDueForDisplay(task.due),
                        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.secondary),
                    )
                }
                TextButton(onClick = onDismiss) {
                    Text("Dismiss")
                }
            }

            task.notes?.takeIf { it.isNotBlank() }?.let { notes ->
                Spacer(Modifier.height(12.dp))
                Text(
                    text = notes.trim(),
                    style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.secondary),
                )
            }

            Spacer(Modifier.height(16.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(onClick = onOpenReminders, modifier = Modifier.weight(1f)) {
                    Text("Open Reminders")
                }
                Button(
                    onClick = onMarkDone,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1F2A34)),
                ) {
                    Icon(imageVector = Icons.Filled.CheckCircle, contentDescription = null, tint = Color(0xFF2D9CDB))
                    Spacer(Modifier.width(6.dp))
                    Text("Mark done")
                }
            }
        }
    }
}

private fun formatDueForDisplay(due: LocalDateTime?): String {
    if (due == null) return "Due now"
    val tz = TimeZone.currentSystemDefault()
    val now = Clock.System.now().toLocalDateTime(tz)
    val today = now.date
    val tomorrow = kotlinx.datetime.LocalDate.fromEpochDays(today.toEpochDays() + 1)

    val isTimeUnset = due.hour == 0 && due.minute == 0
    val timePart =
        if (isTimeUnset) {
            "time not set"
        } else {
            run {
                var hour = due.hour % 12
                if (hour == 0) hour = 12
                val minute = due.minute
                val minuteStr = if (minute < 10) "0$minute" else minute.toString()
                val ampm = if (due.hour < 12) "AM" else "PM"
                "$hour:$minuteStr $ampm"
            }
        }

    return when (due.date) {
        today -> if (isTimeUnset) "Today ($timePart)" else "Today at $timePart"
        tomorrow -> if (isTimeUnset) "Tomorrow ($timePart)" else "Tomorrow at $timePart"
        else -> {
            val monthNames = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
            val month = monthNames[due.monthNumber - 1]
            if (isTimeUnset) "${due.dayOfMonth} $month ($timePart)" else "${due.dayOfMonth} $month at $timePart"
        }
    }
}

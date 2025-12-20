/*
 * Strata - Kotlin Multiplatform client.
 * Copyright (c) 2025.
 */
package org.strata.ui.reminder

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import org.strata.ui.MONTHS

/**
 * Reminder list item UI.
 * This is a purely visual component: all state updates are reported via callbacks.
 */
@Composable
fun ReminderUI(
    date: LocalDateTime?,
    reminder: String = "",
    completed: Boolean = false,
    notes: String = "",
    highlighted: Boolean = false,
    onToggleCompleted: () -> Unit = {},
    onReminderChange: (String) -> Unit = {},
    onNotesChange: (String) -> Unit = {},
    onDateChange: (LocalDateTime) -> Unit = {},
    onTitleEditDone: () -> Unit = {},
    onNotesEditDone: () -> Unit = {},
) {
    // Helpers
    fun formatReminderDate(dt: LocalDateTime?): String {
        val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
        if (dt == null) return "Time not set"
        val today = now.date
        val tomorrow = kotlinx.datetime.LocalDate.fromEpochDays(today.toEpochDays() + 1)
        val isTimeUnset = (dt.hour == 0 && dt.minute == 0)
        val monthShort = MONTHS[dt.monthNumber - 1]
        if (isTimeUnset) {
            return when (dt.date) {
                today -> "Today (time not set)"
                tomorrow -> "Tomorrow (time not set)"
                else -> "${dt.dayOfMonth} $monthShort (time not set)"
            }
        }
        var hour = dt.hour % 12
        if (hour == 0) hour = 12
        val minuteStr = if (dt.minute < 10) "0${dt.minute}" else "${dt.minute}"
        val ampm = if (dt.hour < 12) "AM" else "PM"
        val timeStr = "$hour:$minuteStr $ampm"
        return when (dt.date) {
            today -> "Today $timeStr"
            tomorrow -> "Tomorrow $timeStr"
            else -> "${dt.dayOfMonth} $monthShort, $timeStr"
        }
    }

    val borderShape = RoundedCornerShape(16.dp)
    val chipShape = RoundedCornerShape(8.dp)
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary

    // Local edit state flags (UI mode only; actual values hoisted via callbacks)
    var isEditingTitle by remember { mutableStateOf(false) }
    var isEditingNotes by remember { mutableStateOf(false) }
    var showDateEditor by remember { mutableStateOf(false) }

    // When marked completed, exit any active edit modes and close editors
    LaunchedEffect(completed) {
        if (completed) {
            val wasEditingTitle = isEditingTitle
            val wasEditingNotes = isEditingNotes
            isEditingTitle = false
            isEditingNotes = false
            showDateEditor = false
            if (wasEditingTitle) onTitleEditDone()
            if (wasEditingNotes) onNotesEditDone()
        }
    }

    val baseBackground = if (highlighted) Color(0xFF1C2532) else Color(0xFF141618)
    val baseBorder = if (highlighted) Color(0xFF2D9CDB) else Color.White.copy(alpha = 0.08f)

    Column(
        modifier =
            Modifier
                .width(700.dp)
                .background(baseBackground, borderShape)
                .border(1.5.dp, baseBorder, borderShape)
                .padding(16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            // Circular checkbox look (only this toggles completion)
            val checkBorder = if (completed) Color(0xFF2D9CDB) else Color.White.copy(alpha = 0.6f)
            Box(
                modifier =
                    Modifier
                        .width(22.dp)
                        .height(22.dp)
                        .border(2.dp, checkBorder, CircleShape)
                        .background(
                            if (completed) checkBorder.copy(alpha = 0.25f) else Color.Transparent,
                            CircleShape,
                        )
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable { onToggleCompleted() },
            ) {
                if (completed) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = "Completed",
                        tint = Color.White,
                        modifier =
                            Modifier.align(Alignment.Center)
                                .size(16.dp),
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                val titleColor = if (completed) secondary.copy(alpha = 0.7f) else primary
                if (isEditingTitle) {
                    BasicTextField(
                        value = reminder,
                        onValueChange = onReminderChange,
                        singleLine = true,
                        textStyle =
                            MaterialTheme.typography.titleMedium.copy(
                                color = titleColor,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold,
                            ),
                        keyboardOptions =
                            androidx.compose.foundation.text.KeyboardOptions.Default.copy(
                                imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                            ),
                        keyboardActions =
                            androidx.compose.foundation.text.KeyboardActions(onDone = {
                                isEditingTitle = false
                                onTitleEditDone()
                            }),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(Color(0xFF2D9CDB)),
                        decorationBox = { inner ->
                            Box(Modifier.fillMaxWidth()) {
                                if (reminder.isBlank()) {
                                    Text("New Reminder", color = titleColor)
                                }
                                inner()
                            }
                        },
                    )
                } else {
                    Text(
                        text = reminder.ifBlank { "New Reminder" },
                        color = titleColor,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 18.sp,
                        modifier = (
                            if (!completed) {
                                Modifier.pointerHoverIcon(PointerIcon.Hand).clickable {
                                    isEditingTitle = true
                                }
                            } else {
                                Modifier
                            }
                        ),
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                // Date chip
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Determine chip color based on time state
                    val nowInstant = Clock.System.now()
                    val tz = TimeZone.currentSystemDefault()
                    val isTimeUnset = (date == null) || (date.hour == 0 && date.minute == 0)
                    val isPastDue =
                        !isTimeUnset &&
                            run {
                                val dueInstant = date!!.toInstant(tz)
                                dueInstant < nowInstant
                            }
                    val blue = Color(0xFF2D9CDB)
                    val chipTextColor =
                        when {
                            isTimeUnset -> MaterialTheme.colorScheme.secondary
                            isPastDue -> MaterialTheme.colorScheme.error
                            else -> blue
                        }
                    val chipBgColor =
                        when {
                            isTimeUnset -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f)
                            isPastDue -> MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
                            else -> blue.copy(alpha = 0.12f)
                        }

                    Text(
                        text = formatReminderDate(date),
                        color = chipTextColor,
                        modifier =
                            Modifier
                                .background(chipBgColor, chipShape)
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                                .then(
                                    if (!completed) {
                                        Modifier.pointerHoverIcon(PointerIcon.Hand).clickable {
                                            showDateEditor = !showDateEditor
                                        }
                                    } else {
                                        Modifier
                                    },
                                ),
                        fontSize = 12.sp,
                    )
                }

                if (showDateEditor) {
                    Spacer(modifier = Modifier.height(8.dp))
                    // Simple inline date-time editor (YYYY-MM-DD and HH:MM 24h)
                    androidx.compose.runtime.key(date) {
                        val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
                        val base =
                            date
                                ?: kotlinx.datetime.LocalDateTime(
                                    now.year,
                                    now.monthNumber,
                                    now.dayOfMonth,
                                    0,
                                    0,
                                )
                        val currentDateStr = "%04d-%02d-%02d".format(base.year, base.monthNumber, base.dayOfMonth)
                        val currentTimeStr = "%02d:%02d".format(base.hour, base.minute)
                        var dateStr by androidx.compose.runtime.remember(currentDateStr) {
                            androidx.compose.runtime.mutableStateOf(currentDateStr)
                        }
                        var timeStr by androidx.compose.runtime.remember(currentTimeStr) {
                            androidx.compose.runtime.mutableStateOf(currentTimeStr)
                        }
                        Column(
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier =
                                Modifier
                                    .background(Color(0xFF0F1112), RoundedCornerShape(8.dp))
                                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
                                    .padding(8.dp),
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("Date:", color = secondary, fontSize = 12.sp)
                                BasicTextField(
                                    value = dateStr,
                                    onValueChange = { dateStr = it },
                                    singleLine = true,
                                    textStyle =
                                        MaterialTheme.typography.bodyMedium.copy(
                                            color = Color.White,
                                            fontSize = 13.sp,
                                        ),
                                    cursorBrush = androidx.compose.ui.graphics.SolidColor(Color(0xFF2D9CDB)),
                                    decorationBox = { inner ->
                                        Box(
                                            Modifier.background(
                                                Color(0x221E90FF),
                                                RoundedCornerShape(4.dp),
                                            ).padding(horizontal = 6.dp, vertical = 4.dp),
                                        ) {
                                            inner()
                                        }
                                    },
                                )
                                Text("Time:", color = secondary, fontSize = 12.sp)
                                BasicTextField(
                                    value = timeStr,
                                    onValueChange = { timeStr = it },
                                    singleLine = true,
                                    textStyle =
                                        MaterialTheme.typography.bodyMedium.copy(
                                            color = Color.White,
                                            fontSize = 13.sp,
                                        ),
                                    cursorBrush = androidx.compose.ui.graphics.SolidColor(Color(0xFF2D9CDB)),
                                    decorationBox = { inner ->
                                        Box(
                                            Modifier.background(
                                                Color(0x221E90FF),
                                                RoundedCornerShape(4.dp),
                                            ).padding(horizontal = 6.dp, vertical = 4.dp),
                                        ) {
                                            inner()
                                        }
                                    },
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = {
                                        try {
                                            val parts = dateStr.trim().split("-")
                                            val timeParts = timeStr.trim().split(":")
                                            if (parts.size == 3 && timeParts.size >= 2) {
                                                val y = parts[0].toInt()
                                                val m = parts[1].toInt()
                                                val d = parts[2].toInt()
                                                val hh = timeParts[0].toInt()
                                                val mm = timeParts[1].toInt()
                                                val newDt = kotlinx.datetime.LocalDateTime(y, m, d, hh, mm)
                                                onDateChange(newDt)
                                                showDateEditor = false
                                            }
                                        } catch (_: Throwable) {
                                            // Ignore invalid input
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2D9CDB)),
                                ) { Text("Save", fontSize = 12.sp) }
                                Button(
                                    onClick = { showDateEditor = false },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0x332D9CDB)),
                                ) { Text("Cancel", fontSize = 12.sp) }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                // Notes (editable on click). If blank, still show placeholder so users can add notes.
                val showNotesField = true
                if (showNotesField) {
                    if (isEditingNotes) {
                        BasicTextField(
                            value = notes,
                            onValueChange = onNotesChange,
                            singleLine = false,
                            textStyle = MaterialTheme.typography.bodySmall.copy(color = secondary, fontSize = 13.sp),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(Color(0xFF2D9CDB)),
                            decorationBox = { inner ->
                                Box(Modifier.fillMaxWidth()) {
                                    if (notes.isBlank()) {
                                        Text("Add notes here", color = Color(0xFF2B2B2B), fontSize = 13.sp)
                                    }
                                    inner()
                                }
                            },
                            keyboardOptions =
                                androidx.compose.foundation.text.KeyboardOptions.Default.copy(
                                    imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                                ),
                            keyboardActions =
                                androidx.compose.foundation.text.KeyboardActions(onDone = {
                                    isEditingNotes = false
                                    onNotesEditDone()
                                }),
                        )
                    } else {
                        Text(
                            text = if (notes.isBlank()) "Add notes here" else notes,
                            color = if (notes.isBlank()) Color(0xFF2B2B2B) else secondary,
                            fontSize = 13.sp,
                            modifier = (
                                if (!completed) {
                                    Modifier.pointerHoverIcon(PointerIcon.Hand).clickable {
                                        isEditingNotes = true
                                    }
                                } else {
                                    Modifier
                                }
                            ),
                        )
                    }
                }
            }
        }
    }
}

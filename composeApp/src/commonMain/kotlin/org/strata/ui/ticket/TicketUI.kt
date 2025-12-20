/*
 * Strata - Kotlin Multiplatform client.
 * Copyright (c) 2025.
 */
package org.strata.ui.ticket

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Train
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.strata.ui.HorizontalLine
import org.strata.ui.MONTHS
import kotlin.collections.get

// Domain enums with lightweight UI helpers
enum class TicketTypes { FLIGHT, TRAIN, BUS }

enum class TicketStatus { ON_TIME, DELAYED, CANCELLED }

private val TicketTypes.displayText: String
    get() =
        when (this) {
            TicketTypes.FLIGHT -> "Flight"
            TicketTypes.TRAIN -> "Train"
            TicketTypes.BUS -> "Bus"
        }

private val TicketTypes.icon: ImageVector
    get() =
        when (this) {
            TicketTypes.FLIGHT -> Icons.Filled.Flight
            TicketTypes.TRAIN -> Icons.Filled.Train
            TicketTypes.BUS -> Icons.Filled.DirectionsBus
        }

private val TicketStatus.displayText: String
    get() =
        when (this) {
            TicketStatus.ON_TIME -> "On time"
            TicketStatus.DELAYED -> "Delayed"
            TicketStatus.CANCELLED -> "Cancelled"
        }

private val TicketStatus.color: Color
    get() =
        when (this) {
            TicketStatus.ON_TIME -> Color(0xFF4CAF50) // green
            TicketStatus.DELAYED -> Color(0xFFFFC107) // amber
            TicketStatus.CANCELLED -> Color(0xFFF44336) // red
        }

// Shared constants

/**
 * UI model representing a travel ticket with routing and time details.
 */
data class TicketUiModel(
    val type: TicketTypes,
    val number: String,
    val status: TicketStatus,
    val departureAirport: String,
    val departureAirportAbbr: String,
    val arrivalAirport: String,
    val arrivalAirportAbbr: String,
    val departureDateTime: LocalDateTime,
    val arrivalDateTime: LocalDateTime,
)

@Composable
private fun StatusChip(status: TicketStatus) {
    val c = status.color
    Text(
        status.displayText,
        color = c,
        modifier =
            Modifier
                .background(c.copy(alpha = 0.1f), RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 4.dp),
        fontSize = 14.sp,
    )
}

@Composable
private fun TicketHeader(
    type: TicketTypes,
    number: String,
    status: TicketStatus,
) {
    Text(type.displayText, color = MaterialTheme.colorScheme.secondary)
    Text(
        text = number,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        fontSize = 25.sp,
    )
    Spacer(modifier = Modifier.height(20.dp))
    Text("Status", color = MaterialTheme.colorScheme.secondary)
    StatusChip(status)
}

@Composable
private fun RouteRow(
    type: TicketTypes,
    fromAbbr: String,
    fromName: String,
    toAbbr: String,
    toName: String,
) {
    Spacer(modifier = Modifier.height(20.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
    ) {
        Column(modifier = Modifier.weight(0.4f)) {
            Text(
                fromAbbr,
                fontSize = 25.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                fromName,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.fillMaxWidth(),
                softWrap = true,
                maxLines = 4,
                overflow = androidx.compose.ui.text.style.TextOverflow.Clip,
            )
        }
        Box(
            modifier = Modifier.weight(0.2f),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = type.icon,
                contentDescription = type.displayText,
                tint = Color.White,
                modifier = if (type == TicketTypes.FLIGHT) Modifier.rotate(90f) else Modifier,
            )
        }
        Column(modifier = Modifier.weight(0.4f), horizontalAlignment = Alignment.End) {
            Text(
                toAbbr,
                fontSize = 25.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Right,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                toName,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.secondary,
                textAlign = TextAlign.Right,
                modifier = Modifier.fillMaxWidth(),
                softWrap = true,
                maxLines = 4,
                overflow = androidx.compose.ui.text.style.TextOverflow.Clip,
            )
        }
    }
    Spacer(modifier = Modifier.height(20.dp))
}

@Composable
private fun TimeSection(
    label: String,
    value: String,
) {
    Text(label, color = MaterialTheme.colorScheme.secondary)
    Text(value, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun DurationRow(text: String) {
    Text("Duration", color = MaterialTheme.colorScheme.secondary)
    Text(text, color = MaterialTheme.colorScheme.primary)
}

// New model-based API
private fun formatHumanDateTime(dt: LocalDateTime): String {
    val monthShort = MONTHS[dt.monthNumber - 1]
    val day = dt.dayOfMonth
    var hour = dt.hour % 12
    if (hour == 0) hour = 12
    val minute = dt.minute
    val ampm = if (dt.hour < 12) "AM" else "PM"
    val minuteStr = if (minute < 10) "0$minute" else "$minute"
    return "$day $monthShort, $hour:$minuteStr $ampm"
}

private fun formatDuration(
    dep: LocalDateTime,
    arr: LocalDateTime,
): String {
    val tz = TimeZone.currentSystemDefault()
    val depInstant = dep.toInstant(tz)
    val arrInstant = arr.toInstant(tz)
    val totalMinutes = ((arrInstant - depInstant).inWholeMinutes).coerceAtLeast(0)
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return "$hours hours $minutes mins"
}

/**
 * Rich ticket card UI for the given [model].
 * Extracted helpers format dates and durations consistently.
 */
@Composable
fun TicketUI(
    model: TicketUiModel,
    modifier: Modifier = Modifier,
) {
    val depStr = formatHumanDateTime(model.departureDateTime)
    val arrStr = formatHumanDateTime(model.arrivalDateTime)
    val durationText = formatDuration(model.departureDateTime, model.arrivalDateTime)

    Column(
        modifier =
            modifier
                .width(700.dp)
                .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                .border(
                    width = 2.dp,
                    color = Color.White.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(10.dp),
                )
                .padding(40.dp),
    ) {
        TicketHeader(model.type, model.number, model.status)
        Spacer(modifier = Modifier.height(12.dp))
        HorizontalLine()
        Spacer(modifier = Modifier.height(12.dp))
        RouteRow(
            type = model.type,
            fromAbbr = model.departureAirportAbbr,
            fromName = model.departureAirport,
            toAbbr = model.arrivalAirportAbbr,
            toName = model.arrivalAirport,
        )
        Spacer(modifier = Modifier.height(12.dp))
        HorizontalLine()
        Spacer(modifier = Modifier.height(12.dp))
        TimeSection("Departure", depStr)
        Spacer(modifier = Modifier.height(20.dp))
        TimeSection("Arrival", arrStr)
        Spacer(modifier = Modifier.height(12.dp))
        HorizontalLine()
        Spacer(modifier = Modifier.height(12.dp))
        DurationRow(durationText)
    }
}

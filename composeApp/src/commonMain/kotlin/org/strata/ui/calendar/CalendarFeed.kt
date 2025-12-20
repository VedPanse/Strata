/*
 * Strata - Kotlin Multiplatform client.
 * Copyright (c) 2025.
 */
package org.strata.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.strata.auth.CalendarEvent

@Composable
fun CalendarFeed(
    isLoading: Boolean,
    error: String?,
    events: List<CalendarEvent>,
) {
    fun fmtTime(dt: LocalDateTime): String {
        var h = dt.hour % 12
        if (h == 0) h = 12
        val m = dt.minute
        val minuteStr = if (m < 10) "0$m" else "$m"
        val ampm = if (dt.hour < 12) "AM" else "PM"
        return "$h:$minuteStr $ampm"
    }

    when {
        isLoading -> {
            Text("Loading Calendar…", color = MaterialTheme.colorScheme.secondary)
        }
        error != null -> {
            Text(error!!, color = Color(0xFFFFA000))
        }
        events.isEmpty() -> {
            Text("No events today.", color = MaterialTheme.colorScheme.secondary)
        }
        else -> {
            AppleCalendarDayView(events)
        }
    }
}

@Composable
fun AppleCalendarDayView(events: List<CalendarEvent>) {
    // Apple-like Day View: All‑day row + scrollable 24h grid with positioned event blocks
    val leftGutter = 56.dp
    val containerWidth = 700.dp
    val hourHeight = 60.dp // 60.dp ~= 60 minutes; 1.dp/minute scale
    val dpPerMinute = 1.dp
    val gridCorner = 16.dp
    val gridBorder = Color.White.copy(alpha = 0.08f)
    val gridBg = Color(0xFF141618)
    val gridLine = Color.White.copy(alpha = 0.06f)
    val hourTextColor = MaterialTheme.colorScheme.secondary
    val eventBg = Color(0xFF0A84FF).copy(alpha = 0.18f) // iOS blue tinted
    val eventBorder = Color(0xFF0A84FF).copy(alpha = 0.7f)
    val eventText = Color(0xFFDAE9FF)
    val allDayBg = Color(0xFF1A1C1E)

    fun isAllDay(e: CalendarEvent): Boolean {
        return (e.start.hour == 0 && e.start.minute == 0 && e.end.hour == 23 && e.end.minute >= 59) ||
            ((e.end.hour * 60 + e.end.minute) - (e.start.hour * 60 + e.start.minute) >= 23 * 60)
    }

    val allDayEvents = events.filter { isAllDay(it) }
    val timedEvents = events.filter { !isAllDay(it) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // All‑day section
        if (allDayEvents.isNotEmpty()) {
            Column(
                modifier =
                    Modifier
                        .width(containerWidth)
                        .background(allDayBg, RoundedCornerShape(gridCorner))
                        .border(1.dp, gridBorder, RoundedCornerShape(gridCorner))
                        .padding(12.dp),
            ) {
                Text("All‑day", color = hourTextColor, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                allDayEvents.forEach { e ->
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(bottom = 6.dp)
                                .background(eventBg, RoundedCornerShape(8.dp))
                                .border(1.dp, eventBorder.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .width(6.dp)
                                    .height(12.dp)
                                    .background(eventBorder, RoundedCornerShape(3.dp)),
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(e.title, color = eventText, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            if (!e.location.isNullOrBlank()) {
                                Text(e.location ?: "", color = hourTextColor, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }

        // Timed grid
        val totalHeight = hourHeight * 24
        val innerScroll = rememberScrollState()
        Box(
            modifier =
                Modifier
                    .width(containerWidth)
                    .heightIn(min = 360.dp)
                    .background(Color.Black, RoundedCornerShape(gridCorner))
                    .border(1.dp, gridBorder, RoundedCornerShape(gridCorner))
                    .padding(top = 8.dp, bottom = 8.dp),
        ) {
            // Left gutter + right grid
            Row(Modifier.fillMaxWidth()) {
                // Left hour labels
                Column(modifier = Modifier.width(leftGutter)) {
                    repeat(24) { h ->
                        Box(
                            modifier =
                                Modifier
                                    .height(hourHeight)
                                    .fillMaxWidth(),
                        ) {
                            val ampm =
                                if (h == 0) {
                                    "AM"
                                } else if (h < 12) {
                                    "AM"
                                } else {
                                    "PM"
                                }
                            val hour12 =
                                when {
                                    h == 0 -> 12
                                    h <= 12 -> h
                                    else -> h - 12
                                }
                            Text(
                                text = "$hour12 $ampm",
                                color = hourTextColor,
                                fontSize = 12.sp,
                                modifier = Modifier.align(Alignment.TopEnd).padding(end = 8.dp),
                            )
                        }
                    }
                }

                // Right grid (scrollable content with events)
                BoxWithConstraints(
                    modifier =
                        Modifier
                            .weight(1f)
                            .heightIn(min = 360.dp),
                ) {
                    val maxW = this.maxWidth
                    val contentPadding = 6.dp
                    // Grid background with hour lines
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(totalHeight)
                                .verticalScroll(innerScroll),
                    ) {
                        repeat(24) { _ ->
                            Box(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .height(hourHeight),
                            ) {
                                // Hour top line
                                Box(
                                    modifier =
                                        Modifier
                                            .align(Alignment.TopStart)
                                            .fillMaxWidth()
                                            .height(1.dp)
                                            .background(gridLine),
                                )
                                // Half-hour line
                                Box(
                                    modifier =
                                        Modifier
                                            .align(Alignment.TopStart)
                                            .padding(top = hourHeight / 2)
                                            .fillMaxWidth()
                                            .height(1.dp)
                                            .background(gridLine.copy(alpha = 0.04f)),
                                )
                            }
                        }
                    }

                    // Events layer (absolute positioning within same scroll context)
                    val positioned = remember(timedEvents) { computePositionedEvents(timedEvents) }
                    val laneSpacing = 4.dp
                    val paneWidth = maxW - contentPadding * 2

                    // Foreground container to share the same scroll
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(totalHeight)
                                .verticalScroll(innerScroll),
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .height(totalHeight)
                                    .padding(horizontal = contentPadding),
                        ) {
                            // Current time line
                            val now =
                                Clock.System.now()
                                    .toLocalDateTime(TimeZone.currentSystemDefault())
                            val nowMin = now.hour * 60 + now.minute
                            if (nowMin in 0..(24 * 60)) {
                                val y = (nowMin).dp
                                Box(
                                    modifier =
                                        Modifier
                                            .offset(y = y)
                                            .fillMaxWidth()
                                            .height(1.dp)
                                            .background(Color(0xFFFF453A)),
                                )
                            }

                            // Event blocks
                            positioned.forEach { p ->
                                val laneCount = p.laneCount.coerceAtLeast(1)
                                val eventWidth = (paneWidth - laneSpacing * (laneCount - 1)) / laneCount
                                val x = (eventWidth + laneSpacing) * p.lane.toFloat()
                                val s = p.startMin.coerceIn(0, 24 * 60)
                                val e = p.endMin.coerceIn(s + 1, 24 * 60)
                                val durationMinutes = e - s
                                val startDp = s.dp
                                val durationDp = durationMinutes.dp
                                val isCompact = durationDp < 48.dp
                                val isUltraCompact = durationDp < 28.dp
                                val horizontalPadding = if (isCompact) 6.dp else 8.dp
                                val verticalPadding =
                                    when {
                                        durationDp < 16.dp -> 1.dp
                                        durationDp < 36.dp -> 3.dp
                                        durationDp < 72.dp -> 6.dp
                                        else -> 8.dp
                                    }
                                val rounded = RoundedCornerShape(10.dp)
                                val timeLabel = "${format12(p.event.start)} – ${format12(p.event.end)}"
                                val location = p.event.location
                                val showLocation = !location.isNullOrBlank() && !isCompact

                                Box(
                                    modifier =
                                        Modifier
                                            .offset(x = x, y = startDp)
                                            .width(eventWidth)
                                            .height(durationDp)
                                            .background(eventBg, rounded)
                                            .border(1.dp, eventBorder, rounded)
                                            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
                                ) {
                                    if (isUltraCompact) {
                                        Text(
                                            text = buildCompactLabel(p.event.title, timeLabel),
                                            color = eventText,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 11.sp,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            lineHeight = 13.sp,
                                        )
                                    } else {
                                        Column(
                                            verticalArrangement = Arrangement.spacedBy(if (isCompact) 2.dp else 4.dp),
                                        ) {
                                            Text(
                                                text = p.event.title,
                                                color = eventText,
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = if (isCompact) 13.sp else 14.sp,
                                                maxLines = if (isCompact) 1 else 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                text = timeLabel,
                                                color = hourTextColor,
                                                fontSize = if (isCompact) 10.sp else 11.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Clip,
                                            )
                                            if (showLocation) {
                                                Text(
                                                    text = location!!,
                                                    color = hourTextColor,
                                                    fontSize = 11.sp,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class PositionedEvent(
    val event: CalendarEvent,
    val startMin: Int,
    val endMin: Int,
    val lane: Int,
    val laneCount: Int,
)

private fun computePositionedEvents(events: List<CalendarEvent>): List<PositionedEvent> {
    // Build clusters of overlapping events (transitive overlap), then assign lanes per cluster
    data class Timed(val e: CalendarEvent, val s: Int, val t: Int)

    fun toMin(dt: LocalDateTime): Int = dt.hour * 60 + dt.minute
    val timed =
        events.map {
            val s = toMin(it.start).coerceIn(0, 24 * 60)
            var t = toMin(it.end).coerceIn(0, 24 * 60)
            // If an event ends after midnight (e.g., 11pm–12am next day), end minutes wrap to 0.
            // In that case, render it to the end of the day (1440 minutes) so it isn't cut off.
            if (t <= s) t = 24 * 60
            Timed(it, s, t)
        }
            .sortedBy { it.s }

    val result = mutableListOf<PositionedEvent>()
    var i = 0
    while (i < timed.size) {
        // Start a new cluster
        val cluster = mutableListOf<Timed>()
        var clusterEnd = timed[i].t
        cluster += timed[i]
        var j = i + 1
        while (j < timed.size) {
            val next = timed[j]
            if (next.s < clusterEnd) {
                cluster += next
                if (next.t > clusterEnd) clusterEnd = next.t
                j++
            } else {
                break
            }
        }
        // Assign lanes within cluster
        val active = mutableListOf<Pair<Timed, Int>>() // pair of event and its lane
        var maxLaneUsed = -1
        cluster.forEach { ev ->
            // drop non-overlapping from active
            active.removeAll { it.first.t <= ev.s }
            // find smallest available lane
            val used = active.map { it.second }.toMutableSet()
            var lane = 0
            while (used.contains(lane)) lane++
            if (lane > maxLaneUsed) maxLaneUsed = lane
            active.add(ev to lane)
        }
        val laneCount = (maxLaneUsed + 1).coerceAtLeast(1)
        // Emit for cluster
        // Recompute lanes again to record per item
        active.clear()
        cluster.forEach { ev ->
            active.removeAll { it.first.t <= ev.s }
            val used = active.map { it.second }.toMutableSet()
            var lane = 0
            while (used.contains(lane)) lane++
            active.add(ev to lane)
            result += PositionedEvent(ev.e, ev.s, ev.t, lane, laneCount)
        }
        i = j
    }
    return result
}

private fun format12(dt: LocalDateTime): String {
    var h = dt.hour % 12
    if (h == 0) h = 12
    val m = dt.minute
    val minuteStr = if (m < 10) "0$m" else "$m"
    val ampm = if (dt.hour < 12) "AM" else "PM"
    return "$h:$minuteStr $ampm"
}

private fun buildCompactLabel(
    title: String,
    timeLabel: String,
): String {
    val trimmedTitle = title.trim().ifEmpty { "Event" }
    return "$trimmedTitle • $timeLabel"
}

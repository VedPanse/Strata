/*
 * Strata - Kotlin Multiplatform client.
 * Copyright (c) 2025.
 */
package org.strata.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AirplanemodeActive
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.painterResource
import org.strata.ai.LlmHealth
import org.strata.ai.RefreshSignals
import org.strata.ai.SummaryAi
import org.strata.ai.macro.MailPreviewOverlay
import org.strata.auth.CalendarApi
import org.strata.auth.CalendarEvent
import org.strata.auth.GmailApi
import org.strata.auth.GmailMail
import org.strata.auth.JwtTools
import org.strata.auth.SessionStorage
import org.strata.auth.TaskItem
import org.strata.auth.TasksApi
import org.strata.perception.ScreenPerception
import org.strata.persistence.PlanStore
import org.strata.reminder.ReminderDueScheduler
import org.strata.ui.calendar.CalendarFeed
import org.strata.ui.mail.MailFeed
import org.strata.ui.reminder.ReminderDueNotifications
import org.strata.ui.reminder.ReminderUI
import org.strata.ui.reminder.Reminders
import org.strata.ui.reminder.TaskConfirmationOverlay
import org.strata.ui.ticket.TicketStatus
import org.strata.ui.ticket.TicketTypes
import org.strata.ui.ticket.TicketUI
import org.strata.ui.ticket.TicketUiModel
import strata.composeapp.generated.resources.HomescreenWallpaper
import strata.composeapp.generated.resources.Res
import strata.composeapp.generated.resources.StrataNoText
import strata.composeapp.generated.resources.UserProfile
import kotlin.collections.buildList
import kotlin.coroutines.cancellation.CancellationException

object HomeDimens {
    val LeftPaneWeight = 0.05f
    val RightPaneWeight = 0.85f
    val ContentEndPadding = 30.dp
    val HeroHeight = 250.dp
    val HeroCorner = 20.dp
    val TopSpacing = 20.dp
    val TabsSpacing = 1.dp
    val TabsBelowSpacing = 16.dp
    val TabCorner = 25.dp
    val ActiveBorderWidth = 1.5.dp
    val SummaryTitleSize = 25.sp
    val BodyTextSize = 14.sp
}

/**
 * Main application screen showing a dashboard with Mail, Calendar and Reminders.
 *
 * Layout:
 * - Left navigation pane with Home, Account Settings and Logout
 * - Right content pane with hero image, tabs and per‑tab content
 *
 * The screen also prefetches Gmail, Calendar and Tasks using the persisted session
 * and renders simple loading/error/empty states.
 */
class Homescreen : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow

        var selected by remember { mutableStateOf(Section.Feed) }
        var mailRefreshKey by remember { mutableStateOf(0) }
        var calendarRefreshKey by remember { mutableStateOf(0) }
        var tasksRefreshKey by remember { mutableStateOf(0) }
        var summaryRefreshPending by remember { mutableStateOf(true) }

        // Prefetch Gmail when Homescreen renders
        val session = SessionStorage.read()
        val scope = rememberCoroutineScope()
        var mailIsLoading by remember { mutableStateOf(true) }
        var mailError by remember { mutableStateOf<String?>(null) }
        var mailItems by remember { mutableStateOf<List<GmailMail>>(emptyList()) }

        // Prefetch Calendar when Homescreen renders (today for Calendar tab)
        var calIsLoading by remember { mutableStateOf(true) }
        var calError by remember { mutableStateOf<String?>(null) }
        var calEvents by remember { mutableStateOf<List<CalendarEvent>>(emptyList()) }
        // Prefetch upcoming reservation events from Gmail (for Tickets)
        var calFromGmailIsLoading by remember { mutableStateOf(true) }
        var calFromGmailError by remember { mutableStateOf<String?>(null) }
        var calFromGmailEvents by remember { mutableStateOf<List<CalendarEvent>>(emptyList()) }

        // Prefetch Tasks when Homescreen renders
        var tasksIsLoading by remember { mutableStateOf(true) }
        var tasksError by remember { mutableStateOf<String?>(null) }
        var tasksItems by remember { mutableStateOf<List<TaskItem>>(emptyList()) }

        fun applyRefreshSignals(signals: RefreshSignals) {
            var anyService = false
            if (signals.refreshMail) {
                anyService = true
                mailIsLoading = true
                calFromGmailIsLoading = true
                mailRefreshKey++
            }
            if (signals.refreshCalendar) {
                anyService = true
                calIsLoading = true
                calendarRefreshKey++
            }
            if (signals.refreshTasks) {
                anyService = true
                tasksIsLoading = true
                tasksRefreshKey++
            }
            if (signals.refreshSummary || anyService) {
                summaryRefreshPending = true
            }
        }

        fun triggerFullRefresh() {
            applyRefreshSignals(
                RefreshSignals(
                    refreshMail = true,
                    refreshCalendar = true,
                    refreshTasks = true,
                    refreshSummary = true,
                ),
            )
        }

        fun handleTaskToggle(task: TaskItem) {
            val updated = task.copy(completed = !task.completed)
            tasksItems = tasksItems.map { if (it.id == task.id) updated else it }
            summaryRefreshPending = true
            val token = session?.accessToken
            if (token.isNullOrBlank()) {
                return
            }
            scope.launch {
                val result =
                    TasksApi.pushTaskChanges(
                        accessToken = token,
                        taskId = task.id,
                        completed = updated.completed,
                    )
                result.onFailure {
                    applyRefreshSignals(RefreshSignals(refreshTasks = true))
                }
            }
        }

        LaunchedEffect(session?.accessToken, mailRefreshKey) {
            mailIsLoading = true
            mailError = null
            mailItems = emptyList()
            val token = session?.accessToken
            if (token.isNullOrBlank()) {
                mailError = "Please sign in to view your Gmail."
                mailIsLoading = false
            } else {
                val res = GmailApi.fetchTop5Unread(token)
                res.onSuccess {
                    mailItems = it
                }.onFailure {
                    mailError = it.message ?: "Failed to load mail"
                }
                mailIsLoading = false
            }
        }
        // Prefetch Calendar (today for Calendar tab)
        LaunchedEffect(session?.accessToken, calendarRefreshKey) {
            calIsLoading = true
            calError = null
            calEvents = emptyList()
            val token = session?.accessToken
            if (token.isNullOrBlank()) {
                calError = "Please sign in to view your Calendar."
                calIsLoading = false
            } else {
                val res = CalendarApi.fetchToday(token)
                res.onSuccess {
                    calEvents = it
                }.onFailure {
                    calError = it.message ?: "Failed to load calendar events"
                }
                calIsLoading = false
            }
        }
        // Prefetch upcoming reservation events from Gmail
        LaunchedEffect(session?.accessToken, mailRefreshKey) {
            calFromGmailIsLoading = true
            calFromGmailError = null
            calFromGmailEvents = emptyList()
            val token = session?.accessToken
            if (token.isNullOrBlank()) {
                calFromGmailError = "Please sign in to view your upcoming reservations."
                calFromGmailIsLoading = false
            } else {
                val res = CalendarApi.fetchUpcomingFromGmail(token, daysAhead = 365)
                res.onSuccess { calFromGmailEvents = it }
                    .onFailure { calFromGmailError = it.message ?: "Failed to load upcoming reservations" }
                calFromGmailIsLoading = false
            }
        }
        // Prefetch Tasks
        LaunchedEffect(session?.accessToken, tasksRefreshKey) {
            tasksIsLoading = true
            tasksError = null
            tasksItems = emptyList()
            val token = session?.accessToken
            if (token.isNullOrBlank()) {
                tasksError = "Please sign in to view your Tasks."
                tasksIsLoading = false
            } else {
                val res = TasksApi.fetchTopTasks(token)
                res.onSuccess {
                    tasksItems = it
                }.onFailure {
                    tasksError = it.message ?: "Failed to load tasks"
                }
                tasksIsLoading = false
            }
        }
        LaunchedEffect(tasksItems) {
            ReminderDueScheduler.updateTasks(tasksItems)
        }
        // Summary AI state
        var summaryIsLoading by remember { mutableStateOf(false) }
        var summaryError by remember { mutableStateOf<String?>(null) }
        var summaryText by remember { mutableStateOf<String?>(null) }
        // Tickets state
        var ticketsIsLoading by remember { mutableStateOf(false) }
        var ticketsError by remember { mutableStateOf<String?>(null) }
        var ticketsItems by remember { mutableStateOf<List<TicketUiModel>>(emptyList()) }

        // Trigger AI summary when data is available or user pulls refresh
        LaunchedEffect(
            session?.accessToken,
            mailIsLoading, calIsLoading, tasksIsLoading,
            mailError, calError, tasksError,
            mailItems, calEvents, tasksItems,
            summaryRefreshPending,
        ) {
            val canSummarize =
                !session?.accessToken.isNullOrBlank() &&
                    !mailIsLoading && !calIsLoading && !tasksIsLoading &&
                    mailError == null && calError == null && tasksError == null
            if (summaryRefreshPending && canSummarize) {
                summaryRefreshPending = false
                summaryIsLoading = true
                summaryError = null
                summaryText = null
                try {
                    val res =
                        SummaryAi.summarizeDay(
                            unreadMails = mailItems,
                            todayEvents = calEvents,
                            tasks = tasksItems,
                        )
                    res.fold(
                        onSuccess = { summaryText = it },
                        onFailure = { error ->
                            if (error is CancellationException) throw error
                            summaryError = error.message ?: "Failed to generate summary"
                        },
                    )
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } finally {
                    summaryIsLoading = false
                }
            }
        }
        // Trigger Tickets extraction when Gmail and Calendar (fromGmail) are ready
        androidx.compose.runtime.LaunchedEffect(
            session?.accessToken,
            mailIsLoading,
            calFromGmailIsLoading,
            mailError,
            calFromGmailError,
            mailItems,
            calFromGmailEvents,
        ) {
            val canExtract =
                !session?.accessToken.isNullOrBlank() &&
                    !mailIsLoading && !calFromGmailIsLoading &&
                    mailError == null && calFromGmailError == null
            if (canExtract) {
                ticketsIsLoading = true
                ticketsError = null
                ticketsItems = emptyList()
                val res =
                    org.strata.ai.TicketsAi.extractTickets(
                        unreadMails = mailItems,
                        todayEvents = calFromGmailEvents,
                    )
                res.onSuccess { list ->
                    println("[DEBUG_LOG][Homescreen] ExtractedTicket count from AI: ${list.size}")

                    fun parseLdt(s: String): LocalDateTime {
                        return runCatching { LocalDateTime.parse(s) }.getOrElse {
                            val base = s.take(16) // YYYY-MM-DDThh:mm
                            LocalDateTime.parse(base)
                        }
                    }

                    fun inferAbbrFromName(name: String): String {
                        // Prefer explicit parentheses like "London Heathrow (LHR)"
                        val paren =
                            Regex("\\(([A-Z]{3})\\)", RegexOption.IGNORE_CASE)
                                .find(name)
                                ?.groupValues
                                ?.getOrNull(1)
                        if (!paren.isNullOrBlank()) return paren.uppercase()
                        // Gemini returns abbreviations as the last word in the location string.
                        val lastWord = name.trim().split(Regex("\\s+")).lastOrNull().orEmpty()
                        val cleanedLast = lastWord.trim { !it.isLetter() }
                        if (cleanedLast.length == 3 && cleanedLast.all { it.isLetter() }) {
                            return cleanedLast.uppercase()
                        }
                        // Otherwise, pick the last standalone 3-letter token
                        val token =
                            Regex("\\b[A-Z]{3}\\b", RegexOption.IGNORE_CASE)
                                .findAll(name)
                                .lastOrNull()
                                ?.value
                        return token?.uppercase() ?: ""
                    }

                    fun cleanAbbr(raw: String): String {
                        val trimmed = raw.trim()
                        if (trimmed.isBlank()) return ""
                        val token =
                            Regex("\\b[A-Z]{3}\\b", RegexOption.IGNORE_CASE)
                                .findAll(trimmed)
                                .lastOrNull()
                                ?.value
                        if (!token.isNullOrBlank()) return token.uppercase()
                        val lettersOnly = trimmed.filter { it.isLetter() }
                        return if (lettersOnly.length == 3) lettersOnly.uppercase() else ""
                    }

                    val mapped =
                        list.map { t ->
                            val type =
                                when (t.type.uppercase()) {
                                    "FLIGHT" -> TicketTypes.FLIGHT
                                    "TRAIN" -> TicketTypes.TRAIN
                                    "BUS" -> TicketTypes.BUS
                                    else -> TicketTypes.FLIGHT
                                }
                            val status =
                                when (t.status.uppercase()) {
                                    "DELAYED" -> TicketStatus.DELAYED
                                    "CANCELLED" -> TicketStatus.CANCELLED
                                    else -> TicketStatus.ON_TIME
                                }
                            val depNameInfer = inferAbbrFromName(t.departureName)
                            val depAbbrRaw = cleanAbbr(t.departureAbbr)
                            val depAbbr =
                                when {
                                    depAbbrRaw.isNotBlank() -> depAbbrRaw
                                    depNameInfer.isNotBlank() -> depNameInfer
                                    else -> ""
                                }
                            val arrNameInfer = inferAbbrFromName(t.arrivalName)
                            val arrAbbrRaw = cleanAbbr(t.arrivalAbbr)
                            val arrAbbr =
                                when {
                                    arrAbbrRaw.isNotBlank() -> arrAbbrRaw
                                    arrNameInfer.isNotBlank() -> arrNameInfer
                                    else -> ""
                                }
                            println(
                                "[DEBUG_LOG][Homescreen] Mapping ticket: type=${t.type}, number=${t.number}, " +
                                    "dep='${t.departureName}' abbr='${t.departureAbbr}' -> " +
                                    "inferredName='$depNameInfer', final='$depAbbr'; " +
                                    "arr='${t.arrivalName}' abbr='${t.arrivalAbbr}' -> " +
                                    "inferredName='$arrNameInfer', final='$arrAbbr'",
                            )
                            TicketUiModel(
                                type = type,
                                number = t.number,
                                status = status,
                                departureAirport = t.departureName,
                                departureAirportAbbr = depAbbr,
                                arrivalAirport = t.arrivalName,
                                arrivalAirportAbbr = arrAbbr,
                                departureDateTime = parseLdt(t.departureIso),
                                arrivalDateTime = parseLdt(t.arrivalIso),
                            )
                        }
                    println("[DEBUG_LOG][Homescreen] TicketUiModel count ready for UI: ${mapped.size}")
                    mapped.forEach { m ->
                        println(
                            "[DEBUG_LOG][Homescreen] TicketUiModel -> ${m.type} ${m.number} ${m.departureAirportAbbr}->${m.arrivalAirportAbbr} | ${m.departureAirport} -> ${m.arrivalAirport}",
                        )
                    }
                    ticketsItems = mapped
                }.onFailure { e ->
                    val fallback = buildTicketsFromCalendar(calFromGmailEvents)
                    if (fallback.isNotEmpty()) {
                        ticketsItems = fallback
                        ticketsError = null
                    } else {
                        ticketsError = e.message ?: "Failed to extract tickets"
                    }
                }
                ticketsIsLoading = false
            }
        }
        // Chat messages state (user ↔ assistant)
        val messages = remember { mutableStateListOf<ChatMessage>() }
        LaunchedEffect(Unit) {
            val pending = PlanStore.getPending()
            if (pending != null && messages.none { it.role == "assistant" && it.text == pending.question }) {
                messages.add(ChatMessage("assistant", pending.question))
            }
        }
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val isCompact = maxWidth < 900.dp
            val isNarrow = maxWidth < 600.dp
            val contentPadding = if (isCompact) 16.dp else HomeDimens.ContentEndPadding
            val heroHeight = if (isCompact) 180.dp else HomeDimens.HeroHeight
            val startPadding = if (isCompact) contentPadding else 0.dp

            val onHome = { navigator.replaceAll(Homescreen()) }
            val onAccountSettings = { navigator.push(SettingsScreen()) }
            val onLogout = {
                // Clear persisted session and return to onboarding
                SessionStorage.clear()
                navigator.replaceAll(OnboardingScreen())
            }

            Box(modifier = Modifier.fillMaxSize()) {
                if (!isCompact) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        // Left spacer/pane (reserved for future navigation)
                        LeftNavPane(
                            modifier =
                                Modifier
                                    .fillMaxHeight()
                                    .weight(HomeDimens.LeftPaneWeight)
                                    .padding(horizontal = 5.dp),
                            onHome = onHome,
                            onAccountSettings = onAccountSettings,
                            onLogout = onLogout,
                        )

                        // Main content
                        Column(
                            modifier =
                                Modifier
                                    .weight(HomeDimens.RightPaneWeight)
                                    .fillMaxHeight()
                                    .padding(end = contentPadding)
                                    .verticalScroll(rememberScrollState()),
                        ) {
                            HomescreenContent(
                                isCompact = isCompact,
                                isNarrow = isNarrow,
                                heroHeight = heroHeight,
                                selected = selected,
                                onSelect = { selected = it },
                                mailIsLoading = mailIsLoading,
                                mailError = mailError,
                                mailItems = mailItems,
                                calIsLoading = calIsLoading,
                                calError = calError,
                                calEvents = calEvents,
                                tasksIsLoading = tasksIsLoading,
                                tasksError = tasksError,
                                tasksItems = tasksItems,
                                ticketsIsLoading = ticketsIsLoading,
                                ticketsError = ticketsError,
                                ticketsItems = ticketsItems,
                                handleTaskToggle = { handleTaskToggle(it) },
                                session = session,
                                summaryText = summaryText,
                                summaryIsLoading = summaryIsLoading,
                                summaryError = summaryError,
                                triggerFullRefresh = { triggerFullRefresh() },
                                messages = messages,
                                onHome = onHome,
                                onAccountSettings = onAccountSettings,
                                onLogout = onLogout,
                            )
                        }
                    }
                } else {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(start = startPadding, end = contentPadding),
                    ) {
                        HomescreenContent(
                            isCompact = isCompact,
                            isNarrow = isNarrow,
                            heroHeight = heroHeight,
                            selected = selected,
                            onSelect = { selected = it },
                            mailIsLoading = mailIsLoading,
                            mailError = mailError,
                            mailItems = mailItems,
                            calIsLoading = calIsLoading,
                            calError = calError,
                            calEvents = calEvents,
                            tasksIsLoading = tasksIsLoading,
                            tasksError = tasksError,
                            tasksItems = tasksItems,
                            ticketsIsLoading = ticketsIsLoading,
                            ticketsError = ticketsError,
                            ticketsItems = ticketsItems,
                            handleTaskToggle = { handleTaskToggle(it) },
                            session = session,
                            summaryText = summaryText,
                            summaryIsLoading = summaryIsLoading,
                            summaryError = summaryError,
                            triggerFullRefresh = { triggerFullRefresh() },
                            messages = messages,
                            onHome = onHome,
                            onAccountSettings = onAccountSettings,
                            onLogout = onLogout,
                        )
                    }
                }
            }

            MailPreviewOverlay(modifier = Modifier.fillMaxSize().zIndex(10_000f))

            TaskConfirmationOverlay(
                modifier = Modifier.fillMaxSize().zIndex(9_999.5f),
                onNavigateToReminders = { selected = Section.Reminders },
            )

            ReminderDueNotifications(
                modifier =
                    Modifier
                        .align(if (isCompact) Alignment.BottomCenter else Alignment.BottomStart)
                        .padding(
                            start = if (isCompact) 0.dp else contentPadding,
                            bottom = if (isCompact) 96.dp else 24.dp,
                        )
                        .zIndex(9_999.3f),
                onNavigateToReminders = { selected = Section.Reminders },
            )

            // Absolute bottom-right PromptInput overlay
            Box(
                modifier =
                    Modifier
                        .align(if (isCompact) Alignment.BottomCenter else Alignment.BottomEnd)
                        .padding(
                            start = if (isCompact) contentPadding else 0.dp,
                            end = contentPadding,
                            bottom = if (isCompact) 16.dp else 20.dp,
                        )
                        .zIndex(9_999f),
            ) {
                val scope = rememberCoroutineScope()
                var overlayActive by remember { mutableStateOf(ScreenPerception.isOverlayActive()) }

                val buttonSpacing = 10.dp
                val overlayArrangement = Arrangement.spacedBy(buttonSpacing)
                val overlayLayoutModifier = if (isCompact) Modifier.fillMaxWidth() else Modifier

                if (isCompact) {
                    Column(
                        modifier = overlayLayoutModifier,
                        verticalArrangement = overlayArrangement,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        StrataButton(
                            text = if (overlayActive) "Stop screen" else "Record screen",
                            onClick = {
                                if (overlayActive) {
                                    ScreenPerception.stopOverlay()
                                    ScreenPerception.stopStream()
                                    overlayActive = false
                                } else {
                                    ScreenPerception.startOverlay()
                                    overlayActive = true
                                    ScreenPerception.startStream()
                                    scope.launch { ScreenPerception.record() }
                                }
                            },
                        )

                        PromptInput(
                            fullWidth = true,
                            contentAlignment = Alignment.Center,
                            chatHistory = messages.map { it.role to it.text },
                            onAgentReply = { user, assistantReplies, refreshSignals ->
                                messages.add(ChatMessage("user", user))
                                assistantReplies.forEach { reply ->
                                    if (reply.isNotBlank()) {
                                        messages.add(ChatMessage("assistant", reply))
                                    }
                                }
                                applyRefreshSignals(refreshSignals)
                            },
                        )
                    }
                } else {
                    Row(
                        horizontalArrangement = overlayArrangement,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = overlayLayoutModifier,
                    ) {
                        StrataButton(
                            text = if (overlayActive) "Stop screen" else "Record screen",
                            onClick = {
                                if (overlayActive) {
                                    ScreenPerception.stopOverlay()
                                    ScreenPerception.stopStream()
                                    overlayActive = false
                                } else {
                                    ScreenPerception.startOverlay()
                                    overlayActive = true
                                    ScreenPerception.startStream()
                                    scope.launch { ScreenPerception.record() }
                                }
                            },
                        )

                        PromptInput(
                            fullWidth = false,
                            contentAlignment = Alignment.CenterEnd,
                            chatHistory = messages.map { it.role to it.text },
                            onAgentReply = { user, assistantReplies, refreshSignals ->
                                messages.add(ChatMessage("user", user))
                                assistantReplies.forEach { reply ->
                                    if (reply.isNotBlank()) {
                                        messages.add(ChatMessage("assistant", reply))
                                    }
                                }
                                applyRefreshSignals(refreshSignals)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HomescreenContent(
    isCompact: Boolean,
    isNarrow: Boolean,
    heroHeight: Dp,
    selected: Section,
    onSelect: (Section) -> Unit,
    mailIsLoading: Boolean,
    mailError: String?,
    mailItems: List<GmailMail>,
    calIsLoading: Boolean,
    calError: String?,
    calEvents: List<CalendarEvent>,
    tasksIsLoading: Boolean,
    tasksError: String?,
    tasksItems: List<TaskItem>,
    ticketsIsLoading: Boolean,
    ticketsError: String?,
    ticketsItems: List<TicketUiModel>,
    handleTaskToggle: (TaskItem) -> Unit,
    session: org.strata.auth.AuthSession?,
    summaryText: String?,
    summaryIsLoading: Boolean,
    summaryError: String?,
    triggerFullRefresh: () -> Unit,
    messages: List<ChatMessage>,
    onHome: () -> Unit,
    onAccountSettings: () -> Unit,
    onLogout: () -> Unit,
) {
    Spacer(modifier = Modifier.height(HomeDimens.TopSpacing))

    if (isCompact) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    painter = painterResource(Res.drawable.StrataNoText),
                    contentDescription = null,
                    modifier =
                        Modifier
                            .width(44.dp)
                            .absoluteOffset(y = (-6).dp),
                )
                Text("Strata", fontWeight = FontWeight.Bold, fontSize = 22.sp)
                Spacer(modifier = Modifier.weight(1f))
                UserProfile()
            }

            LlmApiHealth(modifier = Modifier.fillMaxWidth())

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NavIcon(
                    icon = Icons.Filled.Home,
                    accent = Color.White,
                    highlight = true,
                ) { onHome() }
                NavIcon(
                    icon = Icons.Filled.Settings,
                    accent = Color.White.copy(alpha = 0.8f),
                ) { onAccountSettings() }
                NavIcon(
                    icon = Icons.AutoMirrored.Filled.Logout,
                    accent = Color(0xFFFF5C5C),
                ) { onLogout() }
            }
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth().padding(end = 20.dp),
        ) {
            Image(
                painter = painterResource(Res.drawable.StrataNoText),
                contentDescription = null,
                modifier =
                    Modifier.width(50.dp)
                        .absoluteOffset(y = (-7).dp),
            )
            Text("Strata", fontWeight = FontWeight.Bold, fontSize = 25.sp)
            LlmApiHealth(
                modifier =
                    Modifier
                        .padding(start = 16.dp)
                        .width(220.dp),
            )
            Spacer(modifier = Modifier.weight(1f))
            UserProfile()
        }
    }

    Spacer(modifier = Modifier.height(HomeDimens.TopSpacing))
    HeroImage(height = heroHeight)
    Spacer(modifier = Modifier.height(HomeDimens.TopSpacing))

    if (isCompact) {
        Column(modifier = Modifier.fillMaxWidth()) {
            HomescreenTabs(selected = selected, onSelect = onSelect)
            Spacer(modifier = Modifier.height(HomeDimens.TabsBelowSpacing))
            when (selected) {
                Section.Feed ->
                    Feed(
                        mailState = FeedSourceState(mailIsLoading, mailError, mailItems),
                        calendarState = FeedSourceState(calIsLoading, calError, calEvents),
                        tasksState = FeedSourceState(tasksIsLoading, tasksError, tasksItems),
                        ticketsState = FeedSourceState(ticketsIsLoading, ticketsError, ticketsItems),
                        onTaskToggle = { handleTaskToggle(it) },
                    )
                Section.Mail ->
                    MailFeed(
                        isLoading = mailIsLoading,
                        error = mailError,
                        mails = mailItems,
                    )
                Section.Calendar ->
                    CalendarFeed(
                        isLoading = calIsLoading,
                        error = calError,
                        events = calEvents,
                    )
                Section.Reminders ->
                    Reminders(
                        isLoading = tasksIsLoading,
                        error = tasksError,
                        tasks = tasksItems,
                    )
            }

            Spacer(modifier = Modifier.height(20.dp))

            val isOnline =
                !session?.accessToken.isNullOrBlank() &&
                    !mailIsLoading && !calIsLoading && !tasksIsLoading &&
                    mailError == null && calError == null && tasksError == null

            SummaryPanel(
                summaryText = summaryText,
                isLoading = summaryIsLoading,
                error = summaryError,
                onRefresh = { triggerFullRefresh() },
                isOnline = isOnline,
                messages = messages,
                recommendations = buildPlayfulRecommendations(mailItems, calEvents, tasksItems),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    } else {
        Row(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.weight(0.7f),
            ) {
                HomescreenTabs(selected = selected, onSelect = onSelect)
                Spacer(modifier = Modifier.height(HomeDimens.TabsBelowSpacing))
                when (selected) {
                    Section.Feed ->
                        Feed(
                            mailState = FeedSourceState(mailIsLoading, mailError, mailItems),
                            calendarState = FeedSourceState(calIsLoading, calError, calEvents),
                            tasksState = FeedSourceState(tasksIsLoading, tasksError, tasksItems),
                            ticketsState = FeedSourceState(ticketsIsLoading, ticketsError, ticketsItems),
                            onTaskToggle = { handleTaskToggle(it) },
                        )
                    Section.Mail ->
                        MailFeed(
                            isLoading = mailIsLoading,
                            error = mailError,
                            mails = mailItems,
                        )
                    Section.Calendar ->
                        CalendarFeed(
                            isLoading = calIsLoading,
                            error = calError,
                            events = calEvents,
                        )
                    Section.Reminders ->
                        Reminders(
                            isLoading = tasksIsLoading,
                            error = tasksError,
                            tasks = tasksItems,
                        )
                }
            }
            Column(
                modifier = Modifier.weight(0.3f).fillMaxHeight(),
            ) {
                val isOnline =
                    !session?.accessToken.isNullOrBlank() &&
                        !mailIsLoading && !calIsLoading && !tasksIsLoading &&
                        mailError == null && calError == null && tasksError == null

                SummaryPanel(
                    summaryText = summaryText,
                    isLoading = summaryIsLoading,
                    error = summaryError,
                    onRefresh = { triggerFullRefresh() },
                    isOnline = isOnline,
                    messages = messages,
                    recommendations = buildPlayfulRecommendations(mailItems, calEvents, tasksItems),
                    modifier = Modifier.fillMaxHeight(),
                )
            }
        }
    }

    Spacer(modifier = Modifier.height(if (isNarrow) 96.dp else 80.dp))
}

private enum class Section(val displayName: String) {
    Feed(
        "Feed",
    ),
    Mail("Mail"),
    Calendar("Calendar"),
    Reminders("Reminders"),
}

private data class ChatMessage(val role: String, val text: String)

@Composable
private fun ChatTranscript(
    messages: List<ChatMessage>,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    Column(
        modifier =
            modifier
                .verticalScroll(scroll)
                .padding(horizontal = 6.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (messages.isEmpty()) {
            Text(
                "I’m here to help. Ask me to plan or draft something.",
                color = Color.White.copy(alpha = 0.65f),
                fontSize = 12.sp,
            )
        } else {
            messages.forEach { msg ->
                val isUser = msg.role == "user"
                val baseColor = Color.White
                val textColor = if (isUser) baseColor else baseColor.copy(alpha = 0.7f)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
                ) {
                    Box(
                        modifier =
                            Modifier
                                .widthIn(max = 320.dp),
                    ) {
                        Text(
                            msg.text,
                            color = textColor,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroImage(height: Dp) {
    Image(
        painter = painterResource(Res.drawable.HomescreenWallpaper),
        contentDescription = null,
        modifier =
            Modifier
                .fillMaxWidth()
                .height(height)
                .clip(RoundedCornerShape(HomeDimens.HeroCorner)),
        contentScale = ContentScale.Crop,
    )
}

private fun buildPlayfulRecommendations(
    mails: List<GmailMail>,
    events: List<CalendarEvent>,
    tasks: List<TaskItem>,
): List<String> {
    val suggestions = mutableListOf<String>()
    val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())

    val upcomingEvent =
        events.minByOrNull { it.start }
            ?.takeIf { it.start.date == now.date }

    if (upcomingEvent != null) {
        val time = "%02d:%02d".format(upcomingEvent.start.hour, upcomingEvent.start.minute)
        suggestions += "You have \"${upcomingEvent.title}\" at $time. Want me to prep notes or set a reminder?"
    }

    val billMail =
        mails.firstOrNull { mail ->
            val text = (mail.subject + " " + mail.snippet).lowercase()
            listOf("invoice", "bill", "payment due", "receipt").any { text.contains(it) }
        }
    if (billMail != null) {
        suggestions += "That looks like a bill or receipt. Want me to add a payment reminder?"
    }

    val recruiterMail =
        mails.firstOrNull { mail ->
            val text = (mail.subject + " " + mail.snippet).lowercase()
            listOf("interview", "recruiter", "application", "position").any { text.contains(it) }
        }
    if (recruiterMail != null) {
        suggestions += "Looks like a hiring email. Want me to draft a reply or add a follow-up?"
    }

    val dueToday =
        tasks.firstOrNull { task ->
            val due = task.due ?: return@firstOrNull false
            due.date == now.date
        }
    if (dueToday != null) {
        suggestions += "You have tasks due today. Want me to block a focus slot?"
    }

    if (suggestions.isEmpty()) {
        suggestions += "Want me to plan a chill, productive day for you?"
    }

    return suggestions.take(3)
}

@Composable
private fun LlmApiHealth(modifier: Modifier = Modifier) {
    val status by LlmHealth.status.collectAsState()
    val limit = status.dailyLimit
    val percent =
        if (limit != null && limit > 0) {
            ((status.usedRequests * 100) / limit).coerceIn(0, 100)
        } else {
            null
        }
    val progress = (percent ?: 0) / 100f
    val exhausted = status.exhausted
    val hasError = status.lastError != null
    val statusLabel =
        when {
            exhausted -> "EXHAUSTED"
            hasError -> "ERROR"
            else -> "OK"
        }
    val statusColor =
        when {
            exhausted -> Color(0xFFE57373)
            hasError -> Color(0xFFFFB74D)
            else -> Color(0xFF81C784)
        }
    val percentLabel = percent?.let { "$it%" } ?: "—%"

    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "OpenAI API",
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = percentLabel,
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 11.sp,
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = statusLabel,
                color = statusColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(6.dp)),
            color = statusColor,
            trackColor = Color.White.copy(alpha = 0.15f),
        )
    }
}

@Composable
private fun HomescreenTabs(
    selected: Section,
    onSelect: (Section) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(HomeDimens.TabsSpacing)) {
        Section.entries.forEach { section ->
            val isActive = selected == section
            Button(
                onClick = { onSelect(section) },
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = Color.Transparent,
                        contentColor = if (isActive) Color.White else Color.Gray,
                        disabledContainerColor = Color.Transparent,
                        disabledContentColor = if (isActive) Color.White else Color.Gray,
                    ),
                shape = RoundedCornerShape(HomeDimens.TabCorner),
                modifier =
                    Modifier
                        .pointerHoverIcon(PointerIcon.Hand)
                        .then(
                            if (isActive) {
                                Modifier.border(
                                    HomeDimens.ActiveBorderWidth,
                                    Color.White,
                                    RoundedCornerShape(HomeDimens.TabCorner),
                                )
                            } else {
                                Modifier
                            },
                        ),
            ) {
                Text(section.displayName, fontSize = HomeDimens.BodyTextSize)
            }
        }
    }
}

@Composable
private fun LeftNavPane(
    modifier: Modifier = Modifier,
    onHome: () -> Unit,
    onAccountSettings: () -> Unit,
    onLogout: () -> Unit,
) {
    val cardShape = RoundedCornerShape(28.dp)
    val navTint = Color(0xFFF5F5F7)
    val shellStroke = Color.White.copy(alpha = 0.05f)
    val containerGradient =
        Brush.verticalGradient(
            colors = listOf(Color(0xFF050506), Color(0xFF010101)),
        )
    Box(
        modifier =
            modifier
                .fillMaxHeight()
                .padding(vertical = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier =
                Modifier
                    .widthIn(min = 80.dp, max = 92.dp)
                    .fillMaxHeight()
                    .clip(cardShape)
                    .background(containerGradient)
                    .border(width = 1.dp, color = shellStroke, shape = cardShape)
                    .padding(vertical = 28.dp, horizontal = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                NavIcon(
                    icon = Icons.Filled.Home,
                    accent = navTint,
                    highlight = true,
                ) { onHome() }
                NavIcon(
                    icon = Icons.Filled.Settings,
                    accent = navTint.copy(alpha = 0.85f),
                ) { onAccountSettings() }
            }

            NavIcon(
                icon = Icons.AutoMirrored.Filled.Logout,
                accent = Color(0xFFFF5C5C),
            ) { onLogout() }
        }
    }
}

@Composable
private fun NavIcon(
    icon: ImageVector,
    accent: Color,
    highlight: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier =
            modifier
                .clip(RoundedCornerShape(20.dp))
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(
                    onClick = onClick,
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                )
                .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (highlight) accent.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.06f))
                    .border(
                        width = 1.dp,
                        color = if (highlight) accent.copy(alpha = 0.38f) else Color.White.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(12.dp),
                    ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (highlight) accent else accent.copy(alpha = 0.75f),
            )
        }
    }
}

@Composable
private fun SummaryPanel(
    summaryText: String?,
    isLoading: Boolean,
    error: String?,
    onRefresh: () -> Unit,
    isOnline: Boolean,
    messages: List<ChatMessage>,
    recommendations: List<String>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth(),
    ) {
        Column {
            Text("Today's summary", fontSize = HomeDimens.SummaryTitleSize, fontWeight = FontWeight.SemiBold)
            when {
                isLoading ->
                    Text(
                        "Generating your daily summary…",
                        color = MaterialTheme.colorScheme.secondary,
                        fontSize = HomeDimens.BodyTextSize,
                    )
                error != null -> Text(error, color = Color(0xFFFFA000), fontSize = HomeDimens.BodyTextSize)
                !summaryText.isNullOrBlank() ->
                    Text(
                        summaryText,
                        color = MaterialTheme.colorScheme.secondary,
                        fontSize = HomeDimens.BodyTextSize,
                    )
                else ->
                    Text(
                        "No data to summarize yet.",
                        color = MaterialTheme.colorScheme.secondary,
                        fontSize = HomeDimens.BodyTextSize,
                    )
            }
            Spacer(modifier = Modifier.height(8.dp))
            // Refresh button styled like the "Time not set" chip in Reminders, plus a connectivity chip to its right
            val chipShape = RoundedCornerShape(8.dp)
            val secondary = MaterialTheme.colorScheme.secondary
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (isLoading) "Working…" else "Pull Data",
                    color = secondary,
                    modifier =
                        Modifier
                            .background(secondary.copy(alpha = 0.12f), chipShape)
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                            .pointerHoverIcon(PointerIcon.Hand)
                            .clickable(enabled = !isLoading) { onRefresh() },
                    fontSize = 12.sp,
                )
                val onlineColor = if (isOnline) Color(0xFF4CAF50) else Color(0xFFF44336)
                val label = if (isOnline) "✓ Online" else "⨯ Offline"
                Text(
                    text = label,
                    color = onlineColor,
                    modifier =
                        Modifier
                            .background(onlineColor.copy(alpha = 0.12f), chipShape)
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    fontSize = 12.sp,
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (recommendations.isNotEmpty()) {
            Text(
                "Suggestions",
                fontSize = 13.sp,
                color = Color.White.copy(alpha = 0.72f),
                modifier = Modifier.padding(bottom = 6.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                recommendations.forEach { suggestion ->
                    Text(
                        "• $suggestion",
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = 12.sp,
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        Text(
            "Assistant",
            fontSize = 14.sp,
            color = Color.White.copy(alpha = 0.72f),
            modifier = Modifier.padding(bottom = 8.dp),
        )
        ChatTranscript(
            messages = messages,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 320.dp),
        )
    }
}

@Composable
private fun Feed(
    mailState: FeedSourceState<GmailMail>,
    calendarState: FeedSourceState<CalendarEvent>,
    tasksState: FeedSourceState<TaskItem>,
    ticketsState: FeedSourceState<TicketUiModel>,
    onTaskToggle: (TaskItem) -> Unit,
) {
    val anyLoading = mailState.isLoading || calendarState.isLoading || tasksState.isLoading || ticketsState.isLoading
    val errors = listOfNotNull(mailState.error, calendarState.error, tasksState.error, ticketsState.error)
    val now = Clock.System.now()
    val timeZone = TimeZone.currentSystemDefault()
    val entries =
        buildFeedEntries(
            now = now,
            timeZone = timeZone,
            mails = mailState.items,
            events = calendarState.items,
            tasks = tasksState.items,
            tickets = ticketsState.items,
        )

    val importantEntries =
        entries.filter {
            it.priority.weight >= FeedPriority.HIGH.weight || it.type == FeedEntryType.TRAVEL
        }
    val displayedEntries =
        importantEntries.filter {
            it.isAttention || it.type == FeedEntryType.TRAVEL
        }
    val hasOnlyLowPriority = displayedEntries.isEmpty() && entries.isNotEmpty()
    val todayCount = entries.count { it.dayBucket == FeedDayBucket.TODAY }
    val eventCount = entries.count { it.type == FeedEntryType.EVENT }
    val taskCount = entries.count { it.type == FeedEntryType.TASK }
    val reminderTextParts =
        buildList {
            if (eventCount > 0) add("$eventCount event" + if (eventCount == 1) "" else "s")
            if (taskCount > 0) add("$taskCount reminder" + if (taskCount == 1) "" else "s")
            val otherCount = entries.size - eventCount - taskCount
            if (otherCount > 0) add("$otherCount other" + if (otherCount == 1) " item" else " items")
        }
    val reminderSummary =
        when (reminderTextParts.size) {
            0 -> "no upcoming items"
            1 -> reminderTextParts.first()
            else -> reminderTextParts.dropLast(1).joinToString(", ") + " and " + reminderTextParts.last()
        }

    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Assistant briefing", fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
            Text(
                "Curated from your inbox, calendar, and reminders.",
                color = MaterialTheme.colorScheme.secondary,
                fontSize = 13.sp,
            )
        }

        when {
            errors.isNotEmpty() -> {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFFFFF3E0).copy(alpha = 0.12f))
                            .border(1.dp, Color(0xFFFFA000).copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                            .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("Heads-up", fontWeight = FontWeight.SemiBold, color = Color(0xFFFFA000))
                    errors.distinct().forEach { msg ->
                        Text(msg, color = Color(0xFFFFE0B2), fontSize = 13.sp)
                    }
                }
            }
            anyLoading && displayedEntries.isEmpty() -> {
                Text("Gathering updates…", color = MaterialTheme.colorScheme.secondary)
            }
        }

        if (!anyLoading && errors.isEmpty()) {
            when {
                entries.isEmpty() -> {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color.White.copy(alpha = 0.03f))
                                .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(16.dp))
                                .padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("You're all set", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Nothing urgent right now. I'll surface anything that needs you immediately.",
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }
                hasOnlyLowPriority -> {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color.White.copy(alpha = 0.03f))
                                .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(16.dp))
                                .padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("Nothing urgent", fontWeight = FontWeight.SemiBold)
                        val todayClause =
                            if (todayCount > 0) {
                                "$todayCount item" + if (todayCount == 1) " scheduled today" else "s scheduled today"
                            } else {
                                "${entries.size} upcoming item" + if (entries.size == 1) "" else "s overall"
                            }
                        Text(
                            "$todayClause — $reminderSummary. I'll nudge you when something needs attention.",
                            color = MaterialTheme.colorScheme.secondary,
                            fontSize = 13.sp,
                        )
                    }
                }
            }
        }

        if (displayedEntries.isNotEmpty()) {
            val dayOrder =
                listOf(
                    FeedDayBucket.TODAY,
                    FeedDayBucket.TOMORROW,
                    FeedDayBucket.UPCOMING,
                    FeedDayBucket.SOMEDAY,
                )
            val priorityOrder = listOf(FeedPriority.CRITICAL, FeedPriority.HIGH, FeedPriority.NORMAL, FeedPriority.LOW)
            var shownAnyDay = false
            dayOrder.forEach { bucket ->
                val dayEntries = displayedEntries.filter { it.dayBucket == bucket }
                if (dayEntries.isNotEmpty()) {
                    if (shownAnyDay) {
                        Spacer(modifier = Modifier.height(18.dp))
                    }
                    shownAnyDay = true

                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(bucket.displayTitle, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        val showPriorityHeaders = dayEntries.map { it.priority }.distinct().size > 1
                        priorityOrder.forEach { priority ->
                            val items = dayEntries.filter { it.priority == priority }
                            if (items.isNotEmpty()) {
                                if (showPriorityHeaders) {
                                    Text(
                                        priority.groupTitle,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.secondary,
                                    )
                                }
                                items.forEach { entry ->
                                    if (entry.type == FeedEntryType.TASK && entry.task != null) {
                                        FeedReminderItem(entry, onTaskToggle)
                                    } else if (entry.type == FeedEntryType.TRAVEL && entry.ticket != null) {
                                        TicketUI(model = entry.ticket, modifier = Modifier.fillMaxWidth(0.65f))
                                    } else {
                                        FeedItemCard(entry)
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

private data class FeedSourceState<T>(
    val isLoading: Boolean,
    val error: String?,
    val items: List<T>,
)

private enum class FeedPriority(val weight: Int, val groupTitle: String) {
    CRITICAL(4, "Needs attention"),
    HIGH(3, "Up next"),
    NORMAL(2, "Later today"),
    LOW(1, "On the radar"),
}

private enum class FeedDayBucket(val order: Int, val displayTitle: String) {
    TODAY(0, "Today"),
    TOMORROW(1, "Tomorrow"),
    UPCOMING(2, "Upcoming days"),
    SOMEDAY(3, "Later"),
}

private enum class FeedEntryType { TASK, EVENT, MAIL, TRAVEL }

private data class FeedEntry(
    val id: String,
    val type: FeedEntryType,
    val title: String,
    val body: String?,
    val context: String?,
    val timing: String?,
    val badge: String?,
    val priority: FeedPriority,
    val sortMillis: Long,
    val dayBucket: FeedDayBucket,
    val task: TaskItem? = null,
    val ticket: TicketUiModel? = null,
    val isAttention: Boolean = true,
)

private data class FeedTimingMeta(
    val priority: FeedPriority,
    val timing: String,
    val badge: String?,
    val sortMillis: Long,
)

private fun buildFeedEntries(
    now: Instant,
    timeZone: TimeZone,
    mails: List<GmailMail>,
    events: List<CalendarEvent>,
    tasks: List<TaskItem>,
    tickets: List<TicketUiModel>,
): List<FeedEntry> {
    val entries = mutableListOf<FeedEntry>()
    val nowDateTime = now.toLocalDateTime(timeZone)
    var fallbackSort = Long.MAX_VALUE - 1_000L

    tasks.filter { !it.completed }.forEach { task ->
        val due = task.due
        val timingMeta =
            if (due != null) {
                val dueInstant = due.toInstant(timeZone)
                val diffMinutes = (dueInstant - now).inWholeMinutes
                val overdueMinutes = (now - dueInstant).inWholeMinutes
                when {
                    diffMinutes <= 0 ->
                        FeedTimingMeta(
                            priority = FeedPriority.CRITICAL,
                            timing = "Overdue by ${formatMinutesAgo(overdueMinutes)}",
                            badge = "Overdue",
                            sortMillis = dueInstant.toEpochMilliseconds(),
                        )
                    diffMinutes in 1..30 ->
                        FeedTimingMeta(
                            priority = FeedPriority.CRITICAL,
                            timing = "Due ${formatMinutesUntil(diffMinutes)}",
                            badge = "Due soon",
                            sortMillis = dueInstant.toEpochMilliseconds(),
                        )
                    diffMinutes in 31..120 ->
                        FeedTimingMeta(
                            priority = FeedPriority.HIGH,
                            timing = "Due ${formatMinutesUntil(diffMinutes)}",
                            badge = "Due soon",
                            sortMillis = dueInstant.toEpochMilliseconds(),
                        )
                    due.date == nowDateTime.date ->
                        FeedTimingMeta(
                            priority = FeedPriority.HIGH,
                            timing = "Due today • ${formatClock(due)}",
                            badge = null,
                            sortMillis = dueInstant.toEpochMilliseconds(),
                        )
                    due.date == nowDateTime.date.plus(DatePeriod(days = 1)) ->
                        FeedTimingMeta(
                            priority = FeedPriority.NORMAL,
                            timing = "Due tomorrow • ${formatClock(due)}",
                            badge = null,
                            sortMillis = dueInstant.toEpochMilliseconds(),
                        )
                    else ->
                        FeedTimingMeta(
                            priority = FeedPriority.NORMAL,
                            timing = "Due ${formatCalendarDay(due, nowDateTime.date)}",
                            badge = null,
                            sortMillis = dueInstant.toEpochMilliseconds(),
                        )
                }
            } else {
                FeedTimingMeta(
                    priority = FeedPriority.LOW,
                    timing = "No due date",
                    badge = null,
                    sortMillis = fallbackSort--,
                )
            }

        val (priority, timing, badge, sortMillis) = timingMeta
        val dayBucket = if (due != null) bucketForDate(due.date, nowDateTime.date) else FeedDayBucket.SOMEDAY
        entries +=
            FeedEntry(
                id = "task-${task.id}",
                type = FeedEntryType.TASK,
                title = task.title.ifBlank { "Untitled task" },
                body = task.notes?.takeIf { it.isNotBlank() },
                context = task.listTitle?.takeIf { it.isNotBlank() }?.let { "List: $it" },
                timing = timing,
                badge = badge,
                priority = priority,
                sortMillis = sortMillis,
                dayBucket = dayBucket,
                task = task,
                isAttention = priority.weight >= FeedPriority.HIGH.weight,
            )
    }

    val attentionEventKeywords =
        listOf(
            "meet",
            "sync",
            "review",
            "deadline",
            "due",
            "handoff",
            "presentation",
            "client",
            "interview",
            "standup",
            "all-hands",
            "all hands",
            "1:1",
            "retro",
            "postmortem",
            "demo",
            "kickoff",
            "launch",
            "planning",
            "board",
            "status",
            "qbr",
        )

    events.forEach { event ->
        val startInstant = event.start.toInstant(timeZone)
        val endInstant = event.end.toInstant(timeZone)
        val minutesUntil = (startInstant - now).inWholeMinutes
        val minutesSinceStart = (now - startInstant).inWholeMinutes
        val minutesUntilEnd = (endInstant - now).inWholeMinutes
        val isOngoing = minutesUntil <= 0 && minutesUntilEnd > 0

        val titleLower = event.title.lowercase()
        val notesLower = event.notes?.lowercase() ?: ""
        val keywordHit =
            attentionEventKeywords.any { keyword ->
                titleLower.contains(keyword) || notesLower.contains(keyword)
            }

        val priority =
            when {
                isOngoing -> FeedPriority.CRITICAL
                keywordHit && minutesUntil in 0L..60L -> FeedPriority.CRITICAL
                minutesUntil in 0L..30L && keywordHit -> FeedPriority.CRITICAL
                keywordHit && minutesUntil in 61L..(4L * 60L) -> FeedPriority.HIGH
                minutesUntil in 0L..30L -> FeedPriority.HIGH
                keywordHit && minutesUntil in 0L..(24L * 60L) -> FeedPriority.HIGH
                keywordHit -> FeedPriority.NORMAL
                minutesUntil in 31L..120L -> FeedPriority.NORMAL
                event.start.date == nowDateTime.date -> FeedPriority.LOW
                event.start.date == nowDateTime.date.plus(DatePeriod(days = 1)) -> FeedPriority.LOW
                else -> FeedPriority.LOW
            }

        val badge =
            when {
                isOngoing -> "In progress"
                keywordHit && minutesUntil in 0L..120L -> "Starting soon"
                else -> null
            }

        val timing =
            when {
                isOngoing -> "Ends ${formatClock(event.end)}"
                minutesUntil < 0 -> "Started ${formatMinutesAgo(minutesSinceStart)}"
                minutesUntil == 0L -> "Starting now"
                minutesUntil < 60 -> "Starts ${formatMinutesUntil(minutesUntil)}"
                event.start.date == nowDateTime.date -> "Today • ${formatClock(event.start)}"
                event.start.date == nowDateTime.date.plus(DatePeriod(days = 1)) -> "Tomorrow • ${formatClock(
                    event.start,
                )}"
                else -> "${formatCalendarDay(event.start, nowDateTime.date)} • ${formatClock(event.start)}"
            }

        val dayBucket = bucketForDate(event.start.date, nowDateTime.date)
        val isAttention = (keywordHit && minutesUntil >= 0) || isOngoing
        entries +=
            FeedEntry(
                id = "event-${event.id}",
                type = FeedEntryType.EVENT,
                title = event.title.ifBlank { "Calendar event" },
                body = event.notes?.takeIf { it.isNotBlank() },
                context = event.location?.takeIf { it.isNotBlank() },
                timing = timing,
                badge = badge,
                priority = priority,
                sortMillis = startInstant.toEpochMilliseconds(),
                dayBucket = dayBucket,
                isAttention = isAttention,
            )
    }

    val urgentKeywords =
        listOf(
            "urgent",
            "asap",
            "action required",
            "follow up",
            "deadline",
            "important",
            "review",
            "please respond",
            "need feedback",
            "blocking",
        )
    mails.forEach { mail ->
        val sentInstant = mail.sentTime.toInstant(timeZone)
        val minutesAgo = (now - sentInstant).inWholeMinutes
        val textToScan = "${mail.subject} ${mail.snippet} ${mail.body}".lowercase()
        val isUrgent = urgentKeywords.any { keyword -> textToScan.contains(keyword) }
        val priority =
            when {
                isUrgent && minutesAgo <= 360 -> FeedPriority.CRITICAL
                isUrgent -> FeedPriority.HIGH
                else -> FeedPriority.LOW
            }
        val badge =
            when {
                isUrgent -> "Flagged"
                else -> null
            }
        val timing = if (minutesAgo <= 0) "Just arrived" else "Received ${formatMinutesAgo(minutesAgo)}"
        entries +=
            FeedEntry(
                id = "mail-${mail.id}",
                type = FeedEntryType.MAIL,
                title = mail.subject.ifBlank { "(No subject)" },
                body = (mail.body.ifBlank { mail.snippet }).take(140).ifBlank { null },
                context = "From ${mail.from}",
                timing = timing,
                badge = badge,
                priority = priority,
                sortMillis = sentInstant.toEpochMilliseconds(),
                dayBucket = FeedDayBucket.TODAY,
                isAttention = isUrgent,
            )
    }

    tickets.forEach { ticket ->
        val departInstant = ticket.departureDateTime.toInstant(timeZone)
        val minutesUntil = (departInstant - now).inWholeMinutes
        val priority =
            when {
                minutesUntil <= 4 * 60 -> FeedPriority.CRITICAL
                minutesUntil <= 24 * 60 -> FeedPriority.HIGH
                else -> FeedPriority.NORMAL
            }
        val badge =
            when {
                minutesUntil <= 0 -> "Departed"
                minutesUntil <= 4 * 60 -> "Travel window"
                minutesUntil <= 24 * 60 -> "Upcoming trip"
                else -> null
            }
        val timing =
            when {
                minutesUntil <= 0 -> "Departed ${formatMinutesAgo((now - departInstant).inWholeMinutes)}"
                minutesUntil < 60 -> "Departs ${formatMinutesUntil(minutesUntil)}"
                minutesUntil < 24 * 60 -> "Departs ${formatCalendarDay(
                    ticket.departureDateTime,
                    nowDateTime.date,
                )} • ${formatClock(ticket.departureDateTime)}"
                else -> "${formatCalendarDay(
                    ticket.departureDateTime,
                    nowDateTime.date,
                )} • ${formatClock(ticket.departureDateTime)}"
            }

        val dayBucket = bucketForDate(ticket.departureDateTime.date, nowDateTime.date)
        entries +=
            FeedEntry(
                id = "travel-${ticket.type}-${ticket.number}-${ticket.departureDateTime}",
                type = FeedEntryType.TRAVEL,
                title = "${ticketTypeLabel(ticket.type)} ${ticket.number}",
                body = "${ticket.departureAirportAbbr} → ${ticket.arrivalAirportAbbr}",
                context = "${ticket.departureAirport} to ${ticket.arrivalAirport}",
                timing = timing,
                badge = badge,
                priority = priority,
                sortMillis = departInstant.toEpochMilliseconds(),
                dayBucket = dayBucket,
                ticket = ticket,
                isAttention = true,
            )
    }

    return entries
        .sortedWith(
            compareBy<FeedEntry> { it.dayBucket.order }
                .thenByDescending { it.priority.weight }
                .thenBy { it.sortMillis }
                .thenBy { it.title.lowercase() },
        )
        .take(20)
}

private fun bucketForDate(
    target: LocalDate?,
    today: LocalDate,
): FeedDayBucket {
    if (target == null) return FeedDayBucket.SOMEDAY
    val diff = today.daysUntil(target)
    return when {
        diff <= 0 -> FeedDayBucket.TODAY
        diff == 1 -> FeedDayBucket.TOMORROW
        diff in 2..7 -> FeedDayBucket.UPCOMING
        else -> FeedDayBucket.SOMEDAY
    }
}

@Composable
private fun FeedItemCard(entry: FeedEntry) {
    val (icon, accent) = remember(entry.type) { feedVisualsFor(entry.type) }
    val borderColor =
        accent.copy(
            alpha =
                when (entry.priority) {
                    FeedPriority.CRITICAL -> 0.8f
                    FeedPriority.HIGH -> 0.55f
                    FeedPriority.NORMAL -> 0.35f
                    FeedPriority.LOW -> 0.2f
                },
        )
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Color.White.copy(alpha = 0.02f))
                .border(1.dp, borderColor, RoundedCornerShape(18.dp))
                .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier =
                Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(accent.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = accent)
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(entry.title, fontWeight = FontWeight.SemiBold)
                entry.badge?.let { badge ->
                    Text(
                        badge,
                        color = accent,
                        fontSize = 11.sp,
                        modifier =
                            Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(accent.copy(alpha = 0.12f))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            entry.body?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.secondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 13.sp,
                )
            }
            entry.context?.let {
                Text(it, color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.8f), fontSize = 12.sp)
            }
            entry.timing?.let {
                Text(it, color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp)
            }
        }
    }
}

private fun feedVisualsFor(type: FeedEntryType): Pair<ImageVector, Color> =
    when (type) {
        FeedEntryType.TASK -> Icons.Filled.TaskAlt to Color(0xFF66BB6A)
        FeedEntryType.EVENT -> Icons.Filled.Event to Color(0xFF42A5F5)
        FeedEntryType.MAIL -> Icons.Filled.Mail to Color(0xFFFFB300)
        FeedEntryType.TRAVEL -> Icons.Filled.AirplanemodeActive to Color(0xFFAB47BC)
    }

private fun buildTicketsFromCalendar(events: List<CalendarEvent>): List<TicketUiModel> {
    return events.mapNotNull { event ->
        val title = event.title.trim()
        if (title.isBlank()) return@mapNotNull null
        val type =
            when {
                title.contains("train", ignoreCase = true) -> TicketTypes.TRAIN
                title.contains("bus", ignoreCase = true) -> TicketTypes.BUS
                else -> TicketTypes.FLIGHT
            }
        val number = extractTicketNumber(title).ifBlank { "TBD" }
        val arrivalName = extractArrivalName(title).ifBlank { title }
        val (depName, depAbbr) = parseLocation(event.location)
        val arrivalAbbr = extractAirportAbbr(arrivalName)
        TicketUiModel(
            type = type,
            number = number,
            status = TicketStatus.ON_TIME,
            departureAirport = depName.ifBlank { event.location?.trim().orEmpty() },
            departureAirportAbbr = depAbbr,
            arrivalAirport = arrivalName,
            arrivalAirportAbbr = arrivalAbbr,
            departureDateTime = event.start,
            arrivalDateTime = event.end,
        )
    }
}

private fun extractTicketNumber(title: String): String {
    val paren = Regex("\\(([A-Z0-9]{1,3}\\s?\\d{2,5})\\)").find(title)?.groupValues?.get(1)
    if (!paren.isNullOrBlank()) return paren
    val inline = Regex("\\b[A-Z]{1,3}\\s?\\d{2,5}\\b").find(title)?.value
    return inline ?: ""
}

private fun extractArrivalName(title: String): String {
    val idx = title.indexOf(" to ", ignoreCase = true)
    if (idx == -1) return title
    val after = title.substring(idx + 4)
    return after.substringBefore("(").trim()
}

private fun parseLocation(location: String?): Pair<String, String> {
    val loc = location?.trim().orEmpty()
    if (loc.isBlank()) return "" to ""
    val abbr = extractAirportAbbr(loc)
    val name = if (abbr.isNotBlank()) loc.replace(abbr, "").trim().trim(',', '-', ' ') else loc
    return name to abbr
}

private fun extractAirportAbbr(text: String): String {
    val token =
        Regex("\\b[A-Z]{3}\\b", RegexOption.IGNORE_CASE).findAll(text).lastOrNull()?.value
    if (!token.isNullOrBlank()) return token.uppercase()
    val lastWord = text.trim().split(Regex("\\s+")).lastOrNull().orEmpty()
    val cleanedLast = lastWord.trim { !it.isLetter() }
    return if (cleanedLast.length == 3 && cleanedLast.all { it.isLetter() }) {
        cleanedLast.uppercase()
    } else {
        ""
    }
}

private fun ticketTypeLabel(type: TicketTypes): String =
    when (type) {
        TicketTypes.FLIGHT -> "Flight"
        TicketTypes.TRAIN -> "Train"
        TicketTypes.BUS -> "Bus"
    }

private fun formatClock(dt: LocalDateTime): String {
    var hour = dt.hour % 12
    if (hour == 0) hour = 12
    val minute = dt.minute
    val ampm = if (dt.hour < 12) "AM" else "PM"
    val minuteStr = if (minute < 10) "0$minute" else "$minute"
    return "$hour:$minuteStr $ampm"
}

private fun formatCalendarDay(
    dt: LocalDateTime,
    today: kotlinx.datetime.LocalDate,
): String {
    val date = dt.date
    return when (date) {
        today -> "Today"
        today.plus(DatePeriod(days = 1)) -> "Tomorrow"
        today.minus(DatePeriod(days = 1)) -> "Yesterday"
        else -> {
            val day = date.dayOfWeek.name.lowercase().replaceFirstChar { it.titlecase() }
            val month = date.month.name.lowercase().replaceFirstChar { it.titlecase() }
            "$day, $month ${date.dayOfMonth}"
        }
    }
}

private fun formatMinutesUntil(minutes: Long): String {
    if (minutes <= 0) return "Now"
    return when {
        minutes < 60 -> "in $minutes min"
        minutes < 24 * 60 -> {
            val hours = minutes / 60
            val mins = minutes % 60
            if (mins == 0L) "in ${hours}h" else "in ${hours}h ${mins}m"
        }
        else -> {
            val days = minutes / (24 * 60)
            val rem = minutes % (24 * 60)
            if (rem == 0L) "in ${days}d" else "in ${days}d ${rem / 60}h"
        }
    }
}

private fun formatMinutesAgo(minutes: Long): String {
    if (minutes <= 0) return "just now"
    return when {
        minutes < 60 -> "$minutes min ago"
        minutes < 24 * 60 -> {
            val hours = minutes / 60
            val mins = minutes % 60
            if (mins == 0L) "${hours}h ago" else "${hours}h ${mins}m ago"
        }
        else -> {
            val days = minutes / (24 * 60)
            val rem = minutes % (24 * 60)
            if (rem == 0L) "${days}d ago" else "${days}d ${rem / 60}h ago"
        }
    }
}

@Composable
private fun FeedReminderItem(
    entry: FeedEntry,
    onToggle: (TaskItem) -> Unit,
) {
    val task = entry.task ?: return
    ReminderUI(
        date = task.due,
        reminder = task.title,
        completed = task.completed,
        notes = task.notes ?: "",
        highlighted = entry.priority == FeedPriority.CRITICAL,
        onToggleCompleted = { onToggle(task) },
    )
}

@Composable
private fun UserProfile() {
    val idToken = SessionStorage.read()?.idToken
    val displayName = idToken?.let { JwtTools.extractName(it) } ?: "Account"
    val pictureUrl = idToken?.let { JwtTools.extractPicture(it) }
    val networkBitmap = pictureUrl?.let { rememberNetworkImageBitmap(it) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        if (networkBitmap != null) {
            Image(
                bitmap = networkBitmap,
                contentDescription = "Profile picture",
                contentScale = ContentScale.Crop,
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .width(20.dp)
                        .height(20.dp),
            )
        } else {
            Image(
                painter = painterResource(Res.drawable.UserProfile),
                contentDescription = "Default profile",
                contentScale = ContentScale.Crop,
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .width(20.dp)
                        .height(20.dp),
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(displayName)
    }
}

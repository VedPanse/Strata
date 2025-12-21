/*
 * Strata - Kotlin Multiplatform client.
 * Copyright (c) 2025.
 */
package org.strata.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetAt
import kotlinx.datetime.toLocalDateTime
import org.strata.ai.ChatAi
import org.strata.ai.RefreshSignals
import org.strata.ai.buildAssistantMessageFromResponse
import org.strata.ai.handleGeminiResponse
import org.strata.perception.ScreenPerception
import org.strata.perception.ScreenPerceptionFormatter
import org.strata.persistence.PlanStore
import org.strata.persistence.MemoryStore

@Composable
fun StrataButton(
    text: String,
    enabled: Boolean = true,
    shape: Shape = MaterialTheme.shapes.extraLarge,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier =
            Modifier
                .height(48.dp)
                .pointerHoverIcon(PointerIcon.Hand),
        shape = shape,
        colors =
            ButtonDefaults.buttonColors(
                containerColor = if (enabled) Color.White else Color.Black,
                contentColor = if (enabled) Color.Black else Color.White,
            ),
    ) {
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun HorizontalLine() {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Color.White.copy(alpha = 0.1f)),
    )
}

val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
private val DAY_NAMES = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

@Composable
fun PromptInput(
    fullWidth: Boolean = true,
    contentAlignment: Alignment = Alignment.Center,
    modifier: Modifier = Modifier,
    chatHistory: List<Pair<String, String>> = emptyList(),
    beforeSend: (suspend () -> Unit)? = null,
    onAgentReply: ((user: String, assistantReplies: List<String>, refreshSignals: RefreshSignals) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf(TextFieldValue("")) }

    // loader + banner state
    var sending by remember { mutableStateOf(false) }
    var banner by remember { mutableStateOf<String?>(null) }
    var bannerIsError by remember { mutableStateOf(false) }

    fun localPendingResponse(pendingPlan: org.strata.persistence.PendingPlan?, userText: String): String? {
        if (pendingPlan == null) return null
        val normalized = userText.trim().lowercase()
        if (normalized.isEmpty()) return null

        val yesTokens =
            setOf(
                "yes", "y", "ok", "okay", "sure", "do it", "go ahead", "proceed",
            )
        val noTokens =
            setOf(
                "no", "n", "stop", "cancel", "don't", "do not", "never mind", "nevermind",
            )

        if (noTokens.contains(normalized)) {
            org.strata.persistence.PlanStore.clearPending()
            return "Okay, I won't proceed."
        }

        if (pendingPlan.status == "external_action" && yesTokens.contains(normalized)) {
            org.strata.persistence.PlanStore.clearPending()
            return "Got it. I'll proceed once the integration is connected."
        }

        if (pendingPlan.status == "await_user" && normalized in setOf("cancel", "never mind", "nevermind")) {
            org.strata.persistence.PlanStore.clearPending()
            return "No problem - tell me what you'd like to do instead."
        }

        return null
    }

    // auto-hide banner after 5s
    LaunchedEffect(banner) {
        if (banner != null) {
            delay(5_000)
            banner = null
        }
    }

    // width animation
    val targetWidth = if (query.text.length > 20) 500.dp else 350.dp
    val animatedWidth by animateDpAsState(targetValue = targetWidth, animationSpec = tween(250))
    val innerScroll = rememberScrollState()

    Box(modifier = (if (fullWidth) modifier.fillMaxWidth() else modifier), contentAlignment = contentAlignment) {
        // Input container
        Box(
            modifier =
                Modifier
                    .width(animatedWidth)
                    .clip(RoundedCornerShape(25.dp))
                    .background(Color(0xFF1A1B1B)),
        ) {
            // Content (text field)
            Box(
                modifier =
                    Modifier
                        .heightIn(max = 100.dp)
                        .verticalScroll(innerScroll)
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .fillMaxWidth()
                        .animateContentSize(),
            ) {
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = false,
                    maxLines = Int.MAX_VALUE,
                    // Disable while sending.
                    enabled = !sending,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White),
                    cursorBrush = SolidColor(Color(0xFF1E90FF)),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .onPreviewKeyEvent { event ->
                                if (
                                    // Ignore keys while sending.
                                    !sending &&
                                    event.type == KeyEventType.KeyDown &&
                                    (event.key == Key.Enter || event.key == Key.NumPadEnter)
                                ) {
                                    val isShift = event.isShiftPressed
                                    if (isShift) {
                                        // insert newline
                                        val start =
                                            minOf(
                                                query.selection.start,
                                                query.selection.end,
                                            ).coerceIn(0, query.text.length)
                                        val end =
                                            maxOf(
                                                query.selection.start,
                                                query.selection.end,
                                            ).coerceIn(0, query.text.length)
                                        val newText = query.text.replaceRange(start, end, "\n")
                                        query = query.copy(text = newText, selection = TextRange(start + 1))
                                        true
                                    } else {
                                        val textToSend = query.text
                                        if (textToSend.isNotBlank()) {
                                            query = TextFieldValue("")
                                            sending = true
                                            scope.launch {
                                                val pendingPlan = PlanStore.getPending()
                                                val localReply = localPendingResponse(pendingPlan, textToSend)
                                                if (localReply != null) {
                                                    bannerIsError = false
                                                    banner = null
                                                    onAgentReply?.invoke(
                                                        textToSend,
                                                        listOf(localReply),
                                                        RefreshSignals(),
                                                    )
                                                } else {
                                                    if (beforeSend != null) {
                                                        runCatching { beforeSend() }
                                                    }
                                                    val promptPayload = buildPromptPayload(chatHistory, textToSend, pendingPlan)
                                                    val res = ChatAi.sendPrompt(promptPayload)
                                                    if (res.isSuccess) {
                                                        val raw = res.getOrNull()
                                                        val agentResult = handleGeminiResponse(raw)
                                                        val summary = agentResult.summary
                                                        when {
                                                            summary.sentEmails > 0 && summary.failedEmails == 0 -> {
                                                                query = TextFieldValue("")
                                                                bannerIsError = false
                                                                banner = "Sent email successfully"
                                                            }
                                                            summary.sentEmails == 0 && summary.failedEmails > 0 -> {
                                                                bannerIsError = true
                                                                banner = "Failed to send email"
                                                            }
                                                            summary.sentEmails > 0 && summary.failedEmails > 0 -> {
                                                                bannerIsError = true
                                                                banner = "Partially sent (${summary.sentEmails} sent, ${summary.failedEmails} failed)"
                                                            }
                                                            else -> {
                                                                bannerIsError = false
                                                                banner = null
                                                            }
                                                        }
                                                        // Build and emit assistant reply messages for the transcript panel
                                                        val assistantReplies =
                                                            if (agentResult.userMessages.isNotEmpty()) {
                                                                agentResult.userMessages
                                                            } else {
                                                                listOf(
                                                                    buildAssistantMessageFromResponse(
                                                                        raw,
                                                                        summary,
                                                                        agentResult.userMessages,
                                                                    ),
                                                                )
                                                            }
                                                        onAgentReply?.invoke(
                                                            textToSend,
                                                            assistantReplies,
                                                            agentResult.refreshSignals,
                                                        )
                                                    } else {
                                                        bannerIsError = true
                                                        banner = "OpenAI error: ${res.exceptionOrNull()?.message ?: "Unknown error"}"
                                                        onAgentReply?.invoke(
                                                            textToSend,
                                                            listOf(
                                                                "I couldn't process that due to an error: ${res.exceptionOrNull()?.message ?: "Unknown error"}.",
                                                            ),
                                                            RefreshSignals(),
                                                        )
                                                    }
                                                }
                                                sending = false
                                            }
                                        }
                                        true
                                    }
                                } else {
                                    false
                                }
                            },
                    decorationBox = { innerTextField ->
                        Box(modifier = Modifier.fillMaxWidth()) {
                            if (query.text.isEmpty()) {
                                Text(
                                    "Ask Strata something...",
                                    color = Color.LightGray,
                                    fontSize = HomeDimens.BodyTextSize,
                                )
                            }
                            innerTextField()
                        }
                    },
                )
            }

            // Loader overlay (blocks input while sending)
            if (sending) {
                Box(
                    modifier =
                        Modifier
                            .matchParentSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier =
                            Modifier
                                .padding(horizontal = 18.dp, vertical = 12.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                            trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                        )
                        Text(
                            "Sending",
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                            fontSize = 12.sp,
                            letterSpacing = 0.2.sp,
                        )
                    }
                }
            }
        }

        // Floating banner
        if (banner != null) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(top = 8.dp, bottom = 12.dp)
                        .background(
                            color = if (bannerIsError) Color(0xFFB00020) else Color(0xFF2E7D32),
                            shape = RoundedCornerShape(10.dp),
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(
                    text = banner!!,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

// Provide a slim transcript so the agent can handle follow-up questions.
private fun buildPromptPayload(
    history: List<Pair<String, String>>,
    latestUserText: String,
    pendingPlan: org.strata.persistence.PendingPlan?,
): String {
    val localTimeContext = buildLocalTimeContext()
    val trimmed = history.filter { it.second.isNotBlank() }.takeLast(10)
    val memories = MemoryStore.list()
    val screen = ScreenPerception.latest()

    return buildString {
        append("Assistant context:\n")
        append(localTimeContext)
        append("\n\n")

        if (memories.isNotEmpty()) {
            append("Long-term memory:\n")
            memories.forEach { memory ->
                append("- ").append(memory).append('\n')
            }
            append("\n")
        }

        if (pendingPlan != null) {
            append("Pending plan:\n")
            append("Status: ").append(pendingPlan.status).append('\n')
            append("Question: ").append(pendingPlan.question).append('\n')
            pendingPlan.context?.let {
                append("Context: ").append(it).append('\n')
            }
            append("\n")
        }

        if (screen != null) {
            append("Screen perception (latest capture):\n")
            append(ScreenPerceptionFormatter.formatForPrompt(screen))
            append("\n\n")
        }

        if (trimmed.isNotEmpty()) {
            val historyBlock =
                trimmed.joinToString(separator = "\n") { (role, text) ->
                    val label = if (role.equals("assistant", ignoreCase = true)) "Assistant" else "User"
                    "$label: $text"
                }
            append("Conversation so far:\n")
            append(historyBlock)
            append("\n\n")
        }

        append("Latest user request:\n")
        append(latestUserText)
    }
}

private fun buildLocalTimeContext(): String {
    val timezone = TimeZone.currentSystemDefault()
    val now = Clock.System.now()
    val localDateTime = now.toLocalDateTime(timezone)
    val dayName = DAY_NAMES[localDateTime.date.dayOfWeek.ordinal]
    val monthIndex = localDateTime.month.ordinal
    val monthName =
        MONTHS.getOrNull(monthIndex) ?: localDateTime.month.name.lowercase().replaceFirstChar {
            it.uppercaseChar().toString()
        }
    val timeString = "%02d:%02d".format(localDateTime.hour, localDateTime.minute)
    val offset = timezone.offsetAt(now).toString()
    val offsetLabel = if (offset.startsWith("UTC")) offset else "UTC$offset"

    return "The user's current local time is $dayName, $monthName ${localDateTime.dayOfMonth}, " +
        "${localDateTime.year} at $timeString (timezone ${timezone.id}, $offsetLabel). " +
        "Plan and schedule your response using this local time unless the user specifies otherwise."
}

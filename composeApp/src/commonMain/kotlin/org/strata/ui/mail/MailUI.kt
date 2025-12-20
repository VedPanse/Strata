/*
 * Strata - Kotlin Multiplatform client.
 * Copyright (c) 2025.
 */
package org.strata.ui.mail

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.strata.auth.GmailApi
import org.strata.auth.SessionStorage
import org.strata.ui.HorizontalLine
import org.strata.ui.MONTHS

@Composable
private fun FourDots(color: Color = MaterialTheme.colorScheme.secondary) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Box(
                modifier =
                    Modifier
                        .width(4.dp)
                        .height(4.dp)
                        .background(color, RoundedCornerShape(50)),
            )
            Box(
                modifier =
                    Modifier
                        .width(4.dp)
                        .height(4.dp)
                        .background(color, RoundedCornerShape(50)),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Box(
                modifier =
                    Modifier
                        .width(4.dp)
                        .height(4.dp)
                        .background(color, RoundedCornerShape(50)),
            )
            Box(
                modifier =
                    Modifier
                        .width(4.dp)
                        .height(4.dp)
                        .background(color, RoundedCornerShape(50)),
            )
        }
    }
}

/**
 * Gmail message card UI.
 * Renders a polished preview with sender, subject and message body. Attachments
 * and inline images are summarized without rendering actual media.
 */
@Composable
fun MailUI(
    subject: String,
    body: String,
    sender: String,
    sentTime: LocalDateTime,
    attachments: List<String> = emptyList(),
    inlineImageCount: Int = 0,
    messageId: String? = null,
) {
    // Local helpers to keep changes self-contained
    fun initialsFrom(name: String): String {
        // Goal: derive initials from first and last names, ignoring emails and trailing qualifiers
        // Examples handled:
        // - "John Doe <john@x.com>" -> JD
        // - "Jane A. Doe (Acme)" -> JD
        // - "Neil Panse, PhD" -> NP
        // - "Neil Panse via GitHub" -> NP
        // - "Single" -> S
        var display = name
        // Remove email part like "<...>"
        val lt = display.indexOf('<')
        if (lt >= 0) display = display.substring(0, lt)
        // Remove anything inside parentheses
        display = display.replace(Regex("\\(.*?\\)"), " ")
        // Replace commas with spaces
        display = display.replace(",", " ")
        // Normalize whitespace
        val rawParts = display.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val blacklist = setOf("jr", "sr", "ii", "iii", "iv", "phd", "md", "esq", "inc", "ltd", "via")
        val parts =
            rawParts
                .map { it.trim('"', '\'', '.', '-') }
                .map { it.replace(Regex("[^A-Za-z]"), "") }
                .filter { it.isNotBlank() }
                .filter { it.lowercase() !in blacklist }
        return when {
            parts.isEmpty() -> "?"
            parts.size == 1 -> parts.first().take(1).uppercase()
            else -> (parts.first().first().toString() + parts.last().first().toString()).uppercase()
        }
    }

    fun formatMailDateTime(dt: LocalDateTime): String {
        // Best-effort human style similar to Apple: Today/Yesterday else dd MMM yyyy, h:mm a
        val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
        val today = now.date
        val sameDate = (dt.date == today)
        val yesterday =
            run {
                val yd = kotlinx.datetime.LocalDate.fromEpochDays(today.toEpochDays() - 1)
                dt.date == yd
            }
        var hour = dt.hour % 12
        if (hour == 0) hour = 12
        val minuteStr = if (dt.minute < 10) "0${dt.minute}" else "${dt.minute}"
        val ampm = if (dt.hour < 12) "AM" else "PM"
        val timeStr = "$hour:$minuteStr $ampm"
        val monthShort = MONTHS[dt.monthNumber - 1]
        return when {
            sameDate -> "Today $timeStr"
            yesterday -> "Yesterday $timeStr"
            now.year == dt.year -> "${dt.dayOfMonth} $monthShort, $timeStr"
            else -> "${dt.dayOfMonth} $monthShort ${dt.year}, $timeStr"
        }
    }

    fun extractEmailAddress(rawSender: String): String {
        // Try angle brackets first: "Name <email@domain>"
        val m = Regex("<([^>]+)>").find(rawSender)
        if (m != null) return m.groupValues[1].trim()
        // Otherwise, pick the first token that looks like an email
        val tokens = rawSender.replace(",", " ").split(Regex("\\s+")).filter { it.contains("@") }
        if (tokens.isNotEmpty()) return tokens.first().trim('<', '>', '"')
        // Fallback: return as-is
        return rawSender.trim()
    }

    var hideSelf by remember { mutableStateOf(false) }
    if (hideSelf) return
    val scope = rememberCoroutineScope()

    Column(
        modifier =
            Modifier
                .width(700.dp)
                .background(Color(0xFF141618), RoundedCornerShape(16.dp))
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
                .padding(20.dp),
    ) {
        // Header: avatar + sender + date
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Avatar
            Box(
                modifier =
                    Modifier
                        .background(Color(0xFF2D9CDB).copy(alpha = 0.85f), shape = RoundedCornerShape(999.dp))
                        .height(36.dp)
                        .width(36.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = initialsFrom(sender),
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(sender, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                Text(
                    text = formatMailDateTime(sentTime),
                    color = MaterialTheme.colorScheme.secondary,
                    fontSize = 12.sp,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Small cross to mark as read (not delete)
                val subtleBg = Color.White.copy(alpha = 0.06f)
                val subtleBorder = Color.White.copy(alpha = 0.08f)
                var markError by remember { mutableStateOf<String?>(null) }
                Box(
                    modifier =
                        Modifier
                            .background(subtleBg, RoundedCornerShape(999.dp))
                            .border(1.dp, subtleBorder, RoundedCornerShape(999.dp))
                            .padding(2.dp)
                            .pointerHoverIcon(PointerIcon.Hand)
                            .clickable {
                                markError = null
                                val id = messageId
                                val token = SessionStorage.read()?.accessToken
                                if (id.isNullOrBlank()) {
                                    markError = "Cannot mark this message"
                                } else {
                                    scope.launch {
                                        val res = GmailApi.markAsRead(token, id)
                                        res.fold(
                                            onSuccess = { hideSelf = true },
                                            onFailure = { markError = it.message ?: "Failed" },
                                        )
                                    }
                                }
                            },
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Mark as read",
                        tint = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Subject
        Text(
            text = subject,
            fontWeight = FontWeight.Bold,
            fontSize = 28.sp,
            color = MaterialTheme.colorScheme.primary,
        )

        Spacer(modifier = Modifier.height(12.dp))
        HorizontalLine()
        Spacer(modifier = Modifier.height(12.dp))

        // Body
        Text(
            text = body,
            color = Color.White,
            fontSize = 16.sp,
        )

        // Inline images and attachments summary (simple, non-rendering)
        if (inlineImageCount > 0 || attachments.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (inlineImageCount > 0) {
                    Text(
                        "Images: $inlineImageCount (not displayed)",
                        color = MaterialTheme.colorScheme.secondary,
                        fontSize = 12.sp,
                    )
                }
            }
            if (attachments.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Attachments:", color = MaterialTheme.colorScheme.secondary)
                    attachments.take(5).forEach { fname ->
                        Box(
                            modifier =
                                Modifier
                                    .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(8.dp))
                                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                        ) {
                            Text(fname, color = Color.White, fontSize = 13.sp)
                        }
                    }
                    if (attachments.size > 5) {
                        Text(
                            "+${attachments.size - 5} more",
                            color = MaterialTheme.colorScheme.secondary,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        HorizontalLine()
        Spacer(modifier = Modifier.height(10.dp))

        // Actions
        var showReply by remember { mutableStateOf(false) }
        var replyText by remember { mutableStateOf("") }
        var sending by remember { mutableStateOf(false) }
        var sendError by remember { mutableStateOf<String?>(null) }
        var sendSuccess by remember { mutableStateOf(false) }

        var showForward by remember { mutableStateOf(false) }
        var forwardTo by remember { mutableStateOf("") }
        var forwardBody by remember { mutableStateOf("") }
        var forwardSending by remember { mutableStateOf(false) }
        var forwardError by remember { mutableStateOf<String?>(null) }
        var forwardSuccess by remember { mutableStateOf(false) }

        var deleteError by remember { mutableStateOf<String?>(null) }
        var deleteSuccess by remember { mutableStateOf(false) }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val btnColors =
                ButtonDefaults.buttonColors(
                    containerColor = Color.White.copy(alpha = 0.06f),
                    contentColor = Color.White,
                )
            val btnShape = RoundedCornerShape(10.dp)

            Button(onClick = {
                showReply = true
                sendError = null
                sendSuccess = false
            }, colors = btnColors, shape = btnShape) {
                Icon(imageVector = Icons.AutoMirrored.Filled.Reply, contentDescription = "Reply")
                Spacer(modifier = Modifier.width(6.dp))
                Text("Reply")
            }
            Button(onClick = {
                // Prepare default forward body on first open
                if (!showForward) {
                    val header =
                        buildString {
                            append("Forwarded message:\n")
                            append("From: ").append(sender).append('\n')
                            append("Date: ").append(formatMailDateTime(sentTime)).append('\n')
                            append("Subject: ").append(subject).append("\n\n")
                        }
                    forwardBody = header + body
                }
                showForward = true
                forwardError = null
                forwardSuccess = false
            }, colors = btnColors, shape = btnShape) {
                Icon(imageVector = Icons.AutoMirrored.Filled.Forward, contentDescription = "Forward")
                Spacer(modifier = Modifier.width(6.dp))
                Text("Forward")
            }
            Button(onClick = {
                deleteError = null
                if (deleteSuccess) return@Button
                val token = SessionStorage.read()?.accessToken
                val id = messageId
                if (id.isNullOrBlank()) {
                    deleteError = "Cannot delete this message"
                } else {
                    scope.launch {
                        val res = GmailApi.deleteMessage(token, id)
                        res.fold(
                            onSuccess = {
                                deleteSuccess = true
                                hideSelf = true
                            },
                            onFailure = { deleteError = it.message ?: "Delete failed" },
                        )
                    }
                }
            }, colors = btnColors, shape = btnShape) {
                Icon(imageVector = Icons.Filled.Delete, contentDescription = "Delete")
                Spacer(modifier = Modifier.width(6.dp))
                Text(if (deleteSuccess) "Deleted" else "Delete")
            }
            Spacer(modifier = Modifier.weight(1f))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FourDots(color = MaterialTheme.colorScheme.secondary)
                Text(
                    "More Actions",
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }

        if (showReply) {
            Spacer(modifier = Modifier.height(12.dp))
            // Apple-like inline reply composer
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier =
                        Modifier
                            .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(12.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                            .padding(12.dp)
                            .fillMaxWidth(),
                ) {
                    if (replyText.isEmpty()) {
                        Text("Reply to $sender", color = MaterialTheme.colorScheme.secondary)
                    }
                    BasicTextField(
                        value = replyText,
                        onValueChange = {
                            replyText = it
                            sendError = null
                            sendSuccess = false
                        },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White, fontSize = 16.sp),
                        cursorBrush = SolidColor(Color.White),
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Default),
                        keyboardActions = KeyboardActions(),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Cancel (text-like)
                    Text(
                        text = if (sending) "Cancel" else "Cancel",
                        color = MaterialTheme.colorScheme.secondary,
                        modifier =
                            Modifier
                                .padding(horizontal = 8.dp, vertical = 8.dp)
                                .clickable(enabled = !sending) {
                                    showReply = false
                                    replyText = ""
                                    sendError = null
                                    sendSuccess = false
                                },
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    // Send button in iOS blue
                    val sendEnabled = replyText.isNotBlank() && !sending
                    Button(
                        onClick = {
                            sendError = null
                            sendSuccess = false
                            val toEmail = extractEmailAddress(sender)
                            val token = SessionStorage.read()?.accessToken
                            val mailSubject = if (subject.lowercase().startsWith("re:")) subject else "Re: $subject"
                            sending = true
                            scope.launch {
                                val res = GmailApi.sendEmail(token, toEmail, mailSubject, replyText)
                                sending = false
                                res.fold(
                                    onSuccess = {
                                        sendSuccess = true
                                        showReply = false
                                        replyText = ""
                                    },
                                    onFailure = { sendError = it.message ?: "Failed to send" },
                                )
                            }
                        },
                        enabled = sendEnabled,
                        colors =
                            ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF0A84FF),
                                contentColor = Color.White,
                                disabledContainerColor = Color.White.copy(alpha = 0.12f),
                                disabledContentColor = Color.White.copy(alpha = 0.5f),
                            ),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        if (sending) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = null,
                                modifier = Modifier.rotate(0f),
                            )
                        }
                        Text("Send")
                    }
                }
                if (sendError != null) {
                    Text(sendError!!, color = Color(0xFFFF453A), fontSize = 12.sp)
                }
                if (sendSuccess) {
                    Text("Sent", color = Color(0xFF30D158), fontSize = 12.sp)
                }
            }
        }

        if (showForward) {
            Spacer(modifier = Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // To field styled lightly like iOS pill
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("To", color = MaterialTheme.colorScheme.secondary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier =
                            Modifier
                                .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(999.dp))
                                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(999.dp))
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        BasicTextField(
                            value = forwardTo,
                            onValueChange = {
                                forwardTo = it
                                forwardError = null
                                forwardSuccess = false
                            },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White, fontSize = 14.sp),
                            cursorBrush = SolidColor(Color.White),
                            keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Next),
                            keyboardActions = KeyboardActions(),
                        )
                    }
                }
                // Body box with quoted original
                Box(
                    modifier =
                        Modifier
                            .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(12.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                            .padding(12.dp)
                            .fillMaxWidth(),
                ) {
                    if (forwardBody.isEmpty()) {
                        Text("Write a message", color = MaterialTheme.colorScheme.secondary)
                    }
                    BasicTextField(
                        value = forwardBody,
                        onValueChange = {
                            forwardBody = it
                            forwardError = null
                            forwardSuccess = false
                        },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White, fontSize = 16.sp),
                        cursorBrush = SolidColor(Color.White),
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Default),
                        keyboardActions = KeyboardActions(),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Cancel",
                        color = MaterialTheme.colorScheme.secondary,
                        modifier =
                            Modifier
                                .padding(horizontal = 8.dp, vertical = 8.dp)
                                .clickable(enabled = !forwardSending) {
                                    showForward = false
                                    forwardTo = ""
                                    forwardBody = ""
                                    forwardError = null
                                    forwardSuccess = false
                                },
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    val sendEnabled = forwardTo.isNotBlank() && forwardBody.isNotBlank() && !forwardSending
                    Button(
                        onClick = {
                            forwardError = null
                            forwardSuccess = false
                            val token = SessionStorage.read()?.accessToken
                            val mailSubject = if (subject.lowercase().startsWith("fwd:")) subject else "Fwd: $subject"
                            forwardSending = true
                            scope.launch {
                                val res = GmailApi.sendEmail(token, forwardTo, mailSubject, forwardBody)
                                forwardSending = false
                                res.fold(
                                    onSuccess = {
                                        forwardSuccess = true
                                        showForward = false
                                        forwardTo = ""
                                        forwardBody = ""
                                    },
                                    onFailure = { forwardError = it.message ?: "Failed to send" },
                                )
                            }
                        },
                        enabled = sendEnabled,
                        colors =
                            ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF0A84FF),
                                contentColor = Color.White,
                                disabledContainerColor = Color.White.copy(alpha = 0.12f),
                                disabledContentColor = Color.White.copy(alpha = 0.5f),
                            ),
                        shape = RoundedCornerShape(10.dp),
                    ) { Text("Send") }
                }
                if (forwardError != null) {
                    Text(forwardError!!, color = Color(0xFFFF453A), fontSize = 12.sp)
                }
                if (forwardSuccess) {
                    Text("Sent", color = Color(0xFF30D158), fontSize = 12.sp)
                }
            }
        }

        if (deleteError != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(deleteError!!, color = Color(0xFFFF453A), fontSize = 12.sp)
        }
        if (deleteSuccess) {
            Spacer(modifier = Modifier.height(8.dp))
            Text("Moved to Trash", color = Color(0xFF30D158), fontSize = 12.sp)
        }
    }
}

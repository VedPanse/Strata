/*
 * Strata - Kotlin Multiplatform client.
 * Copyright (c) 2025.
 */
package org.strata.ai.macro

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Attachment
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.launch
import org.strata.ai.ChatAi
import org.strata.ai.MailPreview
import org.strata.ai.MailPreviewBus
import org.strata.ai.MailPreviewDecision
import org.strata.ai.MailPreviewRequest
import org.strata.ai.handleRewrite

@Composable
fun MailPreviewOverlay(modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var active by remember { mutableStateOf<MailPreviewRequest?>(null) }

    LaunchedEffect(Unit) {
        MailPreviewBus.requests.collect { req -> active = req }
    }

    // Only show when requested
    if (active != null) {
        // Dialog renders above everything (true topmost)
        Dialog(
            onDismissRequest = {
                // Treat dismiss like cancel
                active?.result?.complete(MailPreviewDecision.Cancel)
                active = null
            },
            properties =
                DialogProperties(
                    dismissOnBackPress = true,
                    dismissOnClickOutside = true,
                    // Allow full-width on Android.
                    usePlatformDefaultWidth = false,
                ),
        ) {
            // Fullscreen scrim that also EATS INPUT behind it
            Box(
                modifier =
                    Modifier
                        .fillMaxSize() // <- replaces matchParentSize()
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f))
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                        ) { /* consume clicks on scrim */ },
                contentAlignment = Alignment.Center,
            ) {
                PreviewSheet(
                    initial = active!!.initial,
                    onSend = { to, subject, body ->
                        scope.launch {
                            active!!.result.complete(
                                MailPreviewDecision.Send(to, subject, body),
                            )
                            active = null
                        }
                    },
                    onCancel = {
                        scope.launch {
                            active!!.result.complete(MailPreviewDecision.Cancel)
                            active = null
                        }
                    },
                )
            }
        }
    }
}

@Composable
fun PreviewSheet(
    initial: MailPreview,
    onSend: (to: List<String>, subject: String, body: String) -> Unit,
    onCancel: () -> Unit,
) {
    var recipients by remember { mutableStateOf(initial.recipients) }
    var subject by remember { mutableStateOf(initial.subject) }
    var body by remember { mutableStateOf(initial.body) }
    val scope = rememberCoroutineScope()

    Surface(
        shape = RoundedCornerShape(24.dp),
        modifier =
            Modifier
                .fillMaxWidth(0.5f)
                .fillMaxHeight(0.75f)
                .shadow(20.dp, RoundedCornerShape(24.dp))
                .background(Color(0xFF111111)),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Title bar
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "New Message",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )

                IconButton(
                    onClick = { },
                    modifier =
                        Modifier.size(20.dp)
                            .pointerHoverIcon(PointerIcon.Hand),
                ) {
                    Icon(
                        Icons.Outlined.Attachment,
                        contentDescription = "Attach files",
                    )
                }

                Spacer(Modifier.width(30.dp))

                var expanded by remember { mutableStateOf(false) }
                var text by remember { mutableStateOf("") }

                Box {
                    IconButton(
                        onClick = { expanded = !expanded },
                        modifier =
                            Modifier
                                .size(20.dp)
                                .pointerHoverIcon(PointerIcon.Hand),
                    ) {
                        Icon(Icons.Outlined.AutoAwesome, contentDescription = "Smart write")
                    }

                    if (expanded) {
                        Popup(
                            alignment = Alignment.TopStart,
                            // Position below the icon.
                            offset = IntOffset(-280, 60),
                            onDismissRequest = { expanded = false },
                            // Lets it capture typing.
                            properties = PopupProperties(focusable = true),
                        ) {
                            BasicTextField(
                                value = text,
                                onValueChange = { text = it },
                                singleLine = false,
                                textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                                cursorBrush = SolidColor(Color.White),
                                modifier =
                                    Modifier
                                        .width(280.dp)
                                        .background(Color(0xFF333333), RoundedCornerShape(25.dp))
                                        .padding(vertical = 7.dp, horizontal = 10.dp)
                                        .onPreviewKeyEvent { event ->
                                            val isEnter = event.key == Key.Enter || event.key == Key.NumPadEnter
                                            if (event.type == KeyEventType.KeyDown && isEnter) {
                                                if (event.isShiftPressed) {
                                                    // allow newline with Shift+Enter
                                                    return@onPreviewKeyEvent false
                                                }
                                                // Handle Enter once
                                                scope.launch {
                                                    println("Subject: $subject\nbody: $body\ntext: $text")
                                                    val res = ChatAi.mailRewrite(subject, body, text)
                                                    if (res.isSuccess) {
                                                        val parsed = handleRewrite(res.getOrNull())
                                                        if (parsed != null) {
                                                            subject = parsed.subject
                                                            body = parsed.body
                                                            println(
                                                                "[Updated Preview] subject=$subject, body(length)=${body.length}",
                                                            )
                                                        } else {
                                                            println(
                                                                "[handleRewrite] No valid subject/body found; keeping originals",
                                                            )
                                                        }
                                                    } else {
                                                        println("Error: ${res.exceptionOrNull()}")
                                                    }

                                                    text = ""
                                                }

                                                expanded = false
                                                return@onPreviewKeyEvent true // consume so no newline is inserted
                                            }
                                            false
                                        },
                            )
                        }
                    }
                }

                Spacer(Modifier.width(30.dp))
                IconButton(
                    onClick = onCancel,
                    modifier =
                        Modifier.size(20.dp)
                            .pointerHoverIcon(PointerIcon.Hand),
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "Close mail preview")
                }

                Spacer(Modifier.width(30.dp))

                IconButton(
                    onClick = { onSend(recipients, subject.trim(), body) },
                    enabled = recipients.isNotEmpty() && subject.isNotBlank(),
                    modifier =
                        Modifier.size(20.dp)
                            .pointerHoverIcon(PointerIcon.Hand),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }

            Divider()

            ToComponent(
                emails = recipients,
                onEmailsChange = { recipients = it },
            )

            SubjectComponent(value = subject) { subject = it }

            Text(
                "Message",
                style =
                    MaterialTheme.typography.labelMedium.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
            )

            MessageComponent(
                value = body,
            ) {
                body = it
            }
        }
    }
}

@Composable
fun MessageComponent(
    value: String,
    onValueChange: (String) -> Unit,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(bottom = 10.dp),
        textStyle = TextStyle(color = Color.White),
        cursorBrush = SolidColor(Color.White),
        decorationBox = { innerTextField ->
            if (value.isEmpty()) {
                Text(
                    "Subject",
                    color = Color.Gray,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            innerTextField()
        },
    )
}

@Composable
fun ToComponent(
    emails: List<String>,
    onEmailsChange: (List<String>) -> Unit,
) {
    var input by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    var hadFocus by remember { mutableStateOf(false) }

    fun commit(tokens: List<String>) {
        val newOnes = tokens.map { it.trim() }.filter { it.isNotEmpty() }
        if (newOnes.isNotEmpty()) onEmailsChange((emails + newOnes).distinct())
    }

    fun commitInput() {
        if (input.isNotBlank()) {
            commit(listOf(input))
            input = ""
        }
    }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "To",
            style =
                MaterialTheme.typography.labelMedium.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            modifier = Modifier.padding(end = 8.dp),
        )

        Box {
            FlowRow {
                // chips
                emails.forEachIndexed { idx, e ->
                    EmailChip(
                        text = e,
                        onRemove = {
                            onEmailsChange(emails.toMutableList().also { it.removeAt(idx) })
                        },
                    )
                }

                // trailing input
                BasicTextField(
                    value = input,
                    onValueChange = { new ->
                        if (new.any { it in listOf(',', ' ', ';', '\n') }) {
                            val parts = new.split(',', ' ', ';', '\n')
                            commit(parts.dropLast(1))
                            input = parts.lastOrNull().orEmpty()
                        } else {
                            input = new
                        }
                    },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White),
                    cursorBrush = SolidColor(Color.White),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { commitInput() }),
                    modifier =
                        Modifier
                            .heightIn(min = 32.dp) // match chip height
                            .defaultMinSize(minWidth = 80.dp)
                            .focusRequester(focusRequester)
                            .onFocusChanged {
                                // commit on blur
                                if (hadFocus && !it.isFocused) commitInput()
                                hadFocus = it.isFocused
                            },
                )
            }
        }
    }

    Divider(color = Color.DarkGray, thickness = 1.dp)
}

@Composable
private fun EmailChip(
    text: String,
    onRemove: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .border(
                    1.dp,
                    Color.White.copy(alpha = 0.14f),
                    RoundedCornerShape(999.dp),
                )
                .background(Color(0xFF222222), RoundedCornerShape(999.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = Color.White, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(6.dp))
        Text(
            "✕",
            color = Color.White.copy(alpha = 0.80f),
            modifier =
                Modifier
                    .clickable(onClick = onRemove)
                    .padding(start = 2.dp),
        )
    }
}

@Composable
fun SubjectComponent(
    value: String,
    onValueChange: (String) -> Unit,
) {
    Column {
        // top line

        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp),
            textStyle = TextStyle(color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
            singleLine = true,
            cursorBrush = SolidColor(Color.White),
            decorationBox = { innerTextField ->
                if (value.isEmpty()) {
                    Text(
                        "Subject",
                        color = Color.Gray,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                innerTextField()
            },
        )

        // bottom line
        Divider(color = Color.DarkGray, thickness = 1.dp)
    }
}

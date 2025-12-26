/*
 * Strata - Kotlin Multiplatform client.
 * Copyright (c) 2025.
 */
package org.strata.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ScreenOverlayPrompt(
    onClose: () -> Unit,
    onBeforeSend: (suspend () -> Unit)? = null,
) {
    val messages = remember { mutableStateListOf<Pair<String, String>>() }
    val scrollState = rememberScrollState()
    val panelShape = RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp)
    var isSending by remember { mutableStateOf(false) }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Transparent),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .clip(panelShape)
                    .blur(32.dp)
                    .background(
                        brush =
                            Brush.verticalGradient(
                                colors =
                                    listOf(
                                        Color(0x70181818),
                                        Color(0x60101010),
                                    ),
                            ),
                    ),
        )
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .clip(panelShape)
                    .background(
                        brush =
                            Brush.verticalGradient(
                                colors =
                                    listOf(
                                        Color(0xD01A1C1F),
                                        Color(0xC0141518),
                                    ),
                            ),
                    )
                    .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "Screen Mode",
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 14.sp,
                    letterSpacing = 0.4.sp,
                )
                IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Close",
                        tint = Color.White.copy(alpha = 0.7f),
                    )
                }
            }

            Divider(color = Color.White.copy(alpha = 0.08f))

            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (messages.isEmpty()) {
                    Text(
                        text = "Ask about the screen and I’ll respond here.",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 12.sp,
                    )
                }

                messages.forEach { (role, text) ->
                    val isUser = role.equals("user", ignoreCase = true)
                    val bubbleColor =
                        if (isUser) {
                            Color(0xFF1E3A5F)
                        } else {
                            Color(0xFF14171B)
                        }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
                    ) {
                        Column(
                            modifier =
                                Modifier
                                    .widthIn(max = 320.dp)
                                    .background(bubbleColor, RoundedCornerShape(14.dp))
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                        ) {
                            Text(
                                text = if (isUser) "You" else "Assistant",
                                color = Color.White.copy(alpha = 0.6f),
                                fontSize = 10.sp,
                                letterSpacing = 0.4.sp,
                            )
                            Text(
                                text = text,
                                color = Color.White.copy(alpha = 0.9f),
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                            )
                        }
                    }
                }

                if (isSending) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Start,
                    ) {
                        Column(
                            modifier =
                                Modifier
                                    .widthIn(max = 320.dp)
                                    .background(Color(0xFF14171B), RoundedCornerShape(14.dp))
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                        ) {
                            Text(
                                text = "Assistant",
                                color = Color.White.copy(alpha = 0.6f),
                                fontSize = 10.sp,
                                letterSpacing = 0.4.sp,
                            )
                            Text(
                                text = "Thinking…",
                                color = Color.White.copy(alpha = 0.85f),
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                            )
                        }
                    }
                }
            }

            PromptInput(
                fullWidth = true,
                contentAlignment = Alignment.CenterEnd,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 2.dp),
                chatHistory = messages.toList(),
                beforeSend = onBeforeSend,
                includePendingPlan = false,
                clearPendingPlanOnSend = true,
                onUserSend = { text ->
                    messages.add("user" to text)
                    isSending = true
                },
                onSendingState = { sending -> isSending = sending },
                onAgentReply = { user, assistantReplies, _ ->
                    assistantReplies.forEach { reply ->
                        if (reply.isNotBlank()) {
                            messages.add("assistant" to reply)
                        }
                    }
                    isSending = false
                },
            )

            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.strata.perception.ScreenPerceptionStore

@Composable
fun ScreenOverlayPrompt(
    onClose: () -> Unit,
    onBeforeSend: (suspend () -> Unit)? = null,
) {
    val messages = remember { mutableStateListOf<Pair<String, String>>() }
    val lastAssistantReply = remember { mutableStateOf<String?>(null) }
    val latest by ScreenPerceptionStore.latest.collectAsState()
    val visionSummary = sanitizeScreenText(latest?.visionSummary?.trim().orEmpty())

    Column(
        modifier =
            Modifier
                .background(Color(0xFF101112), RoundedCornerShape(16.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier,
        ) {
            Box(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Screen Mode",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 12.sp,
                    letterSpacing = 0.3.sp,
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Close",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                )
            }
        }

        PromptInput(
            fullWidth = false,
            contentAlignment = Alignment.CenterEnd,
            chatHistory = messages.toList(),
            beforeSend = onBeforeSend,
            onAgentReply = { user, assistantReplies, _ ->
                messages.add("user" to user)
                assistantReplies.forEach { reply ->
                    if (reply.isNotBlank()) {
                        messages.add("assistant" to reply)
                        lastAssistantReply.value = reply
                    }
                }
            },
        )

        if (visionSummary.isNotBlank()) {
            SectionCard(
                title = "Screen Understanding",
                body = visionSummary,
            )
        }

        val replyText = lastAssistantReply.value?.trim().orEmpty()
        if (replyText.isNotBlank()) {
            SectionCard(
                title = "Assistant",
                body = replyText,
            )
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    body: String,
) {
    Column(
        modifier =
            Modifier
                .background(Color(0xFF151515), RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = title,
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 11.sp,
            letterSpacing = 0.6.sp,
        )
        Text(
            text = body,
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 12.sp,
            lineHeight = 16.sp,
        )
    }
}

private fun sanitizeScreenText(text: String): String {
    if (text.isBlank()) return ""
    return text
        .replace("**", "")
        .lines()
        .map { line ->
            line.trim()
                .removePrefix("- ")
                .removePrefix("• ")
                .removePrefix("* ")
        }
        .filter { it.isNotBlank() }
        .joinToString("\n")
}

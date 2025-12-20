/*
 * Strata - Kotlin Multiplatform client.
 * Copyright (c) 2025.
 */
package org.strata.ui.mail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.strata.auth.GmailMail

@Composable
fun MailFeed(
    isLoading: Boolean,
    error: String?,
    mails: List<GmailMail>,
) {
    when {
        isLoading -> {
            Text("Loading Gmail…", color = MaterialTheme.colorScheme.secondary)
        }
        error != null -> {
            Text(error!!, color = Color(0xFFFFA000))
        }
        mails.isEmpty() -> {
            Text("No unread emails.", color = MaterialTheme.colorScheme.secondary)
        }
        else -> {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                mails.take(5).forEach { m ->
                    MailUI(
                        subject = m.subject,
                        body = if (m.body.isNotBlank()) m.body else m.snippet,
                        sender = m.from,
                        sentTime = m.sentTime,
                        attachments = m.attachments,
                        inlineImageCount = m.inlineImageCount,
                        messageId = m.id,
                    )
                }
            }
        }
    }
}

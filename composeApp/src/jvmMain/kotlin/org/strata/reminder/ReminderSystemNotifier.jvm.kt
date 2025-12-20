/*
 * Strata - Kotlin Multiplatform client.
 * Copyright (c) 2025.
 */
package org.strata.reminder

import org.strata.auth.TaskItem
import java.awt.Color
import java.awt.EventQueue
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicReference

actual object ReminderSystemNotifier {
    private val formatter: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, MMM d 'at' h:mm a")
    private val trayIconRef = AtomicReference<TrayIcon?>()

    actual fun notifyDue(task: TaskItem) {
        val icon = ensureTrayIcon() ?: return
        val title = task.title.ifBlank { "Reminder due" }
        val dueText =
            task.due?.let { due ->
                val jt = java.time.LocalDateTime.of(due.year, due.monthNumber, due.dayOfMonth, due.hour, due.minute)
                formatter.format(jt.atZone(ZoneId.systemDefault()))
            } ?: "Due now"
        val body =
            buildString {
                append(dueText)
                task.notes?.takeIf { it.isNotBlank() }?.let {
                    append('\n')
                    append(it.trim())
                }
            }

        val showMessage =
            Runnable {
                runCatching {
                    icon.displayMessage(title, body, TrayIcon.MessageType.INFO)
                }
            }
        if (EventQueue.isDispatchThread()) {
            showMessage.run()
        } else {
            EventQueue.invokeLater(showMessage)
        }
    }

    private fun ensureTrayIcon(): TrayIcon? {
        val existing = trayIconRef.get()
        if (existing != null) return existing
        if (!SystemTray.isSupported()) return null

        val creator =
            Runnable {
                try {
                    val tray = SystemTray.getSystemTray()
                    val image = createImage()
                    val trayIcon =
                        TrayIcon(image, "Strata Reminders").apply {
                            isImageAutoSize = true
                        }
                    tray.add(trayIcon)
                    trayIconRef.set(trayIcon)
                } catch (_: Throwable) {
                    trayIconRef.set(null)
                }
            }

        return if (EventQueue.isDispatchThread()) {
            creator.run()
            trayIconRef.get()
        } else {
            runCatching { EventQueue.invokeAndWait(creator) }.onFailure { trayIconRef.set(null) }
            trayIconRef.get()
        }
    }

    private fun createImage(): BufferedImage {
        val size = 16
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g2 = image.createGraphics()
        try {
            g2.color = Color(0x2D, 0x9C, 0xDB)
            g2.fillRoundRect(0, 0, size, size, 6, 6)
            g2.color = Color.WHITE
            g2.drawString("S", 4, 12)
        } finally {
            g2.dispose()
        }
        return image
    }
}

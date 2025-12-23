/*
 * Strata - Kotlin Multiplatform client.
 * Copyright (c) 2025.
 */
package org.strata.platform

import java.awt.Robot
import java.awt.event.InputEvent
import java.awt.event.KeyEvent

actual object UiAutomation {
    private val robot = Robot()

    actual suspend fun moveCursor(
        x: Int,
        y: Int,
    ): Result<Unit> =
        runCatching {
            robot.mouseMove(x, y)
        }

    actual suspend fun click(
        x: Int,
        y: Int,
        button: String?,
        count: Int,
    ): Result<Unit> =
        runCatching {
            val mask = buttonMask(button)
            robot.mouseMove(x, y)
            repeat(count.coerceIn(1, 3)) {
                robot.mousePress(mask)
                robot.delay(40)
                robot.mouseRelease(mask)
                robot.delay(60)
            }
        }

    actual suspend fun mouseDown(
        x: Int,
        y: Int,
        button: String?,
    ): Result<Unit> =
        runCatching {
            robot.mouseMove(x, y)
            robot.mousePress(buttonMask(button))
        }

    actual suspend fun mouseUp(
        x: Int,
        y: Int,
        button: String?,
    ): Result<Unit> =
        runCatching {
            robot.mouseMove(x, y)
            robot.mouseRelease(buttonMask(button))
        }

    actual suspend fun drag(
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        durationMs: Int?,
        button: String?,
    ): Result<Unit> =
        runCatching {
            val totalMs = durationMs?.coerceAtLeast(50) ?: 250
            robot.mouseMove(startX, startY)
            robot.mousePress(buttonMask(button))
            robot.delay(30)
            robot.mouseMove(endX, endY)
            robot.delay(totalMs)
            robot.mouseRelease(buttonMask(button))
        }

    actual suspend fun tap(
        x: Int,
        y: Int,
    ): Result<Unit> =
        runCatching {
            robot.mouseMove(x, y)
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
            robot.delay(40)
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
        }

    actual suspend fun typeText(text: String): Result<Unit> =
        runCatching {
            text.forEach { ch ->
                typeChar(ch)
                robot.delay(6)
            }
        }

    actual suspend fun scroll(
        dx: Int,
        dy: Int,
    ): Result<Unit> =
        runCatching {
            if (dx != 0) {
                // Horizontal scroll not directly supported; emulate via shift+wheel.
                robot.keyPress(KeyEvent.VK_SHIFT)
                robot.mouseWheel((dx / 80).coerceIn(-8, 8))
                robot.keyRelease(KeyEvent.VK_SHIFT)
            }
            if (dy != 0) {
                robot.mouseWheel((dy / 80).coerceIn(-12, 12))
            }
        }

    actual suspend fun pressKey(key: String): Result<Unit> =
        runCatching {
            val keyCode = toKeyCode(key.trim().uppercase())
            if (keyCode == KeyEvent.VK_UNDEFINED) error("Unknown key: $key")
            robot.keyPress(keyCode)
            robot.keyRelease(keyCode)
        }

    actual suspend fun keyCombo(keys: List<String>): Result<Unit> =
        runCatching {
            if (keys.isEmpty()) return@runCatching
            val normalized = keys.map { it.trim().uppercase() }
            val modifiers = normalized.filter { isModifier(it) }
            val normals = normalized.filterNot { isModifier(it) }

            modifiers.forEach { key -> robot.keyPress(toKeyCode(key)) }
            normals.forEach { key -> robot.keyPress(toKeyCode(key)) }
            normals.asReversed().forEach { key -> robot.keyRelease(toKeyCode(key)) }
            modifiers.asReversed().forEach { key -> robot.keyRelease(toKeyCode(key)) }
        }

    private fun buttonMask(button: String?): Int =
        when (button?.trim()?.lowercase()) {
            "right" -> InputEvent.BUTTON3_DOWN_MASK
            "middle" -> InputEvent.BUTTON2_DOWN_MASK
            else -> InputEvent.BUTTON1_DOWN_MASK
        }

    private fun typeChar(ch: Char) {
        when {
            ch.isLetter() -> {
                val upper = ch.uppercaseChar()
                val key = KeyEvent.getExtendedKeyCodeForChar(upper.code)
                if (ch.isUpperCase()) robot.keyPress(KeyEvent.VK_SHIFT)
                robot.keyPress(key)
                robot.keyRelease(key)
                if (ch.isUpperCase()) robot.keyRelease(KeyEvent.VK_SHIFT)
            }
            ch.isDigit() -> {
                val key = KeyEvent.getExtendedKeyCodeForChar(ch.code)
                robot.keyPress(key)
                robot.keyRelease(key)
            }
            ch == ' ' -> pressKey(KeyEvent.VK_SPACE)
            ch == '\n' -> pressKey(KeyEvent.VK_ENTER)
            ch == '.' -> pressKey(KeyEvent.VK_PERIOD)
            ch == ',' -> pressKey(KeyEvent.VK_COMMA)
            ch == '/' -> pressKey(KeyEvent.VK_SLASH)
            ch == '-' -> pressKey(KeyEvent.VK_MINUS)
            ch == '_' -> pressShifted(KeyEvent.VK_MINUS)
            ch == ':' -> pressShifted(KeyEvent.VK_SEMICOLON)
            ch == ';' -> pressKey(KeyEvent.VK_SEMICOLON)
            ch == '@' -> pressShifted(KeyEvent.VK_2)
            ch == '?' -> pressShifted(KeyEvent.VK_SLASH)
            ch == '!' -> pressShifted(KeyEvent.VK_1)
            ch == '\'' -> pressKey(KeyEvent.VK_QUOTE)
            ch == '"' -> pressShifted(KeyEvent.VK_QUOTE)
            else -> {
                val key = KeyEvent.getExtendedKeyCodeForChar(ch.code)
                if (key != KeyEvent.VK_UNDEFINED) {
                    robot.keyPress(key)
                    robot.keyRelease(key)
                }
            }
        }
    }

    private fun pressKey(key: Int) {
        robot.keyPress(key)
        robot.keyRelease(key)
    }

    private fun pressShifted(key: Int) {
        robot.keyPress(KeyEvent.VK_SHIFT)
        robot.keyPress(key)
        robot.keyRelease(key)
        robot.keyRelease(KeyEvent.VK_SHIFT)
    }

    private fun isModifier(key: String): Boolean =
        key == "SHIFT" || key == "CTRL" || key == "CONTROL" || key == "ALT" ||
            key == "OPTION" || key == "CMD" || key == "COMMAND" || key == "META" || key == "WIN"

    private fun toKeyCode(key: String): Int =
        when (key) {
            "SHIFT" -> KeyEvent.VK_SHIFT
            "CTRL", "CONTROL" -> KeyEvent.VK_CONTROL
            "ALT", "OPTION" -> KeyEvent.VK_ALT
            "CMD", "COMMAND", "META" -> KeyEvent.VK_META
            "WIN" -> KeyEvent.VK_WINDOWS
            "ENTER", "RETURN" -> KeyEvent.VK_ENTER
            "SPACE" -> KeyEvent.VK_SPACE
            "TAB" -> KeyEvent.VK_TAB
            "ESC", "ESCAPE" -> KeyEvent.VK_ESCAPE
            "UP" -> KeyEvent.VK_UP
            "DOWN" -> KeyEvent.VK_DOWN
            "LEFT" -> KeyEvent.VK_LEFT
            "RIGHT" -> KeyEvent.VK_RIGHT
            else -> {
                if (key.length == 1) {
                    KeyEvent.getExtendedKeyCodeForChar(key.first().code)
                } else {
                    KeyEvent.VK_UNDEFINED
                }
            }
        }
}

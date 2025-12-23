/*
 * Strata - Kotlin Multiplatform client.
 * Copyright (c) 2025.
 */
package org.strata.platform

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay
import org.strata.perception.StrataAccessibilityService

actual object UiAutomation {
    private fun service(): AccessibilityService? = StrataAccessibilityService.instance

    actual suspend fun moveCursor(
        x: Int,
        y: Int,
    ): Result<Unit> =
        Result.success(Unit)

    actual suspend fun click(
        x: Int,
        y: Int,
        button: String?,
        count: Int,
    ): Result<Unit> =
        runCatching {
            repeat(count.coerceIn(1, 3)) {
                tap(x, y).getOrThrow()
                delay(80)
            }
        }

    actual suspend fun mouseDown(
        x: Int,
        y: Int,
        button: String?,
    ): Result<Unit> =
        Result.failure(UnsupportedOperationException("Mouse down is not supported on Android."))

    actual suspend fun mouseUp(
        x: Int,
        y: Int,
        button: String?,
    ): Result<Unit> =
        Result.failure(UnsupportedOperationException("Mouse up is not supported on Android."))

    actual suspend fun drag(
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        durationMs: Int?,
        button: String?,
    ): Result<Unit> =
        runCatching {
            val svc = service() ?: error("Accessibility service not available.")
            val path = Path().apply {
                moveTo(startX.toFloat(), startY.toFloat())
                lineTo(endX.toFloat(), endY.toFloat())
            }
            val duration = (durationMs ?: 350).coerceIn(120, 1_200)
            val gesture =
                GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, duration.toLong()))
                    .build()
            val dispatched = svc.dispatchGesture(gesture, null, null)
            if (!dispatched) error("Failed to dispatch drag gesture.")
        }

    actual suspend fun tap(
        x: Int,
        y: Int,
    ): Result<Unit> =
        runCatching {
            val svc = service() ?: error("Accessibility service not available.")
            val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            val gesture =
                GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
                    .build()
            val dispatched = svc.dispatchGesture(gesture, null, null)
            if (!dispatched) error("Failed to dispatch tap gesture.")
        }

    actual suspend fun typeText(text: String): Result<Unit> =
        runCatching {
            val svc = service() ?: error("Accessibility service not available.")
            val root = svc.rootInActiveWindow ?: error("No active window to type into.")
            val target =
                root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
                    ?: error("No focused input field.")
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            val success = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            if (!success) error("Failed to set text in focused input.")
        }

    actual suspend fun scroll(
        dx: Int,
        dy: Int,
    ): Result<Unit> =
        runCatching {
            val svc = service() ?: error("Accessibility service not available.")
            val metrics = svc.resources.displayMetrics
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val startX = width * 0.7f
            val startY = height * 0.6f
            val endX = (startX - dx).coerceIn(0f, width.toFloat())
            val endY = (startY - dy).coerceIn(0f, height.toFloat())
            val path = Path().apply {
                moveTo(startX, startY)
                lineTo(endX, endY)
            }
            val gesture =
                GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, 250))
                    .build()
            val dispatched = svc.dispatchGesture(gesture, null, null)
            if (!dispatched) error("Failed to dispatch scroll gesture.")
        }

    actual suspend fun pressKey(key: String): Result<Unit> =
        Result.failure(UnsupportedOperationException("Key presses are not supported on Android."))

    actual suspend fun keyCombo(keys: List<String>): Result<Unit> =
        Result.failure(UnsupportedOperationException("Key combos are not supported on Android."))
}

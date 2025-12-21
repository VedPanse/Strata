/*
 * Strata - Kotlin Multiplatform client.
 * Copyright (c) 2025.
 */
package org.strata.perception

import android.graphics.Rect as AndroidRect
import android.view.accessibility.AccessibilityNodeInfo

data class AccessibilitySnapshot(
    val appContext: AppContextInfo?,
    val uiElements: List<UiElement>,
)

object AccessibilitySnapshotStore {
    @Volatile private var latest: AccessibilitySnapshot? = null

    fun update(snapshot: AccessibilitySnapshot) {
        latest = snapshot
    }

    fun get(): AccessibilitySnapshot? = latest
}

internal fun buildAccessibilitySnapshot(root: AccessibilityNodeInfo?): AccessibilitySnapshot {
    if (root == null) return AccessibilitySnapshot(null, emptyList())
    val elements = mutableListOf<UiElement>()
    collectUiElements(root, elements, limit = 300)
    val appContext =
        AppContextInfo(
            appName = root.packageName?.toString(),
            windowTitle = root.window?.title?.toString(),
            packageName = root.packageName?.toString(),
        )
    return AccessibilitySnapshot(appContext, elements)
}

private fun collectUiElements(
    node: AccessibilityNodeInfo?,
    out: MutableList<UiElement>,
    limit: Int,
) {
    if (node == null || out.size >= limit) return

    val bounds = AndroidRect()
    node.getBoundsInScreen(bounds)
    val label =
        node.text?.toString()?.takeIf { it.isNotBlank() }
            ?: node.contentDescription?.toString()?.takeIf { it.isNotBlank() }

    if (!label.isNullOrBlank()) {
        val className = node.className?.toString().orEmpty()
        val type =
            when {
                className.contains("Button", ignoreCase = true) -> UiElementType.BUTTON
                className.contains("EditText", ignoreCase = true) -> UiElementType.FIELD
                node.isClickable && label.contains("http", ignoreCase = true) -> UiElementType.LINK
                className.contains("Text", ignoreCase = true) -> UiElementType.TEXT
                else -> UiElementType.OTHER
            }
        out +=
            UiElement(
                type = type,
                label = label,
                bounds =
                    Rect(
                        left = bounds.left,
                        top = bounds.top,
                        right = bounds.right,
                        bottom = bounds.bottom,
                    ),
            )
    }

    for (i in 0 until node.childCount) {
        collectUiElements(node.getChild(i), out, limit)
        if (out.size >= limit) return
    }
}

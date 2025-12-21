/*
 * Strata - Kotlin Multiplatform client.
 * Copyright (c) 2025.
 */
package org.strata.perception

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

data class Rect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

enum class UiElementType {
    BUTTON,
    FIELD,
    LINK,
    TEXT,
    OTHER,
}

data class OcrBlock(
    val text: String,
    val bounds: Rect,
)

data class UiElement(
    val type: UiElementType,
    val label: String?,
    val bounds: Rect,
)

data class AppContextInfo(
    val appName: String?,
    val windowTitle: String?,
    val packageName: String? = null,
)

data class ScreenPerceptionResult(
    val capturedAtMillis: Long = Clock.System.now().toEpochMilliseconds(),
    val appContext: AppContextInfo?,
    val ocrBlocks: List<OcrBlock>,
    val uiElements: List<UiElement>,
    val visionSummary: String? = null,
    val frameDigest: String = "",
    val visionUpdatedAtMillis: Long? = null,
)

object ScreenPerceptionStore {
    private val _latest = MutableStateFlow<ScreenPerceptionResult?>(null)
    val latest: StateFlow<ScreenPerceptionResult?> = _latest

    fun setLatest(result: ScreenPerceptionResult) {
        _latest.value = result
    }
}

object ScreenPerceptionStreamState {
    @Volatile var lastDigest: String? = null
    @Volatile var lastVisionAtMillis: Long? = null
    @Volatile var lastVisionSummary: String? = null
}

object ScreenPerceptionDigest {
    fun compute(
        ocrBlocks: List<OcrBlock>,
        uiElements: List<UiElement>,
    ): String {
        val ocrLines =
            ocrBlocks
                .asSequence()
                .map { it.text.trim().lowercase() }
                .filter { it.isNotBlank() }
                .take(160)
                .toList()
        val uiLines =
            uiElements
                .asSequence()
                .mapNotNull { it.label?.trim()?.lowercase()?.takeIf { label -> label.isNotBlank() } }
                .take(80)
                .toList()
        val combined = (ocrLines + uiLines).joinToString("|")
        return combined.hashCode().toString()
    }
}

expect object ScreenPerceptionPlatform {
    suspend fun captureAndAnalyze(forceVision: Boolean = false): Result<ScreenPerceptionResult>

    fun startOverlay()

    fun stopOverlay()

    fun isOverlayActive(): Boolean
}

object ScreenPerception {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var streamJob: Job? = null

    suspend fun record(forceVision: Boolean = false): Result<ScreenPerceptionResult> =
        ScreenPerceptionPlatform
            .captureAndAnalyze(forceVision = forceVision)
            .onSuccess { ScreenPerceptionStore.setLatest(it) }

    fun latest(): ScreenPerceptionResult? = ScreenPerceptionStore.latest.value

    fun startOverlay() = ScreenPerceptionPlatform.startOverlay()

    fun stopOverlay() = ScreenPerceptionPlatform.stopOverlay()

    fun isOverlayActive(): Boolean = ScreenPerceptionPlatform.isOverlayActive()

    fun startStream(intervalMillis: Long = 1200L) {
        if (streamJob?.isActive == true) return
        streamJob =
            scope.launch {
                while (isActive) {
                    record()
                    delay(intervalMillis)
                }
            }
    }

    fun stopStream() {
        streamJob?.cancel()
        streamJob = null
    }
}

object ScreenPerceptionFormatter {
    fun formatForPrompt(result: ScreenPerceptionResult): String {
        val appLine =
            buildString {
                append("App: ").append(result.appContext?.appName ?: "Unknown")
                result.appContext?.windowTitle?.takeIf { it.isNotBlank() }?.let {
                    append(" (").append(it).append(")")
                }
                result.appContext?.packageName?.takeIf { it.isNotBlank() }?.let {
                    append(" [").append(it).append("]")
                }
            }

        val ocrLines =
            result.ocrBlocks
                .asSequence()
                .map { it.text.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .take(120)
                .toList()

        val uiLines =
            result.uiElements
                .asSequence()
                .mapNotNull { element ->
                    val label = element.label?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    "${element.type.name.lowercase()}: $label"
                }
                .distinct()
                .take(80)
                .toList()

        return buildString {
            append(appLine)
            append('\n')
            result.visionSummary?.takeIf { it.isNotBlank() }?.let { summary ->
                val updatedAt =
                    result.visionUpdatedAtMillis?.let { ts ->
                        val ageSec = ((Clock.System.now().toEpochMilliseconds() - ts) / 1000).coerceAtLeast(0)
                        " (updated ${ageSec}s ago)"
                    } ?: ""
                append("Vision summary").append(updatedAt).append(":\n")
                append(summary.trim())
                append('\n')
            }
            if (ocrLines.isNotEmpty()) {
                append("Visible text:\n")
                ocrLines.forEach { line ->
                    append("- ").append(line).append('\n')
                }
            } else {
                append("Visible text: none detected\n")
            }
            if (uiLines.isNotEmpty()) {
                append("UI elements:\n")
                uiLines.forEach { line ->
                    append("- ").append(line).append('\n')
                }
            } else {
                append("UI elements: none detected\n")
            }
        }.trimEnd()
    }
}

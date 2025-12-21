/*
 * Strata - Kotlin Multiplatform client.
 * Copyright (c) 2025.
 */
package org.strata.perception

import androidx.compose.runtime.mutableStateOf
import net.sourceforge.tess4j.ITessAPI
import net.sourceforge.tess4j.ITesseract
import net.sourceforge.tess4j.Tesseract
import net.sourceforge.tess4j.util.LoadLibs
import java.awt.Rectangle
import java.awt.Robot
import java.awt.Toolkit

object DesktopOverlayState {
    val visible = mutableStateOf(false)
}

actual object ScreenPerceptionPlatform {
    private const val MIN_VISION_INTERVAL_MS: Long = 60_000
    private const val FORCE_VISION_MIN_INTERVAL_MS: Long = 5_000

    actual suspend fun captureAndAnalyze(forceVision: Boolean): Result<ScreenPerceptionResult> =
        runCatching {
            val screenSize = Toolkit.getDefaultToolkit().screenSize
            val capture = Robot().createScreenCapture(Rectangle(screenSize))
            val words = runCatching { runOcr(capture) }.getOrDefault(emptyList())
            val ocrBlocks =
                words.mapNotNull { word ->
                    val text = word.text?.trim().orEmpty()
                    if (text.isBlank()) return@mapNotNull null
                    val box = word.boundingBox ?: return@mapNotNull null
                    OcrBlock(
                        text = text,
                        bounds =
                            Rect(
                                left = box.x,
                                top = box.y,
                                right = box.x + box.width,
                                bottom = box.y + box.height,
                            ),
                    )
                }

            val uiElements =
                ocrBlocks.map { block ->
                    UiElement(
                        type = guessElementType(block.text),
                        label = block.text,
                        bounds = block.bounds,
                    )
                }

            val digest = ScreenPerceptionDigest.compute(ocrBlocks, uiElements)
            val now = System.currentTimeMillis()
            val lastDigest = ScreenPerceptionStreamState.lastDigest
            val lastVisionAt = ScreenPerceptionStreamState.lastVisionAtMillis
            val allowVision =
                if (forceVision) {
                    lastVisionAt == null || now - lastVisionAt > FORCE_VISION_MIN_INTERVAL_MS
                } else {
                    digest != lastDigest &&
                        (lastVisionAt == null || now - lastVisionAt > MIN_VISION_INTERVAL_MS)
                }

            val visionSummary =
                if (allowVision) {
                    println("[ScreenPerception] Vision call allowed (force=$forceVision, digestChanged=${digest != lastDigest})")
                    ScreenVisionAi.summarize(capture).getOrNull()?.also { summary ->
                        ScreenPerceptionStreamState.lastVisionAtMillis = now
                        ScreenPerceptionStreamState.lastVisionSummary = summary
                    }
                } else {
                    println("[ScreenPerception] Vision call skipped (force=$forceVision, digestChanged=${digest != lastDigest})")
                    ScreenPerceptionStreamState.lastVisionSummary
                }

            ScreenPerceptionStreamState.lastDigest = digest

            ScreenPerceptionResult(
                appContext =
                    AppContextInfo(
                        appName = "Desktop",
                        windowTitle = null,
                        packageName = null,
                    ),
                ocrBlocks = ocrBlocks,
                uiElements = uiElements,
                visionSummary = visionSummary,
                frameDigest = digest,
                visionUpdatedAtMillis = ScreenPerceptionStreamState.lastVisionAtMillis,
            )
        }

    actual fun startOverlay() {
        DesktopOverlayState.visible.value = true
        ScreenPerception.startStream()
    }

    actual fun stopOverlay() {
        DesktopOverlayState.visible.value = false
        ScreenPerception.stopStream()
    }

    actual fun isOverlayActive(): Boolean = DesktopOverlayState.visible.value

    private fun runOcr(image: java.awt.image.BufferedImage): List<net.sourceforge.tess4j.Word> {
        val instance: ITesseract = Tesseract()
        val tessData = LoadLibs.extractTessResources("tessdata")
        instance.setDatapath(tessData.absolutePath)
        instance.setLanguage("eng")
        return instance.getWords(image, ITessAPI.TessPageIteratorLevel.RIL_WORD)
    }

    private fun guessElementType(text: String): UiElementType {
        val lowered = text.lowercase()
        return when {
            lowered.startsWith("http") || lowered.contains(".com") -> UiElementType.LINK
            lowered.endsWith(":") -> UiElementType.FIELD
            text.length <= 18 && text.all { it.isLetter() } && text == text.uppercase() -> UiElementType.BUTTON
            else -> UiElementType.TEXT
        }
    }
}

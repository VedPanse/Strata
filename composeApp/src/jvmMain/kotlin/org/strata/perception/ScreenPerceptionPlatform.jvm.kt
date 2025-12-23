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
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.ImageWriter
import javax.imageio.stream.MemoryCacheImageOutputStream

object DesktopOverlayState {
    val visible = mutableStateOf(false)
}

actual object ScreenPerceptionPlatform {
    private const val MIN_VISION_INTERVAL_MS: Long = 60_000
    private const val FORCE_VISION_MIN_INTERVAL_MS: Long = 5_000
    private const val MAX_DIMENSION = 1280
    private const val JPEG_QUALITY = 0.72f

    actual suspend fun captureAndAnalyze(forceVision: Boolean): Result<ScreenPerceptionResult> =
        runCatching {
            val screenSize = Toolkit.getDefaultToolkit().screenSize
            val capture = Robot().createScreenCapture(Rectangle(screenSize))
            val screenWidth = capture.width
            val screenHeight = capture.height
            val imageBase64 = encodeJpegBase64(capture)
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
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                imageBase64Jpeg = imageBase64,
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

    private fun encodeJpegBase64(image: java.awt.image.BufferedImage): String {
        val scaled = downscale(image, MAX_DIMENSION)
        val output = ByteArrayOutputStream()
        val writer: ImageWriter = ImageIO.getImageWritersByFormatName("jpeg").next()
        val params = writer.defaultWriteParam
        if (params.canWriteCompressed()) {
            params.compressionMode = ImageWriteParam.MODE_EXPLICIT
            params.compressionQuality = JPEG_QUALITY.coerceIn(0.1f, 0.95f)
        }
        writer.output = MemoryCacheImageOutputStream(output)
        writer.write(null, javax.imageio.IIOImage(scaled, null, null), params)
        writer.dispose()
        return Base64.getEncoder().encodeToString(output.toByteArray())
    }

    private fun downscale(
        image: java.awt.image.BufferedImage,
        maxDimension: Int,
    ): java.awt.image.BufferedImage {
        val width = image.width
        val height = image.height
        val maxSide = maxOf(width, height)
        if (maxSide <= maxDimension) return image
        val scale = maxDimension.toDouble() / maxSide.toDouble()
        val targetW = (width * scale).toInt().coerceAtLeast(1)
        val targetH = (height * scale).toInt().coerceAtLeast(1)
        val scaled = java.awt.image.BufferedImage(targetW, targetH, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g2 = scaled.createGraphics()
        g2.drawImage(image, 0, 0, targetW, targetH, null)
        g2.dispose()
        return scaled
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

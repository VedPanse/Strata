/*
 * Strata - Kotlin Multiplatform client.
 * Copyright (c) 2025.
 */
package org.strata.perception

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.strata.ai.JsonMapEncoder
import org.strata.ai.OpenAiSupport
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.ImageWriter
import javax.imageio.stream.MemoryCacheImageOutputStream

internal object ScreenVisionAi {
    private val http = HttpClient(CIO)
    private val json = Json { ignoreUnknownKeys = true }

    private const val MAX_DIMENSION = 1280
    private const val JPEG_QUALITY = 0.72f

    suspend fun summarize(image: BufferedImage): Result<String> =
        runCatching {
            val apiKey = OpenAiSupport.requireApiKey()
            val payload =
                mapOf(
                    "model" to "gpt-4o-mini",
                    "messages" to
                        listOf(
                            mapOf(
                                "role" to "user",
                                "content" to
                                    listOf(
                                        mapOf(
                                            "type" to "text",
                                            "text" to
                                                "Summarize the visible screen. Identify the app, key UI elements " +
                                                "(buttons, fields, links), and the main content. Keep it concise.",
                                        ),
                                        buildInlineImagePart(image),
                                    ),
                            ),
                        ),
                    "temperature" to 0.2,
                    "max_tokens" to 256,
                )
            val body = JsonMapEncoder.encodeToString(payload)
            val url = "https://api.openai.com/v1/chat/completions"
            val response =
                http.post(url) {
                    contentType(ContentType.Application.Json)
                    headers.append("Authorization", "Bearer $apiKey")
                    setBody(body)
                }
            val raw = response.bodyAsText()
            extractText(raw) ?: error("Empty response from OpenAI Vision")
        }

    private fun buildInlineImagePart(image: BufferedImage): Map<String, Any> {
        val scaled = downscale(image, MAX_DIMENSION)
        val jpegBytes = encodeJpeg(scaled, JPEG_QUALITY)
        return mapOf(
            "type" to "image_url",
            "image_url" to
                mapOf(
                    "url" to "data:image/jpeg;base64,${Base64.getEncoder().encodeToString(jpegBytes)}",
                ),
        )
    }

    private fun downscale(
        image: BufferedImage,
        maxDimension: Int,
    ): BufferedImage {
        val width = image.width
        val height = image.height
        val maxSide = maxOf(width, height)
        if (maxSide <= maxDimension) return image
        val scale = maxDimension.toDouble() / maxSide.toDouble()
        val targetW = (width * scale).toInt().coerceAtLeast(1)
        val targetH = (height * scale).toInt().coerceAtLeast(1)
        val scaled = BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB)
        val g2 = scaled.createGraphics()
        g2.drawImage(image, 0, 0, targetW, targetH, null)
        g2.dispose()
        return scaled
    }

    private fun encodeJpeg(
        image: BufferedImage,
        quality: Float,
    ): ByteArray {
        val output = ByteArrayOutputStream()
        val writer: ImageWriter = ImageIO.getImageWritersByFormatName("jpeg").next()
        val params = writer.defaultWriteParam
        if (params.canWriteCompressed()) {
            params.compressionMode = ImageWriteParam.MODE_EXPLICIT
            params.compressionQuality = quality.coerceIn(0.1f, 0.95f)
        }
        writer.output = MemoryCacheImageOutputStream(output)
        writer.write(null, javax.imageio.IIOImage(image, null, null), params)
        writer.dispose()
        return output.toByteArray()
    }

    private fun extractText(raw: String): String? =
        runCatching {
            val root = json.parseToJsonElement(raw).jsonObject
            val choices = root["choices"]?.jsonArray ?: return null
            val first = choices.firstOrNull()?.jsonObject ?: return null
            val message = first["message"]?.jsonObject ?: return null
            message["content"]?.jsonPrimitive?.content
        }.getOrNull()
}

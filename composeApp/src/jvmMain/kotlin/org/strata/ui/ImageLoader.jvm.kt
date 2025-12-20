/*
 * Strata - Kotlin Multiplatform client.
 * Copyright (c) 2025.
 */
package org.strata.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL

@Composable
actual fun rememberNetworkImageBitmap(url: String): ImageBitmap? {
    val state = remember(url) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(url) {
        val bmp =
            withContext(Dispatchers.IO) {
                try {
                    val connection =
                        (URL(url).openConnection() as HttpURLConnection).apply {
                            connectTimeout = 5000
                            readTimeout = 5000
                            instanceFollowRedirects = true
                        }
                    connection.inputStream.use { input ->
                        BufferedInputStream(input).use { bis ->
                            val bytes = bis.readBytes()
                            val skiaImage = Image.makeFromEncoded(bytes)
                            skiaImage.toComposeImageBitmap()
                        }
                    }
                } catch (_: Throwable) {
                    null
                }
            }
        state.value = bmp
    }

    return state.value
}

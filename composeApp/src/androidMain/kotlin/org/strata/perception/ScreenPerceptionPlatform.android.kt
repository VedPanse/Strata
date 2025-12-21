/*
 * Strata - Kotlin Multiplatform client.
 * Copyright (c) 2025.
 */
package org.strata.perception

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.WindowManager
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import org.strata.platform.AppContext
import kotlin.coroutines.resume

actual object ScreenPerceptionPlatform {
    private var projectionIntent: Intent? = null
    private var projection: MediaProjection? = null

    actual suspend fun captureAndAnalyze(forceVision: Boolean): Result<ScreenPerceptionResult> =
        runCatching {
            val context = AppContext.context
            val activity = AppContext.activity?.get()
            val projection = ensureProjection(context, activity)
                ?: error("Screen capture permission denied or unavailable.")

            val bitmap = captureBitmap(context, projection)
                ?: error("Failed to capture screen.")

            val ocrBlocks = recognizeText(bitmap)
            val accessibilitySnapshot = AccessibilitySnapshotStore.get()
            val uiElements = accessibilitySnapshot?.uiElements ?: emptyList()
            val appContext = accessibilitySnapshot?.appContext
            val digest = ScreenPerceptionDigest.compute(ocrBlocks, uiElements)
            ScreenPerceptionStreamState.lastDigest = digest

            ScreenPerceptionResult(
                appContext = appContext,
                ocrBlocks = ocrBlocks,
                uiElements = uiElements,
                visionSummary = null,
                frameDigest = digest,
                visionUpdatedAtMillis = ScreenPerceptionStreamState.lastVisionAtMillis,
            )
        }

    actual fun startOverlay() {
        val context = AppContext.context
        if (!canDrawOverlays(context)) {
            requestOverlayPermission(context)
            return
        }
        ScreenOverlayService.show(context)
    }

    actual fun stopOverlay() {
        ScreenOverlayService.hide(AppContext.context)
    }

    actual fun isOverlayActive(): Boolean = ScreenOverlayService.isShowing

    private suspend fun ensureProjection(
        context: Context,
        activity: Activity?,
    ): MediaProjection? {
        projection?.let { return it }
        if (projectionIntent == null) {
            val active = activity ?: return null
            projectionIntent = ScreenCapturePermissionManager.request(active).await()
        }
        val intent = projectionIntent ?: return null
        val manager =
            context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = manager.getMediaProjection(Activity.RESULT_OK, intent)
        return projection
    }

    private suspend fun captureBitmap(
        context: Context,
        projection: MediaProjection,
    ): Bitmap? =
        withContext(Dispatchers.Default) {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)

            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val density = metrics.densityDpi
            val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            val virtualDisplay =
                projection.createVirtualDisplay(
                    "StrataScreenCapture",
                    width,
                    height,
                    density,
                    0,
                    imageReader.surface,
                    null,
                    null,
                )

            var image = imageReader.acquireLatestImage()
            var attempts = 0
            while (image == null && attempts < 10) {
                delay(60)
                image = imageReader.acquireLatestImage()
                attempts++
            }

            if (image == null) {
                virtualDisplay.release()
                imageReader.close()
                return@withContext null
            }

            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * width
            val bmp =
                Bitmap.createBitmap(
                    width + rowPadding / pixelStride,
                    height,
                    Bitmap.Config.ARGB_8888,
                )
            bmp.copyPixelsFromBuffer(buffer)
            image.close()
            virtualDisplay.release()
            imageReader.close()

            Bitmap.createBitmap(bmp, 0, 0, width, height)
        }

    private suspend fun recognizeText(bitmap: Bitmap): List<OcrBlock> {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val image = InputImage.fromBitmap(bitmap, 0)
        val result = recognizer.process(image).awaitResult()
        val blocks = mutableListOf<OcrBlock>()
        result?.textBlocks?.forEach { block ->
            val bounds = block.boundingBox ?: return@forEach
            blocks +=
                OcrBlock(
                    text = block.text.orEmpty(),
                    bounds =
                        Rect(
                            left = bounds.left,
                            top = bounds.top,
                            right = bounds.right,
                            bottom = bounds.bottom,
                        ),
                )
        }
        return blocks
    }

    private fun canDrawOverlays(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    private fun requestOverlayPermission(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}

private suspend fun <T> com.google.android.gms.tasks.Task<T>.awaitResult(): T? =
    suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { continuation.resume(it) }
        addOnFailureListener { continuation.resume(null) }
        addOnCanceledListener { continuation.resume(null) }
    }

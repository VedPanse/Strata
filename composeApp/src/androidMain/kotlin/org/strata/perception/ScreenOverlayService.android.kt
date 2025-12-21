/*
 * Strata - Kotlin Multiplatform client.
 * Copyright (c) 2025.
 */
package org.strata.perception

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import org.strata.ui.ScreenOverlayPrompt

class ScreenOverlayService : LifecycleService() {
    companion object {
        private const val CHANNEL_ID = "strata_screen_overlay"
        private const val NOTIFICATION_ID = 4123
        private const val ACTION_SHOW = "org.strata.action.SHOW_OVERLAY"
        private const val ACTION_HIDE = "org.strata.action.HIDE_OVERLAY"
        @Volatile var isShowing: Boolean = false

        fun show(context: Context) {
            val intent = Intent(context, ScreenOverlayService::class.java).setAction(ACTION_SHOW)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun hide(context: Context) {
            val intent = Intent(context, ScreenOverlayService::class.java).setAction(ACTION_HIDE)
            context.startService(intent)
        }
    }

    private lateinit var windowManager: WindowManager
    private var overlayView: ComposeView? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Service.WINDOW_SERVICE) as WindowManager
        ensureNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> showOverlay()
            ACTION_HIDE -> stopOverlay()
        }
        return Service.START_STICKY
    }

    override fun onDestroy() {
        stopOverlay()
        super.onDestroy()
    }

    private fun showOverlay() {
        if (overlayView != null) return
        ScreenPerception.startStream()
        val composeView = ComposeView(this)
        composeView.setViewTreeLifecycleOwner(this)
        composeView.setViewTreeSavedStateRegistryOwner(this)
        composeView.setContent {
            MaterialTheme {
                ScreenOverlayPrompt(
                    onClose = { stopOverlay() },
                    onBeforeSend = { ScreenPerception.record(forceVision = true) },
                )
            }
        }

        val layoutType =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

        val params =
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                x = 40
                y = 80
            }

        windowManager.addView(composeView, params)
        overlayView = composeView
        isShowing = true
    }

    private fun stopOverlay() {
        overlayView?.let { view ->
            windowManager.removeView(view)
        }
        overlayView = null
        isShowing = false
        ScreenPerception.stopStream()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                "Strata Screen Overlay",
                NotificationManager.IMPORTANCE_MIN,
            )
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Strata screen mode")
            .setContentText("Overlay active")
            .setOngoing(true)
            .build()
}

/*
 * Strata - Kotlin Multiplatform client.
 * Copyright (c) 2025.
 */
package org.strata.perception

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import kotlinx.coroutines.CompletableDeferred

object ScreenCapturePermissionManager {
    private const val REQUEST_CODE = 9214
    private var pending: CompletableDeferred<Intent?>? = null

    fun request(activity: Activity): CompletableDeferred<Intent?> {
        val projectionManager =
            activity.getSystemService(Activity.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val intent = projectionManager.createScreenCaptureIntent()
        val deferred = CompletableDeferred<Intent?>()
        pending = deferred
        activity.startActivityForResult(intent, REQUEST_CODE)
        return deferred
    }

    fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
    ) {
        if (requestCode != REQUEST_CODE) return
        val deferred = pending ?: return
        pending = null
        if (resultCode == Activity.RESULT_OK) {
            deferred.complete(data)
        } else {
            deferred.complete(null)
        }
    }
}

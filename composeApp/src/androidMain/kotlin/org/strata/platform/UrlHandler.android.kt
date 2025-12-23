/*
 * Strata - Kotlin Multiplatform client.
 * Copyright (c) 2025.
 */
package org.strata.platform

import android.content.Intent
import android.net.Uri
import org.strata.platform.AppContext.context

actual object UrlHandler {
    actual suspend fun openUrl(url: String): Result<Unit> =
        runCatching {
            val normalized = if (url.startsWith("http")) url else "https://$url"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(normalized))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
}

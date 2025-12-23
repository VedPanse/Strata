/*
 * Strata - Kotlin Multiplatform client.
 * Copyright (c) 2025.
 */
package org.strata.platform

import android.content.Intent
import android.content.pm.PackageManager
import org.strata.platform.AppContext.context

actual object AppLauncher {
    actual suspend fun openApp(
        appName: String?,
        packageName: String?,
    ): Result<Unit> =
        runCatching {
            val pm = context.packageManager
            val pkg =
                packageName?.trim()?.takeIf { it.isNotBlank() }
                    ?: resolvePackageByName(pm, appName)
                    ?: error("App not found.")
            println("[AppLauncher] Android launching package=$pkg name=${appName ?: ""}")
            val launchIntent = pm.getLaunchIntentForPackage(pkg) ?: error("App not launchable.")
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
        }

    private fun resolvePackageByName(
        pm: PackageManager,
        appName: String?,
    ): String? {
        val query = appName?.trim()?.lowercase().orEmpty()
        if (query.isBlank()) return null
        val apps =
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .asSequence()
                .mapNotNull { app ->
                    val label = pm.getApplicationLabel(app).toString()
                    if (label.lowercase() == query) app.packageName else null
                }
                .toList()
        return apps.firstOrNull()
    }
}

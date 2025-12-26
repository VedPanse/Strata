/*
 * Strata - Kotlin Multiplatform client.
 * Copyright (c) 2025.
 */
package org.strata

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import org.strata.auth.GoogleAuth
import org.strata.perception.ScreenCapturePermissionManager
import java.lang.ref.WeakReference

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Initialize a global app context for common storage
        org.strata.platform.AppContext.context = applicationContext
        org.strata.platform.AppContext.activity = WeakReference(this)

        setContent {
            App()
        }
    }

    override fun onResume() {
        super.onResume()
        org.strata.platform.AppContext.activity = WeakReference(this)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: android.content.Intent?,
    ) {
        super.onActivityResult(requestCode, resultCode, data)
        ScreenCapturePermissionManager.onActivityResult(requestCode, resultCode, data)
        GoogleAuth.onActivityResult(requestCode, resultCode, data)
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}

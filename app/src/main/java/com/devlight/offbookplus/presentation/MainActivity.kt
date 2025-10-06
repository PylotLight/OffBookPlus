/* While this template provides a good starting point for using Wear Compose, you can always
 * take a look at https://github.com/android/wear-os-samples/tree/main/ComposeStarter to find the
 * most up to date changes to the libraries and their usages.
 */

package com.devlight.offbookplus.presentation

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.wear.compose.material3.Text
import com.devlight.offbookplus.ui.WearApp
import com.google.android.horologist.compose.layout.AppScaffold
import com.google.android.horologist.compose.layout.ResponsiveTimeText

/**
 * The main activity for the Wear OS application.
 * Handles the runtime permission for accessing audio files.
 */
class MainActivity : ComponentActivity() {

    // Permission handling logic
    private val mediaPermission =
        Manifest.permission.READ_MEDIA_AUDIO

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (!isGranted) {
            // Handle permanent denial (e.g., show a persistent message)
        }
        // Force a re-evaluation of the content state
        setContent(null) { MainContent() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initial check and request if needed
        if (!isPermissionGranted()) {
            requestPermissionLauncher.launch(mediaPermission)
        }

        setContent { MainContent() }
    }

    @Composable
    private fun MainContent() {
        if (isPermissionGranted()) {
            AppScaffold(timeText = { ResponsiveTimeText() }) { // <-- Horologist Scaffold
                WearApp()
            }
        } else {
            // Permission UI to request again
            PermissionHost(onRetry = {
                requestPermissionLauncher.launch(mediaPermission)
            })
        }
    }

    private fun isPermissionGranted(): Boolean {
        return ContextCompat.checkSelfPermission(this, mediaPermission) == PackageManager.PERMISSION_GRANTED
    }
}

// Simple Composable for when permission is denied
@Composable
private fun PermissionHost(onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Media Permission Required.\nTap to Retry.", modifier = Modifier.align(Alignment.Center))
        // In a real app, this Box would have a Button or be clickable
    }
}
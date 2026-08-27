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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.wear.compose.material3.Text
import com.devlight.offbookplus.playback.PlaybackContract
import com.devlight.offbookplus.ui.WearApp
import com.google.android.horologist.compose.layout.AppScaffold
import com.google.android.horologist.compose.layout.ResponsiveTimeText

/**
 * The main activity for the Wear OS application.
 * Handles the runtime permissions for accessing audio files and posting
 * media notifications (the swipe-down "Now Playing" shade controls).
 */
class MainActivity : ComponentActivity() {

    private val mediaPermission =
        Manifest.permission.READ_MEDIA_AUDIO

    private val notificationsPermission =
        Manifest.permission.POST_NOTIFICATIONS

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (!isGranted) {
        }
        maybeRequestNotifications()
        setContent(null) { MainContent() }
    }

    private val notificationsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Media playback works without it; the shade controls just stay hidden.
        setContent(null) { MainContent() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!isPermissionGranted(mediaPermission)) {
            requestPermissionLauncher.launch(mediaPermission)
        } else {
            maybeRequestNotifications()
        }

        setContent { MainContent() }
    }

    private fun maybeRequestNotifications() {
        if (!isPermissionGranted(notificationsPermission)) {
            notificationsPermissionLauncher.launch(notificationsPermission)
        }
    }

    @Composable
    private fun MainContent() {
        if (isPermissionGranted(mediaPermission)) {
            // Set when launched from the system media notification: go straight to the player.
            val startAtPlayer = remember {
                intent?.getBooleanExtra(PlaybackContract.EXTRA_OPEN_PLAYER, false) == true
            }
            AppScaffold(timeText = { ResponsiveTimeText() }) {
                WearApp(startAtPlayer = startAtPlayer)
            }
        } else {
            PermissionHost(onRetry = {
                requestPermissionLauncher.launch(mediaPermission)
            })
        }
    }

    private fun isPermissionGranted(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }
}

@Composable
private fun PermissionHost(onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Media Permission Required.\nTap to Retry.", modifier = Modifier.align(Alignment.Center))
    }
}

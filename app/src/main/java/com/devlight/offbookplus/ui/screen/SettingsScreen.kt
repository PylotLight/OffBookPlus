package com.devlight.offbookplus.ui.screen

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Update
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.Text
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.MaterialTheme
import com.devlight.offbookplus.ui.NavRoutes
import com.devlight.offbookplus.ui.viewmodel.LibraryViewModel
import com.devlight.offbookplus.ui.viewmodel.UpdateStatus

@Composable
fun SettingsScreen(
    viewModel: LibraryViewModel,
    onNavigate: (String) -> Unit
) {
    val updateStatus = viewModel.updateStatus.collectAsState().value
    val downloadProgress = viewModel.downloadProgress.collectAsState().value
    val context = LocalContext.current
    val currentVersion =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"
    ScalingLazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 4.dp)
    ) {
        item {
            Text(
                "Settings",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
            )
        }
        item {
            Text(
                "Version: $currentVersion",
                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
            )
        }
        item {
            Chip(
                onClick = { viewModel.forceFullRescan() },
                label = { Text("Force Full Rescan") },
                secondaryLabel = { Text("Rescan all media folders.") },
                icon = { Icon(imageVector = Icons.Default.Refresh, contentDescription = "Rescan") }
            )
        }
        item {
            val statusText = when (updateStatus) {
                UpdateStatus.IDLE -> "Check for App Update"
                UpdateStatus.CHECKING -> "Checking..."
                UpdateStatus.UPDATE_AVAILABLE -> "Update Available! (Tap to download)"
                UpdateStatus.NO_UPDATE -> "You are on the latest version."
                UpdateStatus.ERROR -> "Error checking for update."
                UpdateStatus.DOWNLOADING -> "Downloading Update..."
                UpdateStatus.DOWNLOAD_COMPLETE -> "Installation Ready (Tap)"
            }
            Chip(
                onClick = {
                    when (updateStatus) {
                        UpdateStatus.UPDATE_AVAILABLE, UpdateStatus.DOWNLOAD_COMPLETE -> viewModel.downloadAndInstallUpdate()
                        UpdateStatus.IDLE, UpdateStatus.ERROR, UpdateStatus.NO_UPDATE -> viewModel.checkForUpdate()
                        else -> {}
                    }
                },
                label = { Text(statusText) },
                secondaryLabel = when {
                    updateStatus == UpdateStatus.DOWNLOADING -> {
                        {
                            Row {
                                CircularProgressIndicator(Modifier.padding(end = 6.dp))
                                Text("$downloadProgress%")
                            }
                        }
                    }
                    updateStatus == UpdateStatus.CHECKING -> {
                        { CircularProgressIndicator(Modifier.padding(end = 8.dp)) }
                    }
                    else -> null
                },
                icon = {
                    Icon(
                        imageVector = Icons.Default.Update,
                        contentDescription = "Check for Update"
                    )
                },
                enabled = updateStatus != UpdateStatus.CHECKING
            )
        }
        item {
            Chip(
                onClick = { onNavigate(NavRoutes.HISTORY_ROUTE) },
                label = { Text("Play History") },
                secondaryLabel = { Text("Recently played media.") },
                icon = { Icon(imageVector = Icons.Default.History, contentDescription = "Play History") }
            )
        }
        item {
            Chip(
                onClick = { onNavigate(NavRoutes.UPDATES_ROUTE) },
                label = { Text("Updates") },
                secondaryLabel = { Text("Release notes and downloads.") },
                icon = { Icon(imageVector = Icons.Default.Update, contentDescription = "Updates") }
            )
        }
    }
}
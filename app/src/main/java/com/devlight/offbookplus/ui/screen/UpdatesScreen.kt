package com.devlight.offbookplus.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Update
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.Text
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.MaterialTheme
import com.devlight.offbookplus.data.GitHubRelease
import com.devlight.offbookplus.ui.viewmodel.ReleaseListStatus
import com.devlight.offbookplus.ui.viewmodel.UpdateStatus
import com.devlight.offbookplus.ui.viewmodel.UpdatesViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun UpdatesScreen(
    viewModel: UpdatesViewModel
) {
    val releases by viewModel.releases.collectAsState()
    val listStatus by viewModel.listStatus.collectAsState()
    val downloadStatus by viewModel.downloadStatus.collectAsState()
    val progress by viewModel.downloadProgress.collectAsState()
    val activeDownloadUrl by viewModel.activeDownloadUrl.collectAsState()
    val activeCompleteUrl by viewModel.activeCompleteUrl.collectAsState()

    LaunchedEffect(Unit) {
        if (listStatus == ReleaseListStatus.IDLE) viewModel.loadReleases()
    }

    ScalingLazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            Text(
                "Updates",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
            )
        }
        when (listStatus) {
            ReleaseListStatus.IDLE, ReleaseListStatus.LOADING -> {
                item {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.padding(16.dp))
                    }
                }
            }
            ReleaseListStatus.ERROR -> {
                item {
                    Text(
                        "Failed to load releases.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
                item {
                    Chip(
                        onClick = { viewModel.loadReleases() },
                        label = { Text("Retry") },
                        icon = { Icon(Icons.Default.Refresh, contentDescription = "Retry") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            ReleaseListStatus.LOADED -> {
                items(releases) { release ->
                    val isInstalled = release.version == viewModel.currentVersion
                    val isDownloading = activeDownloadUrl == release.apkAsset?.browserDownloadUrl &&
                        downloadStatus == UpdateStatus.DOWNLOADING
                    val isDownloaded = activeCompleteUrl == release.apkAsset?.browserDownloadUrl

                    ReleaseCard(
                        release = release,
                        isLatest = releases.firstOrNull()?.tagName == release.tagName,
                        isInstalled = isInstalled,
                        isDownloading = isDownloading,
                        isDownloaded = isDownloaded,
                        progress = progress,
                        onClick = { viewModel.downloadOrInstall(release) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ReleaseCard(
    release: GitHubRelease,
    isLatest: Boolean,
    isInstalled: Boolean,
    isDownloading: Boolean,
    isDownloaded: Boolean,
    progress: Int,
    onClick: () -> Unit
) {
    val secondaryLabel = when {
        isInstalled -> "Installed"
        isDownloading -> "Downloading $progress%"
        isDownloaded -> "Tap to Install"
        isLatest -> "Latest · Download"
        else -> "Download"
    }
    val badges = buildList {
        if (release.prerelease) add("Pre-release")
        add(formatDate(release.publishedAt))
    }

    Chip(
        onClick = onClick,
        label = {
            Text("v${release.version}" + if (badges.isNotEmpty()) " · ${badges.joinToString(" · ")}" else "")
        },
        secondaryLabel = { Text(secondaryLabel) },
        icon = { Icon(Icons.Default.Update, contentDescription = null) },
        enabled = !isInstalled,
        modifier = Modifier.fillMaxWidth()
    )
}

private val dateFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")

private fun formatDate(publishedAt: String): String {
    return try {
        Instant.parse(publishedAt).atZone(ZoneId.systemDefault()).format(dateFormatter)
    } catch (e: Exception) {
        ""
    }
}
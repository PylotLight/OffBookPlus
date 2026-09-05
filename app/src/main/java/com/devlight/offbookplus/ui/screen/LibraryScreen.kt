package com.devlight.offbookplus.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.Text
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.MaterialTheme
import com.devlight.offbookplus.data.PlaybackProgressEntity
import com.devlight.offbookplus.model.MediaItem
import com.devlight.offbookplus.model.MediaType
import com.devlight.offbookplus.ui.viewmodel.LibraryViewModel
import com.devlight.offbookplus.ui.viewmodel.PlaybackViewModel
import java.util.concurrent.TimeUnit

@Composable
fun LibraryScreen(
    mediaType: MediaType,
    onItemClick: (mediaId: String, mediaType: MediaType) -> Unit,
    onNavigateToPlayer: () -> Unit = {},
    libraryViewModel: LibraryViewModel,
    playbackViewModel: PlaybackViewModel
) {
    val mediaItems by libraryViewModel.uiState.collectAsState()
    val progressByPlaylist by libraryViewModel.progressByPlaylist.collectAsState()
    val playbackState by playbackViewModel.playbackState.collectAsState()
    val savedQueues by playbackViewModel.savedQueues.collectAsState()
    val hasSavedQueue = savedQueues.any { it.mediaType == mediaType }

    LaunchedEffect(mediaType) {
        libraryViewModel.checkAndLoadMedia(mediaType)
        playbackViewModel.refreshSavedQueues()
    }

    ScalingLazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Text(mediaType.title, style = MaterialTheme.typography.titleMedium)
        }
        if (hasSavedQueue) {
            item {
                Chip(
                    onClick = {
                        playbackViewModel.resumeLastQueue(mediaType)
                        onNavigateToPlayer()
                    },
                    label = { Text("Resume", maxLines = 1) },
                    icon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                    colors = ChipDefaults.primaryChipColors(),
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        if (mediaType == MediaType.MUSIC && mediaItems.isNotEmpty()) {
            item {
                Chip(
                    onClick = {
                        playbackViewModel.shuffleAllMusic()
                        onNavigateToPlayer()
                    },
                    label = { Text("Shuffle All", maxLines = 1) },
                    icon = { Icon(Icons.Filled.Shuffle, contentDescription = null) },
                    colors = ChipDefaults.primaryChipColors(),
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        if (mediaItems.isEmpty()) {
            item {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No ${mediaType.title.lowercase()} found.")
                }
            }
        } else {
            items(mediaItems) { item ->
                // Music shares one queue across all tracks, so per-card progress only
                // makes sense for audiobooks/podcasts (one playlist per folder).
                val progress = if (item.mediaType == MediaType.MUSIC) {
                    null
                } else {
                    progressByPlaylist[item.playlistId]
                }
                MediaItemCard(
                    item = item,
                    isCurrent = playbackState.mediaId == item.id,
                    isPlaying = playbackState.isPlaying,
                    progress = progress,
                    onClick = { onItemClick(item.id, item.mediaType) }
                )
            }
        }
    }
}

@Composable
private fun MediaItemCard(
    item: MediaItem,
    isCurrent: Boolean,
    isPlaying: Boolean,
    progress: PlaybackProgressEntity?,
    onClick: () -> Unit
) {
    Card(onClick = onClick) {
        Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(item.author, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (isCurrent) {
            Text(
                text = if (isPlaying) "Now playing" else "Paused",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        } else if (item.mediaType == MediaType.AUDIOBOOKS && progress != null && progress.playbackPositionMs > 0) {
            Text(
                text = "Played ${formatTime(progress.playbackPositionMs)} · ${relativeTime(progress.lastUpdatedTimestamp)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

private fun formatTime(ms: Long): String {
    if (ms < 0) return "00:00"
    val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(ms)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) String.format("%d:%02d:%02d", hours, minutes, seconds) else String.format(
        "%02d:%02d",
        minutes,
        seconds
    )
}

private fun relativeTime(timestampMs: Long): String {
    val diff = System.currentTimeMillis() - timestampMs
    val minutes = TimeUnit.MILLISECONDS.toMinutes(diff)
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        minutes < 60 * 24 -> "${minutes / 60}h ago"
        else -> "${minutes / (60 * 24)}d ago"
    }
}

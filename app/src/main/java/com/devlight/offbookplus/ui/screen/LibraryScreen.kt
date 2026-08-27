package com.devlight.offbookplus.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.Text
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.MaterialTheme
import com.devlight.offbookplus.model.MediaItem
import com.devlight.offbookplus.model.MediaType
import com.devlight.offbookplus.ui.viewmodel.LibraryViewModel
import com.devlight.offbookplus.ui.viewmodel.PlaybackViewModel

@Composable
fun LibraryScreen(
    mediaType: MediaType,
    onItemClick: (mediaId: String, mediaType: MediaType) -> Unit,
    libraryViewModel: LibraryViewModel,
    playbackViewModel: PlaybackViewModel
) {
    val mediaItems by libraryViewModel.uiState.collectAsState()

    LaunchedEffect(mediaType) {
        libraryViewModel.checkAndLoadMedia(mediaType)
    }

    ScalingLazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            NowPlayingBar(
                playbackViewModel = playbackViewModel,
                onOpenNowPlaying = {
                    onItemClick(playbackViewModel.playbackState.value.mediaId, playbackViewModel.playbackState.value.mediaType)
                }
            )
        }
        item {
            Text(mediaType.title, style = MaterialTheme.typography.titleMedium)
        }
        if (mediaItems.isEmpty()) {
            item {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No ${mediaType.title.lowercase()} found.")
                }
            }
        } else {
            items(mediaItems) { item ->
                // Single source of truth for playback is PlayerScreen's
                // LaunchedEffect; firing playMediaItem here as well caused
                // double COMMAND_LOAD_MEDIA_AND_PLAY and raced with the
                // still-loading playlist (first-item wrong-track bug).
                MediaItemCard(item = item, onClick = {
                    onItemClick(item.id, item.mediaType)
                })
            }
        }
    }
}

@Composable
private fun MediaItemCard(item: MediaItem, onClick: () -> Unit) {
    Card(onClick = onClick) {
        Text(item.title, style = MaterialTheme.typography.titleSmall)
        Text(item.author, style = MaterialTheme.typography.bodySmall)
    }
}
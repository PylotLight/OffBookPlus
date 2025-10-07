package com.devlight.offbookplus.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.Text
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.MaterialTheme
import com.devlight.offbookplus.model.MediaItem
import com.devlight.offbookplus.model.MediaType
import com.devlight.offbookplus.ui.viewmodel.LibraryViewModel
import com.devlight.offbookplus.ui.viewmodel.PlaybackViewModel
import kotlinx.coroutines.launch

@Composable
fun LibraryScreen(
    mediaType: MediaType,
    onItemClick: (mediaId: String, mediaType: MediaType) -> Unit,
    libraryViewModel: LibraryViewModel,
    playbackViewModel: PlaybackViewModel = viewModel()
) {
    val mediaItems by libraryViewModel.uiState.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(mediaType) {
        libraryViewModel.loadMedia(mediaType)
    }

    ScalingLazyColumn(modifier = Modifier.fillMaxSize()) {
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
                MediaItemCard(item = item, onClick = {
                    coroutineScope.launch {
                        playbackViewModel.playMediaItem(item.id, item.mediaType)
                    }
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
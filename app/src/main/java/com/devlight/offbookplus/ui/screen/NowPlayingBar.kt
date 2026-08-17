package com.devlight.offbookplus.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.Text
import androidx.wear.compose.material3.MaterialTheme
import com.devlight.offbookplus.ui.viewmodel.PlaybackViewModel

/**
 * Compact "Now Playing" affordance shown at the top of Home/Library screens while a media
 * session is active. Tapping it returns the user straight to the current player screen
 * without stopping or restarting playback.
 */
@Composable
fun NowPlayingBar(
    playbackViewModel: PlaybackViewModel,
    onOpenNowPlaying: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by playbackViewModel.playbackState.collectAsState()
    if (state.mediaId.isBlank()) return

    Box(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp)) {
            Text(
                text = "Now Playing",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
            )
            Chip(
                onClick = onOpenNowPlaying,
                label = {
                    Text(
                        text = state.currentChapterTitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                secondaryLabel = {
                    Text(
                        text = if (state.isPlaying) "Playing" else "Paused",
                        style = MaterialTheme.typography.labelSmall
                    )
                },
                icon = {
                    Icon(
                        imageVector = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = null
                    )
                },
                colors = ChipDefaults.primaryChipColors(),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

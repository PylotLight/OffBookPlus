@file:OptIn(ExperimentalFoundationApi::class)

package com.devlight.offbookplus.ui.screen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material3.LinearProgressIndicator
import androidx.wear.compose.material3.MaterialTheme
import com.devlight.offbookplus.ui.viewmodel.PlaybackViewModel
import java.util.concurrent.TimeUnit

@Composable
fun PlayerScreen(
    bookId: String?,
    onBack: () -> Unit,
    viewModel: PlaybackViewModel = viewModel()
) {
    val state by viewModel.playbackState.collectAsState()

    LaunchedEffect(bookId) {
        if (bookId != null) {
            viewModel.playBookFromLibrary(bookId)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        TimeText()
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = state.currentChapterTitle,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { state.progress },
                modifier = Modifier.fillMaxWidth(0.9f)
            )
            Text(
                text = "${formatTime(state.currentPositionMs)} / ${formatTime(state.durationMs)}",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 4.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))

            // Main Controls (center row)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // --- THE DEFINITIVE FIX: Build the button from low-level components ---
                GestureButton(
                    onClick = { viewModel.seekToPosition(state.currentPositionMs - 15000) },
                    onDoubleClick = { viewModel.seekToPosition(state.currentPositionMs - 60000) },
                    onLongClick = { viewModel.seekToPosition(state.currentPositionMs - 900000) },
                    enabled = state.isReady
                ) {
                    Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Rewind")
                }

                Spacer(modifier = Modifier.width(16.dp))

                // Play/Pause Button remains a standard Button as it only needs a single click
                Button(
                    onClick = { if (state.isPlaying) viewModel.pause() else viewModel.play() },
                    enabled = state.isReady,
                    modifier = Modifier.size(ButtonDefaults.LargeButtonSize)
                ) {
                    Icon(imageVector = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = "Play/Pause", modifier = Modifier.size(ButtonDefaults.LargeIconSize))
                }

                Spacer(modifier = Modifier.width(16.dp))

                // Forward Button with Gestures
                GestureButton(
                    onClick = { viewModel.seekToPosition(state.currentPositionMs + 30000) },
                    onDoubleClick = { viewModel.seekToPosition(state.currentPositionMs + 60000) },
                    onLongClick = { viewModel.seekToPosition(state.currentPositionMs + 900000) },
                    enabled = state.isReady
                ) {
                    Icon(imageVector = Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Forward")
                }
            }

            // Chapter Controls (bottom row, smaller)
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                Button(onClick = { viewModel.skipToPreviousChapter() }, enabled = state.isPreviousChapterAvailable, modifier = Modifier.size(ButtonDefaults.SmallButtonSize)) {
                    Icon(imageVector = Icons.Default.SkipPrevious, contentDescription = "Previous Chapter")
                }
                Spacer(modifier = Modifier.width(80.dp))
                Button(onClick = { viewModel.skipToNextChapter() }, enabled = state.isNextChapterAvailable, modifier = Modifier.size(ButtonDefaults.SmallButtonSize)) {
                    Icon(imageVector = Icons.Default.SkipNext, contentDescription = "Next Chapter")
                }
            }
        }
    }
}

@Composable
private fun GestureButton(
    onClick: () -> Unit,
    onDoubleClick: () -> Unit,
    onLongClick: () -> Unit,
    enabled: Boolean,
    content: @Composable () -> Unit
) {
    val backgroundColor = if (enabled) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(ButtonDefaults.DefaultButtonSize)
            .clip(CircleShape)
            .background(backgroundColor)
            .combinedClickable(
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
                onDoubleClick = onDoubleClick,
                onLongClick = onLongClick
            )
    ) {
        content()
    }
}


private fun formatTime(ms: Long): String {
    if (ms < 0) return "00:00"
    val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(ms)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) String.format("%d:%02d:%02d", hours, minutes, seconds) else String.format("%02d:%02d", minutes, seconds)
}
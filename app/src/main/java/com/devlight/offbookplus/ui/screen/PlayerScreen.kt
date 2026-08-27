@file:OptIn(ExperimentalFoundationApi::class, ExperimentalHorologistApi::class)

package com.devlight.offbookplus.ui.screen

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Replay30
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.Player
import androidx.wear.compose.foundation.requestFocusOnHierarchyActive
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.MaterialTheme
import com.google.android.horologist.annotations.ExperimentalHorologistApi
import com.google.android.horologist.media.ui.components.PlayPauseButton
import com.devlight.offbookplus.ui.viewmodel.PlaybackViewModel
import java.text.DecimalFormat
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

// Rotary crown/bezel seek sensitivity: ms of audio per pixel of rotary delta.
private const val SEEK_MS_PER_ROTARY_PX = 75L
private const val VOLUME_STEPS_PER_ROTARY_PX = 0.12f
private val CornerButtonSize = 42.dp
private val CornerIconSize = 20.dp
private val CenterButtonSize = 54.dp
private val SpeedSteps = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)

@Composable
fun PlayerScreen(
    onNavigateToSpeedControl: () -> Unit,
    viewModel: PlaybackViewModel = viewModel()
) {
    val state by viewModel.playbackState.collectAsState()
    val rewindMs by viewModel.rewindMs.collectAsState()
    val forwardMs by viewModel.forwardMs.collectAsState()

    if (state.mediaId.isBlank()) {
        val lastQueue by viewModel.lastQueue.collectAsState()
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            TimeText()
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Nothing playing.\nPick something from your library.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
                if (lastQueue != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Chip(
                        onClick = { viewModel.resumeLastQueue() },
                        label = {
                            Text(
                                text = "Resume",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        colors = ChipDefaults.primaryChipColors(),
                        modifier = Modifier.fillMaxWidth(0.7f)
                    )
                }
            }
        }
        return
    }

    val df = DecimalFormat("0.00x")
    val context = LocalContext.current
    val audioManager = remember {
        context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
    }
    var volumePanelOpen by remember { mutableFloatStateOf(0f) }

    // Rotary seeks in the track; while the volume panel is open it adjusts volume.
    val seekScrollableState = remember {
        ScrollableState { delta ->
            if (volumePanelOpen > 0.5f) {
                changeVolume(audioManager, delta * VOLUME_STEPS_PER_ROTARY_PX)
            } else {
                viewModel.seekBy((delta * SEEK_MS_PER_ROTARY_PX).toLong())
            }
            delta
        }
    }
    val rotaryBehavior = RotaryScrollableDefaults.behavior(
        scrollableState = seekScrollableState,
        flingBehavior = null
    )
    val focusRequester = remember { FocusRequester() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .requestFocusOnHierarchyActive()
            .rotaryScrollable(rotaryBehavior, focusRequester)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        if (dragAmount > 30f) {
                            volumePanelOpen = 1f
                        } else if (dragAmount < -30f) {
                            volumePanelOpen = 0f
                        }
                    }
                )
            }
    ) {
        TimeText()

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = state.currentChapterTitle,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(0.72f)
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp, start = 12.dp, end = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            Text(
                text = buildString {
                    append(formatTime(state.currentPositionMs))
                    append(" / ")
                    append(formatTime(state.durationMs))
                    if (state.trackCount > 1) {
                        append(" · ${state.currentChapterIndex + 1}/${state.trackCount}")
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { volumePanelOpen = if (volumePanelOpen > 0.5f) 0f else 1f }
                    .padding(horizontal = 10.dp, vertical = 2.dp)
            )

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircleIconButton(
                    imageVector = Icons.Filled.Replay30,
                    contentDescription = "Seek back ${rewindMs / 1000}s",
                    enabled = state.isReady
                ) {
                    viewModel.seekToPosition(state.currentPositionMs - rewindMs)
                }

                Spacer(modifier = Modifier.width(10.dp))

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(CenterButtonSize)
                ) {
                    PlayPauseButton(
                        onPlayClick = {
                            if (state.playbackState == Player.STATE_ENDED) {
                                viewModel.replay()
                            } else {
                                viewModel.play()
                            }
                        },
                        onPauseClick = { viewModel.pause() },
                        playing = state.isPlaying,
                        enabled = state.isReady,
                        modifier = Modifier.fillMaxSize(),
                        progress = {
                            CircularProgressIndicator(
                                modifier = Modifier.fillMaxSize(),
                                progress = { state.progress },
                                strokeWidth = 3.dp
                            )
                        }
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                CircleIconButton(
                    imageVector = Icons.Filled.Forward30,
                    contentDescription = "Seek forward ${forwardMs / 1000}s",
                    enabled = state.isReady
                ) {
                    viewModel.seekToPosition(state.currentPositionMs + forwardMs)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircleIconButton(
                    imageVector = Icons.Filled.SkipPrevious,
                    contentDescription = "Previous chapter",
                    enabled = state.isPreviousChapterAvailable
                ) {
                    viewModel.skipToPreviousChapter()
                }

                Spacer(modifier = Modifier.width(10.dp))

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .offset(y = 4.dp)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .clickable(enabled = state.isReady) {
                            val current = (state.playbackSpeed * 100).roundToInt()
                            val next = SpeedSteps.firstOrNull { (it * 100).roundToInt() > current }
                                ?: SpeedSteps.first()
                            viewModel.setPlaybackSpeed(next)
                        }
                        .heightIn(min = CornerButtonSize)
                        .padding(horizontal = 12.dp)
                ) {
                    Text(
                        text = df.format(state.playbackSpeed),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                CircleIconButton(
                    imageVector = Icons.Filled.SkipNext,
                    contentDescription = "Next chapter",
                    enabled = state.isNextChapterAvailable
                ) {
                    viewModel.skipToNextChapter()
                }
            }
        }

        // Swipe-down volume overlay: also reachable by tapping the progress bar.
        val panelVisible = volumePanelOpen > 0.5f
        val panelAlpha by animateFloatAsState(
            targetValue = volumePanelOpen,
            animationSpec = tween(durationMillis = 180),
            label = "volumePanelAlpha"
        )
        VolumePanel(
            audioManager = audioManager,
            enabled = panelVisible,
            onDismiss = { volumePanelOpen = 0f },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 6.dp)
                .graphicsLayer {
                    alpha = panelAlpha
                    translationY = (1f - panelAlpha) * 24.dp.toPx()
                }
        )
    }
}


@Composable
private fun VolumePanel(
    audioManager: android.media.AudioManager,
    enabled: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val streamVolume = remember { audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) }
    val maxVolume = remember { audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC) }
    var volume by remember { mutableFloatStateOf(streamVolume.toFloat() / maxVolume) }

    LaunchedEffect(enabled) {
        while (enabled) {
            delay(250)
            volume = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC).toFloat() / maxVolume
        }
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.95f))
            .clickable(enabled = enabled, onClick = onDismiss)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Button(
            onClick = {
                audioManager.adjustStreamVolume(
                    android.media.AudioManager.STREAM_MUSIC,
                    android.media.AudioManager.ADJUST_LOWER,
                    0
                )
                volume = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC).toFloat() / maxVolume
            },
            enabled = enabled,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.VolumeDown,
                contentDescription = "Volume Down",
                modifier = Modifier.size(18.dp)
            )
        }

        Spacer(modifier = Modifier.width(10.dp))

        Text(
            text = "${(volume * 100).roundToInt()}%",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.width(10.dp))

        Button(
            onClick = {
                audioManager.adjustStreamVolume(
                    android.media.AudioManager.STREAM_MUSIC,
                    android.media.AudioManager.ADJUST_RAISE,
                    0
                )
                volume = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC).toFloat() / maxVolume
            },
            enabled = enabled,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = "Volume Up",
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

private fun changeVolume(audioManager: android.media.AudioManager, steps: Float) {
    if (abs(steps) < 0.05f) return
    val direction = if (steps > 0) {
        android.media.AudioManager.ADJUST_RAISE
    } else {
        android.media.AudioManager.ADJUST_LOWER
    }
    repeat(abs(steps).roundToInt().coerceAtLeast(1)) {
        audioManager.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, direction, 0)
    }
}


@Composable
private fun CircleIconButton(
    imageVector: ImageVector,
    contentDescription: String,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(CornerButtonSize)
            .clip(CircleShape)
            .background(
                if (enabled) {
                    MaterialTheme.colorScheme.surfaceContainer
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                }
            )
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            tint = if (enabled) {
                tint
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            },
            modifier = Modifier.size(CornerIconSize)
        )
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

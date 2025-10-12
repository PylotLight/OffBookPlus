package com.devlight.offbookplus.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.Text
import androidx.wear.compose.material3.MaterialTheme
import com.devlight.offbookplus.ui.viewmodel.PlaybackViewModel
import java.text.DecimalFormat

private val speedOptions = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
private val df = DecimalFormat("0.00x")

@Composable
fun SpeedControlScreen(
    viewModel: PlaybackViewModel = viewModel()
) {
    val state by viewModel.playbackState.collectAsState()
    val currentSpeed = state.playbackSpeed

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            Text(
                "Playback Speed",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
            )
        }

        item {
            Text(
                text = "Current: ${df.format(currentSpeed)}",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 16.dp)
            )
        }

        speedOptions.chunked(2).forEach { rowSpeeds ->
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    rowSpeeds.forEach { speed ->
                        val isSelected = speed == currentSpeed
                        Chip(
                            onClick = { viewModel.setPlaybackSpeed(speed) },
                            label = { Text(df.format(speed)) },
                            colors = if (isSelected) {
                                androidx.wear.compose.material.ChipDefaults.primaryChipColors()
                            } else {
                                androidx.wear.compose.material.ChipDefaults.secondaryChipColors()
                            },
                            modifier = Modifier.weight(1f).padding(horizontal = 4.dp)
                        )
                    }
                    if (rowSpeeds.size < 2) {
                        Spacer(modifier = Modifier.weight(1f).padding(horizontal = 4.dp))
                    }
                }
                Spacer(modifier = Modifier.padding(bottom = 8.dp))
            }
        }
    }
}
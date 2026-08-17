package com.devlight.offbookplus.ui.screen

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.Text
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.MaterialTheme
import com.devlight.offbookplus.data.PlayHistoryEntity
import com.devlight.offbookplus.model.MediaType
import com.devlight.offbookplus.ui.viewmodel.HistoryViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel = viewModel(),
    onPlayMedia: (mediaId: String, mediaType: MediaType) -> Unit = { _, _ -> }
) {
    val history by viewModel.history.collectAsState()

    ScalingLazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 4.dp)
    ) {
        item {
            Text(
                "Play History",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
            )
        }
        if (history.isEmpty()) {
            item {
                Text(
                    "Nothing played yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 16.dp)
                )
            }
        } else {
            items(history) { entry ->
                HistoryEntryCard(entry = entry, onPlay = { onPlayMedia(entry.mediaId, entry.mediaType) })
            }
            item {
                Chip(
                    onClick = { viewModel.clearHistory() },
                    label = { Text("Clear History") },
                    icon = { Icon(Icons.Default.Delete, contentDescription = "Clear History") },
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
        }
    }
}

@Composable
private fun HistoryEntryCard(
    entry: PlayHistoryEntity,
    onPlay: () -> Unit
) {
    Card(onClick = onPlay) {
        Text(
            text = entry.title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = "${entry.playCount} play${if (entry.playCount == 1) "" else "s"} · ${formatPlayTime(entry.totalPlayTimeMs)}",
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            text = "Last played ${formatDate(entry.lastPlayedAt)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

private fun formatPlayTime(totalMs: Long): String {
    val hours = TimeUnit.MILLISECONDS.toHours(totalMs)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(totalMs) % 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m"
        else -> "0m"
    }
}

private val dateFormatter = DateTimeFormatter.ofPattern("d MMM, HH:mm")

private fun formatDate(epochMs: Long): String {
    return try {
        Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(dateFormatter)
    } catch (e: Exception) {
        "-"
    }
}
package com.devlight.offbookplus.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.Icon
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.Text
import androidx.wear.compose.material3.MaterialTheme
import com.devlight.offbookplus.model.MediaType
import com.devlight.offbookplus.ui.NavRoutes

@Composable
fun HomeScreen( // Renamed for clarity
    onNavigate: (String) -> Unit
) {
    val items = listOf(
        SelectorItem(MediaType.AUDIOBOOKS.title, NavRoutes.LIBRARY_ROUTE_TEMPLATE, Icons.AutoMirrored.Filled.LibraryBooks, MediaType.AUDIOBOOKS),
        SelectorItem(MediaType.PODCASTS.title, NavRoutes.LIBRARY_ROUTE_TEMPLATE, Icons.Default.Podcasts, MediaType.PODCASTS),
        SelectorItem(MediaType.MUSIC.title, NavRoutes.LIBRARY_ROUTE_TEMPLATE, Icons.Default.MusicNote, MediaType.MUSIC),
        SelectorItem("Settings", NavRoutes.SETTINGS_ROUTE, Icons.Default.Settings)
    )

    ScalingLazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            Text(
                "Select Collection",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
            )
        }
        items(items) { item ->
            Box(modifier = Modifier.fillMaxWidth()) { // Box with fillMaxWidth to force Chip width
                Chip(
                    onClick = {
                        val route = if (item.mediaType != null) {
                            item.route.replace("{mediaType}", item.mediaType.name)
                        } else {
                            item.route
                        }
                        onNavigate(route)
                    },
                    label = { Text(item.title) },
                    icon = { Icon(imageVector = item.icon, contentDescription = item.title) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

private data class SelectorItem(
    val title: String,
    val route: String,
    val icon: ImageVector,
    val mediaType: MediaType? = null
)
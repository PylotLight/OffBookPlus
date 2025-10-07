package com.devlight.offbookplus.ui.screen

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.Icon
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material.Text
import androidx.wear.compose.material3.MaterialTheme
import com.devlight.offbookplus.ui.viewmodel.LibraryViewModel

@Composable
fun SettingsScreen(
    viewModel: LibraryViewModel
) {
    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp)
    ) {
        item {
            Text(
                "Settings",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
            )
        }
        item {
            Chip(
                onClick = { viewModel.forceFullRescan() },
                label = { Text("Force Full Rescan") },
                secondaryLabel = { Text("Rescan all media folders.") },
                icon = { Icon(imageVector = Icons.Default.Refresh, contentDescription = "Rescan") }
            )
        }
        // Other settings items would go here
    }
}
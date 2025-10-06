package com.devlight.offbookplus.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.wear.compose.material3.MaterialTheme

/**
 * Custom theme for the Audiobook App, based on Wear Compose Material 3.
 */
@Composable
fun AudiobookAppTheme(
    content: @Composable () -> Unit
) {
    // Define a basic dark color scheme, standard for Wear OS
    val colorScheme = darkColorScheme(
        primary = MaterialTheme.colorScheme.primary, // Default Wear primary
        secondary = MaterialTheme.colorScheme.secondary
        // Define other colors as needed
    )

    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme,
        content = content
    )
}
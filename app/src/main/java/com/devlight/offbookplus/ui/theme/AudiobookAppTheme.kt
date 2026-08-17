package com.devlight.offbookplus.ui.theme

import androidx.compose.runtime.Composable
import androidx.wear.compose.material3.MaterialTheme

/**
 * Custom theme for the Audiobook App, based on Wear Compose Material 3.
 */
@Composable
fun AudiobookAppTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(content = content)
}
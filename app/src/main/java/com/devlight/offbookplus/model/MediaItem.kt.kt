package com.devlight.offbookplus.model

import androidx.compose.runtime.Immutable

/**
 * Data class representing a single Audiobook.
 *
 * @param id A unique identifier for the book. Used for persistence (Room) and MediaSession tracking.
 * @param title The title of the audiobook.
 * @param author The author of the audiobook.
 */
@Immutable
data class MediaItem(
    val id: String,
    val playlistId: String,
    val mediaType: MediaType,
    val title: String,
    val author: String,
    val fileUri: String
)
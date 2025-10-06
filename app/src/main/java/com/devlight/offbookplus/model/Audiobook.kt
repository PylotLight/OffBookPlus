package com.devlight.offbookplus.model

import androidx.compose.runtime.Immutable // Best practice for Compose data models

/**
 * Data class representing a single Audiobook.
 *
 * @param id A unique identifier for the book. Used for persistence (Room) and MediaSession tracking.
 * @param title The title of the audiobook.
 * @param author The author of the audiobook.
 * @param coverUri The URI to the book's cover art (can be local or remote, typically local content URI on Wear OS).
 * @param chapters Chapter Info
 */
@Immutable
data class Audiobook(
    val id: String,
    val title: String,
    val author: String,
    val coverUri: String? = null, // Optional cover art
    val chapters: List<Chapter> = emptyList()
)
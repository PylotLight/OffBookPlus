package com.devlight.offbookplus.model

import androidx.compose.runtime.Immutable

/**
 * Data class representing a single chapter/audio file of an audiobook.
 *
 * @param bookId The ID of the parent book this chapter belongs to.
 * @param index The sequential index of the chapter within the book.
 * @param title The title of the chapter.
 * @param fileUri The content URI pointing to the actual M4A file on the device.
 * @param startTimeSec The duration of the chapter in Seconds (retrieved during file scanning).
 */
@Immutable
data class Chapter(
    val bookId: String,
    val index: Int,
    val title: String,
    val fileUri: String? = null,
    val startTimeSec: Int
)
package com.devlight.offbookplus.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room Entity representing the last known playback progress for an audiobook.
 *
 * @param bookId The unique ID of the audiobook. This will be the primary key.
 * @param playbackPositionMs The exact position (in milliseconds) within the chapter.
 * @param lastUpdatedTimestamp The system time of the last progress update, useful for debugging/syncing.
 */
@Entity(tableName = "book_progress")
data class BookProgressEntity(
    @PrimaryKey
    val bookId: String,
    val playbackPositionMs: Long,
    val lastUpdatedTimestamp: Long = System.currentTimeMillis()
)
package com.devlight.offbookplus.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.devlight.offbookplus.model.MediaType

/**
 * A persisted playback queue. Stores the exact playback order (so a shuffled
 * music queue survives switching libraries or restarting the app), plus the
 * current position within the queue.
 *
 * `queueId` is the playlist id the queue was built from (e.g. `all_music_tracks`
 * or a podcast/audiobook folder slug), so each library keeps its own queue.
 */
@Entity(tableName = "playback_queue")
data class PlaybackQueueEntity(
    @PrimaryKey
    val queueId: String,
    val mediaType: MediaType,
    val orderedIds: String,
    val currentIndex: Int = 0,
    val positionMs: Long = 0L,
    val shuffleEnabled: Boolean = false,
    val lastUpdatedTimestamp: Long = System.currentTimeMillis()
)

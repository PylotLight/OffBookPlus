package com.devlight.offbookplus.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.devlight.offbookplus.model.MediaType

/**
 * Per-item resume position for spoken-word libraries (podcasts, audiobooks).
 * Music is intentionally excluded: it shares one global queue and always
 * starts tracks from the top.
 *
 * `mediaId` is the MediaItemEntity id (file Uri string) of the episode/chapter.
 */
@Entity(tableName = "track_progress")
data class TrackProgressEntity(
    @PrimaryKey
    val mediaId: String,
    val playlistId: String,
    val mediaType: MediaType,
    val positionMs: Long,
    val lastUpdatedTimestamp: Long = System.currentTimeMillis()
)

package com.devlight.offbookplus.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.devlight.offbookplus.model.MediaType

/**
 * Aggregated listening stats for a single media item, used for the Play History screen.
 * Rows are only written on discrete playback events (start / pause / transition / stop) or
 * a throttled periodic flush while continuously playing, to avoid battery drain.
 */
@Entity(tableName = "play_history")
data class PlayHistoryEntity(
    @PrimaryKey
    val mediaId: String,
    val playlistId: String,
    val mediaType: MediaType,
    val title: String,
    val artist: String,
    val playCount: Int = 0,
    val totalPlayTimeMs: Long = 0L,
    val firstPlayedAt: Long = 0L,
    val lastPlayedAt: Long = 0L
)
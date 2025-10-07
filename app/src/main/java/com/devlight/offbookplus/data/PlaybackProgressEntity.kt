package com.devlight.offbookplus.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "playback_progress")
data class PlaybackProgressEntity(
    @PrimaryKey
    val playlistId: String,
    val trackIndex: Int = 0,
    val playbackPositionMs: Long,
    val lastUpdatedTimestamp: Long = System.currentTimeMillis()
)
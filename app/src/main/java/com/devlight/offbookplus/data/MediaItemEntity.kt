package com.devlight.offbookplus.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.devlight.offbookplus.model.MediaType

@Entity(tableName = "media_items")
data class MediaItemEntity(
    @PrimaryKey
    val id: String,
    val playlistId: String,
    val mediaType: MediaType,
    val title: String,
    val artist: String,
    val trackNumber: Int,
    val fileUri: String
)
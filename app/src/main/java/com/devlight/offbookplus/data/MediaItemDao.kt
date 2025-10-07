package com.devlight.offbookplus.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface MediaItemDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<MediaItemEntity>)

    @Query("SELECT * FROM media_items WHERE mediaType = :mediaType ORDER BY playlistId, trackNumber ASC")
    suspend fun getItemsByMediaType(mediaType: String): List<MediaItemEntity>

    @Query("SELECT * FROM media_items WHERE playlistId = :playlistId ORDER BY trackNumber ASC")
    suspend fun getItemsByPlaylistId(playlistId: String): List<MediaItemEntity>

    @Query("DELETE FROM media_items WHERE mediaType = :mediaType")
    suspend fun deleteByMediaType(mediaType: String)

    @Query("DELETE FROM media_items")
    suspend fun deleteAll()


}
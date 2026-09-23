package com.devlight.offbookplus.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface TrackProgressDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(progress: TrackProgressEntity)

    @Query("SELECT * FROM track_progress WHERE mediaId = :mediaId")
    suspend fun load(mediaId: String): TrackProgressEntity?

    @Query("SELECT * FROM track_progress ORDER BY lastUpdatedTimestamp DESC")
    suspend fun getAllOnce(): List<TrackProgressEntity>

    @Query("SELECT mediaId FROM track_progress WHERE mediaType = :mediaType")
    suspend fun getIdsForType(mediaType: String): List<String>

    @Query("DELETE FROM track_progress WHERE mediaId = :mediaId")
    suspend fun delete(mediaId: String)

    @Query("DELETE FROM track_progress WHERE mediaId IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)
}

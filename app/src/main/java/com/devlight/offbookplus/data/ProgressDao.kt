package com.devlight.offbookplus.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object (DAO) for the BookProgressEntity.
 * Handles saving and retrieving a user's progress.
 */
@Dao
interface ProgressDao {

    /**
     * Inserts or updates the progress for a media playlist.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveProgress(progress: PlaybackProgressEntity)


    /**
     * Retrieves the last saved progress for a specific playlist ID.
     * Returns null if no progress has been saved for that playlist yet.
     */
    @Query("SELECT * FROM playback_progress WHERE playlistId = :playlistId")
    suspend fun loadProgress(playlistId: String): PlaybackProgressEntity?


    /**
     * Retrieve the single most recently played progress across all playlists.
     */
    @Query("SELECT * FROM playback_progress ORDER BY lastUpdatedTimestamp DESC LIMIT 1")
    suspend fun getMostRecentProgress(): PlaybackProgressEntity?

    /**
     * Optionally, retrieve all saved progress records (e.g., for a 'Recently Played' feature).
     */
    @Query("SELECT * FROM playback_progress ORDER BY lastUpdatedTimestamp DESC")
    fun getAllProgress(): Flow<List<PlaybackProgressEntity>>

    /**
     * One-shot variant of [getAllProgress] for use outside of Flow collectors.
     */
    @Query("SELECT * FROM playback_progress ORDER BY lastUpdatedTimestamp DESC")
    suspend fun getAllProgressOnce(): List<PlaybackProgressEntity>
}
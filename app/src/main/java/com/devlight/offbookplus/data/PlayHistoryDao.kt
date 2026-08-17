package com.devlight.offbookplus.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PlayHistoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: PlayHistoryEntity)

    @Query("SELECT * FROM play_history WHERE mediaId = :mediaId")
    suspend fun getHistory(mediaId: String): PlayHistoryEntity?

    @Query("SELECT * FROM play_history ORDER BY lastPlayedAt DESC")
    fun getAllHistoryFlow(): Flow<List<PlayHistoryEntity>>

    @Query("SELECT * FROM play_history ORDER BY totalPlayTimeMs DESC")
    suspend fun getAllHistoryByPlayTime(): List<PlayHistoryEntity>

    @Query("DELETE FROM play_history")
    suspend fun clearAll()
}
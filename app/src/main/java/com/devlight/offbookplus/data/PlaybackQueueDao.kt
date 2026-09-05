package com.devlight.offbookplus.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PlaybackQueueDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(queue: PlaybackQueueEntity)

    @Query("SELECT * FROM playback_queue WHERE queueId = :queueId")
    suspend fun load(queueId: String): PlaybackQueueEntity?

    @Query("SELECT * FROM playback_queue ORDER BY lastUpdatedTimestamp DESC LIMIT 1")
    suspend fun getMostRecent(): PlaybackQueueEntity?

    @Query("SELECT * FROM playback_queue ORDER BY lastUpdatedTimestamp DESC")
    suspend fun getAll(): List<PlaybackQueueEntity>

    @Query("SELECT * FROM playback_queue WHERE mediaType = :mediaType ORDER BY lastUpdatedTimestamp DESC")
    suspend fun getAllForType(mediaType: String): List<PlaybackQueueEntity>

    @Query("DELETE FROM playback_queue WHERE queueId = :queueId")
    suspend fun delete(queueId: String)
}

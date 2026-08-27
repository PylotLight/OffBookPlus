package com.devlight.offbookplus.data

import com.devlight.offbookplus.model.MediaType
import androidx.room.withTransaction

/**
 * Writes aggregated play history records inside a single transaction so playCount and
 * totalPlayTimeMs are updated atomically without a dedicated SQLite UPSERT.
 */
class PlayHistoryRecorder(private val db: AppDatabase) {

    private val dao get() = db.playHistoryDao()

    /**
     * Counts one completed listen for the given media item, creating the record on
     * first completion. Items that were only started (skipped before finishing)
     * never get a history entry.
     */
    suspend fun recordItemFinished(
        mediaId: String,
        playlistId: String,
        mediaType: MediaType,
        title: String,
        artist: String
    ) {
        if (mediaId.isBlank()) return
        db.withTransaction {
            val now = System.currentTimeMillis()
            val existing = dao.getHistory(mediaId)
            if (existing == null) {
                dao.upsert(
                    PlayHistoryEntity(
                        mediaId = mediaId,
                        playlistId = playlistId,
                        mediaType = mediaType,
                        title = title,
                        artist = artist,
                        playCount = 1,
                        totalPlayTimeMs = 0L,
                        firstPlayedAt = now,
                        lastPlayedAt = now
                    )
                )
            } else {
                dao.upsert(
                    existing.copy(
                        playlistId = playlistId,
                        mediaType = mediaType,
                        title = title,
                        artist = artist,
                        playCount = existing.playCount + 1,
                        lastPlayedAt = now
                    )
                )
            }
        }
    }

    /**
     * Adds accrued listening time (ms) to an item's running total. No-op if the item
     * has never been recorded.
     */
    suspend fun addPlayTime(mediaId: String, timeMs: Long) {
        if (mediaId.isBlank() || timeMs <= 0) return
        db.withTransaction {
            val existing = dao.getHistory(mediaId) ?: return@withTransaction
            dao.upsert(existing.copy(totalPlayTimeMs = existing.totalPlayTimeMs + timeMs))
        }
    }
}
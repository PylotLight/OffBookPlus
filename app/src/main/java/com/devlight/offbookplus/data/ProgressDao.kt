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
     * Inserts or updates the progress for a book.
     * OnConflictStrategy.REPLACE ensures that if a record with the same bookId exists, it is updated.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveProgress(progress: BookProgressEntity)

    /**
     * Retrieves the last saved progress for a specific audiobook ID.
     * Returns null if no progress has been saved for that book yet.
     */
    @Query("SELECT * FROM book_progress WHERE bookId = :bookId")
    suspend fun loadProgress(bookId: String): BookProgressEntity?

    /**
     * Optionally, retrieve all saved progress records (e.g., for a 'Recently Played' feature).
     */
    @Query("SELECT * FROM book_progress ORDER BY lastUpdatedTimestamp DESC")
    fun getAllProgress(): Flow<List<BookProgressEntity>>
}
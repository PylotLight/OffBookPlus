package com.devlight.offbookplus.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * The main Room Database class for the application.
 * Defines the entities (tables) and provides access to the DAOs.
 */
@Database(
    entities = [PlaybackProgressEntity::class, MediaItemEntity::class, PlayHistoryEntity::class, PlaybackQueueEntity::class],
    version = 4,
    exportSchema = false
)
@TypeConverters(MediaTypeConverter::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun progressDao(): ProgressDao
    abstract fun mediaItemDao(): MediaItemDao
    abstract fun playHistoryDao(): PlayHistoryDao
    abstract fun playbackQueueDao(): PlaybackQueueDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "offbookplus_db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .fallbackToDestructiveMigration(true)
                    .build()
                INSTANCE = instance
                instance
            }
        }

        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE playback_progress ADD COLUMN shuffleModeEnabled INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS playback_queue (
                        queueId TEXT NOT NULL PRIMARY KEY,
                        mediaType TEXT NOT NULL,
                        orderedIds TEXT NOT NULL,
                        currentIndex INTEGER NOT NULL DEFAULT 0,
                        positionMs INTEGER NOT NULL DEFAULT 0,
                        shuffleEnabled INTEGER NOT NULL DEFAULT 0,
                        lastUpdatedTimestamp INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS play_history (
                        mediaId TEXT NOT NULL PRIMARY KEY,
                        playlistId TEXT NOT NULL,
                        mediaType TEXT NOT NULL,
                        title TEXT NOT NULL,
                        artist TEXT NOT NULL,
                        playCount INTEGER NOT NULL DEFAULT 0,
                        totalPlayTimeMs INTEGER NOT NULL DEFAULT 0,
                        firstPlayedAt INTEGER NOT NULL DEFAULT 0,
                        lastPlayedAt INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
            }
        }
    }
}
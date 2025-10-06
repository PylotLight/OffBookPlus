package com.devlight.offbookplus.model

import androidx.media3.common.Player

/**
 * Data class representing the current state of the media player for the UI.
 *
 * @param bookId The ID of the book currently being played.
 * @param isPlaying True if the player is actively playing audio.
 * @param currentChapterTitle Title of the chapter currently loaded/playing.
 * @param currentChapterIndex Index of the current chapter in the playlist.
 * @param currentPositionMs Current playback position in milliseconds.
 * @param durationMs Total duration of the current chapter in milliseconds.
 * @param isLoading True if the player is buffering or seeking.
 */
data class PlaybackState(
    val bookId: String = "",
    val isPlaying: Boolean = false,
    val currentChapterTitle: String = "Loading...",
    val currentChapterIndex: Int = 0,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 1L, // Set to 1 to avoid DivisionByZero in progress bar calculations
    @Player.State val playbackState: Int = Player.STATE_IDLE,
    val isPreviousChapterAvailable: Boolean = false,
    val isNextChapterAvailable: Boolean = false
) {
    /** Helper property to calculate the progress for the UI (0.0f to 1.0f) */
    val progress: Float
        get() = if (durationMs > 0) currentPositionMs.toFloat() / durationMs.toFloat() else 0f

    /** Helper property to check if the player is ready to play */
    val isReady: Boolean
        get() = playbackState != Player.STATE_IDLE && playbackState != Player.STATE_ENDED
}
package com.devlight.offbookplus.ui.viewmodel

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.concurrent.futures.await
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.devlight.offbookplus.data.AppDatabase
import com.devlight.offbookplus.model.MediaType
import com.devlight.offbookplus.model.PlaybackState
import com.devlight.offbookplus.playback.MediaPlaybackService
import com.devlight.offbookplus.playback.PlaybackContract
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "PlaybackViewModel"
class PlaybackViewModel(application: Application) : AndroidViewModel(application) {

    private companion object {
        const val PREFS_NAME = "PlaybackPrefs"
        const val KEY_SPEED_PREFIX = "playback_speed_"
        const val DEFAULT_SPEED = 1.0f
    }
    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()
    private var mediaControllerFuture: ListenableFuture<MediaController>
    private val mediaItemDao = AppDatabase.getInstance(application).mediaItemDao()
    private val mediaController: MediaController?
        get() = if (mediaControllerFuture.isDone) mediaControllerFuture.get() else null

    private var progressUpdateJob: Job? = null
    private val controllerListener = MediaControllerListener()

    init {
        val sessionToken = SessionToken(application, ComponentName(application,
            MediaPlaybackService::class.java))
        mediaControllerFuture = MediaController.Builder(application, sessionToken).buildAsync()
        mediaControllerFuture.addListener({
            mediaController?.addListener(controllerListener)
            updateStateFromController()
        }, MoreExecutors.directExecutor())
        startProgressUpdate()
    }

    private inner class MediaControllerListener : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            updateStateFromController()
        }
    }
    private fun updateStateFromController() {
        val player = mediaController ?: return
        val isFinished = player.playbackState == Player.STATE_ENDED
        val currentMediaItem = player.currentMediaItem
        val currentMediaType = try {
            currentMediaItem?.mediaMetadata?.extras?.getString("MEDIA_TYPE")
                ?.let { MediaType.valueOf(it) }
        } catch (e: Exception) { null }

        _playbackState.value = _playbackState.value.copy(
            isPlaying = player.isPlaying && !isFinished,
            currentPositionMs = player.currentPosition,
            durationMs = player.duration.coerceAtLeast(1L),
            playbackState = player.playbackState,
            currentChapterTitle = currentMediaItem?.mediaMetadata?.title?.toString() ?: "No Title",
            bookId = currentMediaItem?.mediaMetadata?.albumTitle?.toString() ?: "",
            mediaId = currentMediaItem?.mediaId ?: "",
            mediaType = currentMediaType ?: MediaType.AUDIOBOOKS,
            isPreviousChapterAvailable = player.hasPreviousMediaItem(),
            isNextChapterAvailable = player.hasNextMediaItem(),
            isShuffleEnabled = player.shuffleModeEnabled,
            playbackSpeed = player.playbackParameters.speed
        )
    }

    private fun startProgressUpdate() {
        progressUpdateJob?.cancel()
        progressUpdateJob = viewModelScope.launch {
            while (true) {
                if (mediaController?.isPlaying == true) {
                    updateStateFromController()
                }
                delay(1000)
            }
        }
    }
    /**
     * The single, intelligent function to handle all playback requests.
     */
    suspend fun playMediaItem(bookId: String, mediaType: MediaType) {
        try {
            val player = mediaControllerFuture.await()
            if (player.currentMediaItem?.mediaId == bookId) {
                Log.d(TAG, "Requested media ID '$bookId' is already the current item. No action taken.")
                return
            }

            Log.i(TAG, "New media item requested. Sending command to play '$bookId' of type '$mediaType'.")
            val command = SessionCommand(PlaybackContract.COMMAND_LOAD_MEDIA_AND_PLAY, Bundle.EMPTY)
            val args = Bundle().apply {
                putString(PlaybackContract.KEY_MEDIA_ID, bookId)
                putString(PlaybackContract.KEY_MEDIA_TYPE, mediaType.name)
            }
            player.sendCustomCommand(command, args)
            val savedSpeed = loadPlaybackSpeed(mediaType)
            if (player.playbackParameters.speed != savedSpeed) {
                player.setPlaybackParameters(PlaybackParameters(savedSpeed))
                updateStateFromController()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send playMediaItem command", e)
        }
    }
    /**
     * Fix 1: Saves the new speed to SharedPreferences and applies it to the player.
     * The speed is saved per MediaType.
     */
    fun setPlaybackSpeed(speed: Float) {
        val mediaType = try {
            mediaController?.currentMediaItem?.mediaMetadata?.extras?.getString("MEDIA_TYPE")?.let { MediaType.valueOf(it) }
        } catch (e: Exception) { null }

        if (mediaController?.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH) == true) {
            val params = PlaybackParameters(speed)
            mediaController?.setPlaybackParameters(params)
            Log.d(TAG, "Setting playback speed to $speed")

            // Save the speed per media type
            if (mediaType != null) {
                savePlaybackSpeed(mediaType, speed)
                Log.d(TAG, "Saved speed $speed for ${mediaType.name}")
            }
        } else {
            Log.w(TAG, "Command COMMAND_SET_SPEED_AND_PITCH is not available.")
        }
    }

    fun play() { mediaController?.play() }
    fun pause() { mediaController?.pause() }
    private fun savePlaybackSpeed(mediaType: MediaType, speed: Float) {
        prefs.edit {
            putFloat(KEY_SPEED_PREFIX + mediaType.name, speed)
        }
    }

    private fun loadPlaybackSpeed(mediaType: MediaType): Float {
        // Load the saved speed for this media type, defaulting to 1.0f
        return prefs.getFloat(KEY_SPEED_PREFIX + mediaType.name, DEFAULT_SPEED)
    }
    fun seekToPosition(positionMs: Long) { mediaController?.seekTo(positionMs) }

    fun replay() {
        mediaController?.seekTo(0)
        mediaController?.play()
    }

    fun toggleShuffle() {
        val newShuffleState = !(mediaController?.shuffleModeEnabled ?: false)
        mediaController?.shuffleModeEnabled = newShuffleState
        updateStateFromController()
        Log.d(TAG, "Shuffle toggled to $newShuffleState")
    }

    fun skipToNextChapter() {
        mediaController?.seekToNextMediaItem()
    }

    fun skipToPreviousChapter() {
        if ((mediaController?.currentPosition ?: 0) > 3000) {
            mediaController?.seekTo(0)
        } else {
            mediaController?.seekToPreviousMediaItem()
        }
    }

    override fun onCleared() {
        progressUpdateJob?.cancel()
        mediaController?.removeListener(controllerListener)
        MediaController.releaseFuture(mediaControllerFuture)
        super.onCleared()
    }
}
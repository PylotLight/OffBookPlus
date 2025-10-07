package com.devlight.offbookplus.ui.viewmodel

import android.app.Application
import android.content.ComponentName
import android.os.Bundle
import android.util.Log
import androidx.concurrent.futures.await
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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

        _playbackState.value = _playbackState.value.copy(
            isPlaying = player.isPlaying && !isFinished,
            currentPositionMs = player.currentPosition,
            durationMs = player.duration.coerceAtLeast(1L),
            playbackState = player.playbackState,
            currentChapterTitle = currentMediaItem?.mediaMetadata?.title?.toString() ?: "No Title",
            bookId = currentMediaItem?.mediaMetadata?.albumTitle?.toString() ?: "",
            isPreviousChapterAvailable = player.hasPreviousMediaItem(),
            isNextChapterAvailable = player.hasNextMediaItem()
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

        } catch (e: Exception) {
            Log.e(TAG, "Failed to send playMediaItem command", e)
        }
    }

    fun play() { mediaController?.play() }
    fun pause() { mediaController?.pause() }
    fun seekToPosition(positionMs: Long) { mediaController?.seekTo(positionMs) }

    fun replay() {
        mediaController?.seekTo(0)
        mediaController?.play()
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
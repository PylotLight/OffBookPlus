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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "PlaybackViewModel"

data class LastQueueInfo(
    val queueId: String,
    val mediaType: MediaType
) {
    val displayTitle: String
        get() = if (queueId == "all_music_tracks") "Music" else queueId.replace('_', ' ')
}

class PlaybackViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences(PlaybackContract.PREFS_NAME, Context.MODE_PRIVATE)
    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()
    private var mediaControllerFuture: ListenableFuture<MediaController>
    private val mediaController: MediaController?
        get() = if (mediaControllerFuture.isDone) mediaControllerFuture.get() else null

    private var progressUpdateJob: Job? = null
    private val controllerListener = MediaControllerListener()

    private val _rewindMs = MutableStateFlow(prefs.getLong(PlaybackContract.KEY_REWIND_MS, PlaybackContract.DEFAULT_REWIND_MS))
    val rewindMs: StateFlow<Long> = _rewindMs.asStateFlow()
    private val _forwardMs = MutableStateFlow(prefs.getLong(PlaybackContract.KEY_FORWARD_MS, PlaybackContract.DEFAULT_FORWARD_MS))
    val forwardMs: StateFlow<Long> = _forwardMs.asStateFlow()

    private val _lastQueue = MutableStateFlow<LastQueueInfo?>(null)
    val lastQueue: StateFlow<LastQueueInfo?> = _lastQueue.asStateFlow()
    private val _hasMusic = MutableStateFlow(false)
    val hasMusic: StateFlow<Boolean> = _hasMusic.asStateFlow()

    init {
        val sessionToken = SessionToken(application, ComponentName(application,
            MediaPlaybackService::class.java))
        mediaControllerFuture = MediaController.Builder(application, sessionToken).buildAsync()
        mediaControllerFuture.addListener({
            mediaController?.addListener(controllerListener)
            updateStateFromController()
        }, MoreExecutors.directExecutor())
        startProgressUpdate()
        loadLastQueue()
        refreshHasMusic()
    }

    private fun loadLastQueue() {
        viewModelScope.launch {
            val queue = withContext(Dispatchers.IO) {
                val q = AppDatabase.getInstance(getApplication()).playbackQueueDao().getMostRecent() ?: return@withContext null
                val ids = runCatching {
                    kotlinx.serialization.json.Json.decodeFromString<List<String>>(q.orderedIds)
                }.getOrNull() ?: return@withContext null
                if (ids.isEmpty()) return@withContext null
                val existing = AppDatabase.getInstance(getApplication()).mediaItemDao().getItemsByIds(ids)
                if (existing.isEmpty()) return@withContext null
                q
            }
            _lastQueue.value = queue?.let { LastQueueInfo(it.queueId, it.mediaType) }
        }
    }

    private fun refreshHasMusic() {
        viewModelScope.launch {
            val has = withContext(Dispatchers.IO) {
                AppDatabase.getInstance(getApplication()).mediaItemDao().getItemsByMediaType(MediaType.MUSIC.name).isNotEmpty()
            }
            _hasMusic.value = has
        }
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
        val extras = currentMediaItem?.mediaMetadata?.extras
        val currentMediaType = try {
            extras?.getString(PlaybackContract.EXTRA_MEDIA_TYPE)?.let { MediaType.valueOf(it) }
        } catch (e: Exception) { null }

        _playbackState.value = _playbackState.value.copy(
            isPlaying = player.isPlaying && !isFinished,
            currentPositionMs = player.currentPosition,
            durationMs = player.duration.coerceAtLeast(1L),
            playbackState = player.playbackState,
            currentChapterTitle = currentMediaItem?.mediaMetadata?.title?.toString() ?: "No Title",
            currentChapterArtist = currentMediaItem?.mediaMetadata?.artist?.toString() ?: "",
            bookId = currentMediaItem?.mediaMetadata?.albumTitle?.toString() ?: "",
            mediaId = currentMediaItem?.mediaId ?: "",
            mediaType = currentMediaType ?: MediaType.AUDIOBOOKS,
            currentChapterIndex = player.currentMediaItemIndex.coerceAtLeast(0),
            trackCount = player.mediaItemCount,
            isPreviousChapterAvailable = player.hasPreviousMediaItem(),
            isNextChapterAvailable = player.hasNextMediaItem(),
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
     * Requests playback of a specific item. The service decides whether to seek inside the
     * active queue, restore a persisted queue for that playlist, or build a fresh one.
     */
    fun playMediaItem(bookId: String, mediaType: MediaType) {
        viewModelScope.launch {
            try {
                val player = mediaControllerFuture.await()
                if (player.currentMediaItem?.mediaId == bookId) {
                    Log.d(TAG, "Requested media ID '$bookId' is already the current item. No action taken.")
                    return@launch
                }
                Log.i(TAG, "Requesting playback of '$bookId' of type '$mediaType'.")
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
    }

    /** Resumes the most recently persisted queue (order, track and position). */
    fun resumeLastQueue() {
        viewModelScope.launch {
            try {
                val player = mediaControllerFuture.await()
                player.sendCustomCommand(SessionCommand(PlaybackContract.COMMAND_RESUME_LAST_QUEUE, Bundle.EMPTY), Bundle.EMPTY)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to resume last queue", e)
            }
        }
    }

    /**
     * Saves the new speed to SharedPreferences and applies it to the player.
     * The speed is saved per MediaType.
     */
    fun setPlaybackSpeed(speed: Float) {
        val mediaType = try {
            mediaController?.currentMediaItem?.mediaMetadata?.extras
                ?.getString(PlaybackContract.EXTRA_MEDIA_TYPE)?.let { MediaType.valueOf(it) }
        } catch (e: Exception) { null }

        if (mediaController?.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH) == true) {
            mediaController?.setPlaybackParameters(PlaybackParameters(speed))
            Log.d(TAG, "Setting playback speed to $speed")
            if (mediaType != null) {
                prefs.edit { putFloat(PlaybackContract.KEY_SPEED_PREFIX + mediaType.name, speed) }
                Log.d(TAG, "Saved speed $speed for ${mediaType.name}")
            }
        } else {
            Log.w(TAG, "Command COMMAND_SET_SPEED_AND_PITCH is not available.")
        }
    }

    fun play() { mediaController?.play() }
    fun pause() { mediaController?.pause() }

    fun seekToPosition(positionMs: Long) { mediaController?.seekTo(positionMs) }

    fun seekBy(deltaMs: Long) {
        val player = mediaController ?: return
        val duration = player.duration
        val target = if (duration > 0) {
            (player.currentPosition + deltaMs).coerceIn(0L, duration)
        } else {
            (player.currentPosition + deltaMs).coerceAtLeast(0L)
        }
        player.seekTo(target)
    }

    fun replay() {
        mediaController?.seekTo(0)
        mediaController?.play()
    }

    /** Builds a fresh shuffled queue from all music tracks and starts a random one. */
    fun shuffleAllMusic() {
        viewModelScope.launch {
            try {
                val player = mediaControllerFuture.await()
                player.sendCustomCommand(SessionCommand(PlaybackContract.COMMAND_SHUFFLE_MUSIC, Bundle.EMPTY), Bundle.EMPTY)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send shuffle-all-music command", e)
            }
        }
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

    fun cycleRewindInterval() {
        val next = nextInterval(_rewindMs.value)
        prefs.edit { putLong(PlaybackContract.KEY_REWIND_MS, next) }
        _rewindMs.value = next
    }

    fun cycleForwardInterval() {
        val next = nextInterval(_forwardMs.value)
        prefs.edit { putLong(PlaybackContract.KEY_FORWARD_MS, next) }
        _forwardMs.value = next
    }

    private fun nextInterval(current: Long): Long = when (current) {
        15_000L -> 30_000L
        30_000L -> 45_000L
        45_000L -> 60_000L
        else -> 15_000L
    }

    override fun onCleared() {
        progressUpdateJob?.cancel()
        mediaController?.removeListener(controllerListener)
        MediaController.releaseFuture(mediaControllerFuture)
        super.onCleared()
    }
}

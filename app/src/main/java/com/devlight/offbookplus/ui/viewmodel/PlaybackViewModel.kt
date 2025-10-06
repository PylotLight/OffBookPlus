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
import com.devlight.offbookplus.model.Chapter
import com.devlight.offbookplus.model.PlaybackState
import com.devlight.offbookplus.playback.AudiobookService
import com.devlight.offbookplus.playback.PlaybackContract
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.util.concurrent.TimeUnit

private const val TAG = "PlaybackViewModel"

class PlaybackViewModel(application: Application) : AndroidViewModel(application) {

    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()
    private val _chapters = MutableStateFlow<List<Chapter>>(emptyList())
    val chapters: StateFlow<List<Chapter>> = _chapters.asStateFlow()
    private var mediaControllerFuture: ListenableFuture<MediaController>
    private val mediaController: MediaController?
        get() = if (mediaControllerFuture.isDone) mediaControllerFuture.get() else null

    private var progressUpdateJob: Job? = null
    private val controllerListener = MediaControllerListener()

    init {
        val sessionToken = SessionToken(application, ComponentName(application, AudiobookService::class.java))
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
            // --- CORRECTED EVENT CONSTANT ---
            if (events.contains(Player.EVENT_MEDIA_METADATA_CHANGED)) {
                parseChaptersFromExtras()
            }
        }
    }
    private fun parseChaptersFromExtras() {
        val extras = mediaController?.sessionExtras ?: return
        val jsonString = extras.getString("chapters") ?: return
        val newChapters = mutableListOf<Chapter>()
        try {
            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val jsonObject = jsonArray.getJSONObject(i)
                newChapters.add(
                    Chapter(
                        bookId = _playbackState.value.bookId,
                        index = i,
                        title = jsonObject.getString("title"),
                        startTimeSec = (jsonObject.getLong("startTimeMs") / 1000).toInt(),
                    )
                )
            }
            _chapters.value = newChapters
            Log.d(TAG, "Parsed ${newChapters.size} chapters from session extras.")
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing chapters from JSON", e)
        }
    }

    private fun updateStateFromController() {
        val player = mediaController ?: return
        val currentPosMs = player.currentPosition
        val chapterList = chapters.value

        val currentChapter = chapterList.lastOrNull { TimeUnit.SECONDS.toMillis(it.startTimeSec.toLong()) <= currentPosMs }

        // --- THE FIX FOR isNextChapterAvailable ---
        // We determine if seeking is possible based on our own chapter list, not Media3's commands.
        val isNextAvailable = chapterList.any { TimeUnit.SECONDS.toMillis(it.startTimeSec.toLong()) > currentPosMs }
        val isPreviousAvailable = chapterList.any { TimeUnit.SECONDS.toMillis(it.startTimeSec.toLong()) < currentPosMs }

        _playbackState.value = _playbackState.value.copy(
            isPlaying = player.isPlaying,
            currentPositionMs = player.currentPosition,
            durationMs = player.duration.coerceAtLeast(1L),
            playbackState = player.playbackState,
            currentChapterTitle = currentChapter?.title ?: player.currentMediaItem?.mediaMetadata?.title?.toString() ?: "No Title",
            bookId = player.currentMediaItem?.mediaMetadata?.albumTitle?.toString() ?: "",
            isPreviousChapterAvailable = isPreviousAvailable,
            isNextChapterAvailable = isNextAvailable
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

    suspend fun playBookFromLibrary(bookId: String) {
        try {
            val player = mediaControllerFuture.await()

            val isAlreadyLoaded = player.mediaItemCount > 0 && player.currentMediaItem?.mediaMetadata?.albumTitle == bookId

            if (isAlreadyLoaded) {
                Log.d(TAG, "Book '$bookId' is already loaded. Ensuring playback is active.")
                if (!player.isPlaying) {
                    player.play()
                }
                return
            }

            Log.i(TAG, "Sending COMMAND_LOAD_BOOK_AND_PLAY for bookId: $bookId")
            val command = SessionCommand(PlaybackContract.COMMAND_LOAD_BOOK_AND_PLAY, Bundle.EMPTY)
            val args = Bundle().apply { putString(PlaybackContract.KEY_BOOK_ID, bookId) }
            player.sendCustomCommand(command, args)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get MediaController or send command", e)
        }
    }

    fun play() { mediaController?.play() }
    fun pause() { mediaController?.pause() }
    fun seekToPosition(positionMs: Long) { mediaController?.seekTo(positionMs) }
    fun skipToNextChapter() {
        val player = mediaController ?: return
        val currentPosMs = player.currentPosition
        val nextChapter = chapters.value.firstOrNull { it.startTimeSec * 1000L > currentPosMs }
        nextChapter?.let { player.seekTo(it.startTimeSec * 1000L) }
    }

    fun skipToPreviousChapter() {
        val player = mediaController ?: return
        val currentPosMs = player.currentPosition
        val currentChapter = chapters.value.lastOrNull { it.startTimeSec * 1000L <= currentPosMs }
        if (currentChapter != null) {
            val currentChapterStartMs = currentChapter.startTimeSec * 1000L
            if (currentPosMs - currentChapterStartMs > 3000) {
                player.seekTo(currentChapterStartMs)
            } else {
                val previousChapter = chapters.value.getOrNull(currentChapter.index - 1)
                player.seekTo(previousChapter?.let { it.startTimeSec * 1000L } ?: 0L)
            }
        }
    }

    override fun onCleared() {
        progressUpdateJob?.cancel()
        mediaController?.removeListener(controllerListener)
        MediaController.releaseFuture(mediaControllerFuture)
        super.onCleared()
    }
}
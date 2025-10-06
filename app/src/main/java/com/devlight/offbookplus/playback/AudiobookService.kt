@file:OptIn(UnstableApi::class) // Handles all UnstableApi warnings in this file

package com.devlight.offbookplus.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.devlight.offbookplus.data.AppDatabase
import com.devlight.offbookplus.data.BookProgressEntity
import com.devlight.offbookplus.data.LocalFileScanner
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "AudiobookService"

class AudiobookService : MediaSessionService() {

    private lateinit var exoPlayer: ExoPlayer
    private lateinit var mediaSession: MediaSession
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) { Log.e(TAG, "!!! ExoPlayer ERROR !!!", error) }
        override fun onIsPlayingChanged(isPlaying: Boolean) { if (!isPlaying) saveCurrentProgress() }
    }


    private inner class AudiobookSessionCallback : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(Player.COMMAND_SEEK_TO_NEXT)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                .add(Player.COMMAND_SEEK_FORWARD)
                .add(Player.COMMAND_SEEK_BACK)
                .add(SessionCommand(PlaybackContract.COMMAND_LOAD_BOOK_AND_PLAY, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .build()
        }

        override fun onPlaybackResumption(mediaSession: MediaSession, controller: MediaSession.ControllerInfo): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            if (exoPlayer.mediaItemCount > 0) {
                val mediaItems = List(exoPlayer.mediaItemCount) { exoPlayer.getMediaItemAt(it) }
                return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(mediaItems, exoPlayer.currentMediaItemIndex, exoPlayer.currentPosition))
            }
            return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(emptyList(), C.INDEX_UNSET, C.TIME_UNSET))
        }

        override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
            if (customCommand.customAction == PlaybackContract.COMMAND_LOAD_BOOK_AND_PLAY) {
                args.getString(PlaybackContract.KEY_BOOK_ID)?.let { loadBook(it) }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
        private fun loadBook(bookId: String) {
            serviceScope.launch(Dispatchers.IO) {
                val book = LocalFileScanner(applicationContext).scanForAudiobooks().find { it.id == bookId } ?: return@launch
                val fileUri = Uri.parse(book.chapters.first().fileUri)
                val progress = AppDatabase.getInstance(applicationContext).progressDao().loadProgress(bookId)

                val mediaMetadata = MediaMetadata.Builder()
                    .setTitle(book.title)
                    .setArtist(book.author)
                    .setAlbumTitle(book.id)
                    .build()

                val mediaItem = MediaItem.Builder().setMediaId(book.id).setUri(fileUri).setMediaMetadata(mediaMetadata).build()

                withContext(Dispatchers.Main) {
                    exoPlayer.stop()
                    exoPlayer.clearMediaItems()
                    exoPlayer.setMediaItem(mediaItem, progress?.playbackPositionMs ?: 0L)
                    exoPlayer.prepare()
                    exoPlayer.play()
                }
            }
        }
    }
    private fun saveCurrentProgress() {
        val bookId = exoPlayer.currentMediaItem?.mediaId ?: return
        if (exoPlayer.currentPosition <= 0) return
        val progress = BookProgressEntity(bookId = bookId, playbackPositionMs = exoPlayer.currentPosition)
        serviceScope.launch(Dispatchers.IO) {
            AppDatabase.getInstance(applicationContext).progressDao().saveProgress(progress)
        }
    }
    override fun onCreate() {
        super.onCreate()
        val audioAttributes = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build()
        exoPlayer = ExoPlayer.Builder(this).setAudioAttributes(audioAttributes, true).build()

        // --- BATTERY PERFORMANCE: ENABLE AUDIO OFFLOAD ---
        val audioOffloadPreferences = TrackSelectionParameters.AudioOffloadPreferences.Builder()
            .setAudioOffloadMode(TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED)
            .setIsGaplessSupportRequired(true) // Good for audiobooks with multiple files
            .build()

        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
            .buildUpon()
            .setAudioOffloadPreferences(audioOffloadPreferences)
            .build()

        exoPlayer.addListener(playerListener)
        mediaSession = MediaSession.Builder(this, exoPlayer).setCallback(AudiobookSessionCallback()).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        saveCurrentProgress()
        serviceScope.cancel()
        mediaSession.release()
        exoPlayer.removeListener(playerListener)
        exoPlayer.release()
        super.onDestroy()
    }
}
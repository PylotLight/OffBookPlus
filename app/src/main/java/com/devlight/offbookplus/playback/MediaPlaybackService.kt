@file:OptIn(UnstableApi::class)

package com.devlight.offbookplus.playback

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
import com.devlight.offbookplus.data.PlaybackProgressEntity
import com.devlight.offbookplus.model.MediaType
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "MediaPlaybackService"

class MediaPlaybackService : MediaSessionService() {

    private lateinit var exoPlayer: ExoPlayer
    private lateinit var mediaSession: MediaSession
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) { Log.e(TAG, "!!! ExoPlayer ERROR !!!", error) }
        override fun onIsPlayingChanged(isPlaying: Boolean) { if (!isPlaying) saveCurrentProgress() }
    }


    private inner class MediaSessionCallback : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(Player.COMMAND_SEEK_TO_NEXT)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                .add(Player.COMMAND_SEEK_FORWARD)
                .add(Player.COMMAND_SEEK_BACK)
                .add(SessionCommand(PlaybackContract.COMMAND_LOAD_MEDIA_AND_PLAY, Bundle.EMPTY))
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
            if (customCommand.customAction == PlaybackContract.COMMAND_LOAD_MEDIA_AND_PLAY) {
                val mediaId = args.getString(PlaybackContract.KEY_MEDIA_ID)
                val mediaTypeString = args.getString(PlaybackContract.KEY_MEDIA_TYPE)
                val mediaType = try { MediaType.valueOf(mediaTypeString ?: "AUDIOBOOKS") } catch (e: IllegalArgumentException) { MediaType.AUDIOBOOKS }

                if (mediaId != null) {
                    loadPlaylistFor(mediaId, mediaType)
                }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
        private fun loadPlaylistFor(bookId: String, mediaType: MediaType) {
            serviceScope.launch {
                val (playlistItems, progress, startIndex) = withContext(Dispatchers.IO) {
                    val db = AppDatabase.getInstance(applicationContext)
                    val selectedItemEntity = db.mediaItemDao().getItemsByMediaType(mediaType.name).find { it.id == bookId }
                    if (selectedItemEntity == null) return@withContext null

                    val items = db.mediaItemDao().getItemsByPlaylistId(selectedItemEntity.playlistId)
                    val prog = db.progressDao().loadProgress(selectedItemEntity.playlistId)
                    val startIdx = items.indexOfFirst { it.id == selectedItemEntity.id }.coerceAtLeast(0)

                    Triple(items, prog, startIdx)
                } ?: return@launch

                if (playlistItems.isEmpty()) return@launch
                val mediaItems = playlistItems.map { item ->
                    val metadata = MediaMetadata.Builder()
                        .setAlbumTitle(item.playlistId)
                        .setTitle(item.title)
                        .setArtist(item.artist)
                        .setTrackNumber(item.trackNumber)
                        .setExtras(Bundle().apply { putString("MEDIA_TYPE", item.mediaType.name) })
                        .build()
                    MediaItem.Builder()
                        .setMediaId(item.id)
                        .setUri(item.fileUri)
                        .setMediaMetadata(metadata)
                        .build()
                }

                exoPlayer.stop()
                exoPlayer.clearMediaItems()
                exoPlayer.setMediaItems(mediaItems, progress?.trackIndex ?: startIndex, progress?.playbackPositionMs ?: 0L)
                exoPlayer.prepare()
                exoPlayer.play()
            }
        }
    }
    private fun saveCurrentProgress() {
        val mediaItem = exoPlayer.currentMediaItem ?: return
        val mediaTypeString = mediaItem.mediaMetadata.extras?.getString("MEDIA_TYPE")
        val mediaType = try { MediaType.valueOf(mediaTypeString ?: "") } catch (e: Exception) { null }

        if (mediaType != MediaType.AUDIOBOOKS) {
            return
        }

        val playlistId = mediaItem.mediaMetadata.albumTitle?.toString() ?: return
        if (exoPlayer.currentPosition > 0 && exoPlayer.currentPosition < exoPlayer.duration - 1000) {
            val progress = PlaybackProgressEntity(
                playlistId = playlistId,
                trackIndex = exoPlayer.currentMediaItemIndex,
                playbackPositionMs = exoPlayer.currentPosition
            )
            serviceScope.launch(Dispatchers.IO) {
                AppDatabase.getInstance(applicationContext).progressDao().saveProgress(progress)
                Log.d(TAG, "Saved Audiobook progress for '$playlistId'")
            }
        }
    }
    override fun onCreate() {
        super.onCreate()
        val audioAttributes = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build()
        exoPlayer = ExoPlayer.Builder(this).setAudioAttributes(audioAttributes, true).build()

        val audioOffloadPreferences = TrackSelectionParameters.AudioOffloadPreferences.Builder()
            .setAudioOffloadMode(TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED)
            .setIsGaplessSupportRequired(true)
            .build()

        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
            .buildUpon()
            .setAudioOffloadPreferences(audioOffloadPreferences)
            .build()

        exoPlayer.addListener(playerListener)
        mediaSession = MediaSession.Builder(this, exoPlayer).setCallback(MediaSessionCallback()).build()
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
@file:OptIn(UnstableApi::class)

package com.devlight.offbookplus.playback

import android.os.Bundle
import android.os.SystemClock
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
import com.devlight.offbookplus.data.PlayHistoryRecorder
import com.devlight.offbookplus.data.PlaybackProgressEntity
import com.devlight.offbookplus.model.MediaType
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "MediaPlaybackService"
private const val HISTORY_FLUSH_INTERVAL_MS = 60_000L
private const val HISTORY_TICK_MS = 30_000L
private const val HISTORY_MIN_RECORD_MS = 1_000L

class MediaPlaybackService : MediaSessionService() {

    private lateinit var exoPlayer: ExoPlayer
    private lateinit var mediaSession: MediaSession
    private lateinit var historyRecorder: PlayHistoryRecorder
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    // History writes use their own scope so the final flush survives service destruction.
    private val historyScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // --- Play history accounting (battery-conscious: no per-second writes) ---
    private var currentSessionItemId: String? = null
    private var playingSegmentStartElapsed = 0L
    private var pendingPlayTimeMs = 0L
    private var lastPlayCountedItemId: String? = null
    private var periodicFlushJob: kotlinx.coroutines.Job? = null

    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) { Log.e(TAG, "!!! ExoPlayer ERROR !!!", error) }
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                countPlayForCurrentMediaItem()
                beginPlaySegment()
            } else {
                endPlaySegment()
                saveCurrentProgress()
            }
        }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            endPlaySegment()
            currentSessionItemId = mediaItem?.mediaId
            pendingPlayTimeMs = 0L
            if (exoPlayer.isPlaying) {
                beginPlaySegment()
                countPlayForCurrentMediaItem()
            }
            saveCurrentProgress()
        }
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) { saveCurrentProgress() }
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
            
            val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            serviceScope.launch {
                val result = withContext(Dispatchers.IO) {
                    val db = AppDatabase.getInstance(applicationContext)
                    val recentProgress = db.progressDao().getMostRecentProgress()
                    if (recentProgress != null) {
                        val items = db.mediaItemDao().getItemsByPlaylistId(recentProgress.playlistId)
                        if (items.isNotEmpty()) {
                            val mediaItems = items.map { item ->
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
                            // Restore shuffle mode
                            withContext(Dispatchers.Main) {
                                exoPlayer.shuffleModeEnabled = recentProgress.shuffleModeEnabled
                            }
                            return@withContext MediaSession.MediaItemsWithStartPosition(mediaItems, recentProgress.trackIndex, recentProgress.playbackPositionMs)
                        }
                    }
                    MediaSession.MediaItemsWithStartPosition(emptyList(), C.INDEX_UNSET, C.TIME_UNSET)
                }
                future.set(result)
            }
            return future
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
                
                val actualTrackIndex = if (progress != null && (startIndex == progress.trackIndex || startIndex == 0)) {
                    progress.trackIndex
                } else {
                    startIndex
                }
                val actualPosition = if (progress != null && actualTrackIndex == progress.trackIndex) {
                    progress.playbackPositionMs
                } else {
                    0L
                }
                
                exoPlayer.shuffleModeEnabled = progress?.shuffleModeEnabled ?: false
                exoPlayer.setMediaItems(mediaItems, actualTrackIndex, actualPosition)
                exoPlayer.prepare()
                exoPlayer.play()
            }
        }
    }
    private fun saveCurrentProgress() {
        val mediaItem = exoPlayer.currentMediaItem ?: return
        val mediaTypeString = mediaItem.mediaMetadata.extras?.getString("MEDIA_TYPE")
        val mediaType = try { MediaType.valueOf(mediaTypeString ?: "") } catch (e: Exception) { null }

        val playlistId = mediaItem.mediaMetadata.albumTitle?.toString() ?: return
        val position = if (exoPlayer.currentPosition > 0 && (exoPlayer.duration <= 0 || exoPlayer.currentPosition < exoPlayer.duration - 1000)) {
            exoPlayer.currentPosition
        } else {
            0L
        }
        val progress = PlaybackProgressEntity(
            playlistId = playlistId,
            trackIndex = exoPlayer.currentMediaItemIndex,
            playbackPositionMs = position,
            shuffleModeEnabled = exoPlayer.shuffleModeEnabled
        )
        serviceScope.launch(Dispatchers.IO) {
            AppDatabase.getInstance(applicationContext).progressDao().saveProgress(progress)
            Log.d(TAG, "Saved $mediaType progress for '$playlistId'")
        }
    }

    // --- Play history helpers ---

    private fun elapsedPlayMs(): Long {
        if (playingSegmentStartElapsed == 0L) return 0L
        return SystemClock.elapsedRealtime() - playingSegmentStartElapsed
    }

    private fun totalAccruedMs(): Long = pendingPlayTimeMs + elapsedPlayMs()

    private fun beginPlaySegment() {
        playingSegmentStartElapsed = SystemClock.elapsedRealtime()
    }

    /**
     * Finishes the current play segment: persists accrued time for the current item.
     * If playback is still going (e.g. mid-transition) the caller restarts a segment.
     */
    private fun endPlaySegment() {
        val itemId = currentSessionItemId
        val accrued = totalAccruedMs()
        playingSegmentStartElapsed = 0L
        if (itemId == null) {
            pendingPlayTimeMs = 0L
            return
        }
        if (accrued >= HISTORY_MIN_RECORD_MS) {
            persistPlayTime(itemId, accrued)
            pendingPlayTimeMs = 0L
        } else {
            pendingPlayTimeMs = accrued
        }
    }

    /**
     * Periodic safety-net so long uninterrupted sessions still persist time at least once
     * a minute (prevents losing lots of playtime if the process is ever killed).
     */
    private fun periodicHistoryFlush() {
        val itemId = currentSessionItemId ?: return
        if (playingSegmentStartElapsed == 0L) return
        val accrued = totalAccruedMs()
        if (accrued >= HISTORY_FLUSH_INTERVAL_MS) {
            persistPlayTime(itemId, accrued)
            pendingPlayTimeMs = 0L
            playingSegmentStartElapsed = SystemClock.elapsedRealtime()
        }
    }

    private fun persistPlayTime(itemId: String, timeMs: Long) {
        if (timeMs <= 0) return
        historyScope.launch {
            try {
                historyRecorder.addPlayTime(itemId, timeMs)
                Log.d(TAG, "History += ${timeMs}ms for '$itemId'")
            } catch (e: Exception) {
                Log.w(TAG, "Play history write failed", e)
            }
        }
    }

    private fun countPlayForCurrentMediaItem() {
        val mediaItem = exoPlayer.currentMediaItem ?: return
        val mediaId = mediaItem.mediaId
        if (lastPlayCountedItemId == mediaId) return
        lastPlayCountedItemId = mediaId
        val metadata = mediaItem.mediaMetadata
        val mediaType = try {
            metadata.extras?.getString("MEDIA_TYPE")?.let { MediaType.valueOf(it) }
        } catch (e: Exception) { null } ?: MediaType.AUDIOBOOKS
        historyScope.launch {
            try {
                historyRecorder.recordPlayStarted(
                    mediaId = mediaId,
                    playlistId = metadata.albumTitle?.toString() ?: "",
                    mediaType = mediaType,
                    title = metadata.title?.toString() ?: "Unknown",
                    artist = metadata.artist?.toString() ?: ""
                )
                Log.d(TAG, "Play counted for '$mediaId'")
            } catch (e: Exception) {
                Log.w(TAG, "Play count write failed", e)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        historyRecorder = PlayHistoryRecorder(AppDatabase.getInstance(applicationContext))
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

        periodicFlushJob = serviceScope.launch {
            while (isActive) {
                delay(HISTORY_TICK_MS)
                periodicHistoryFlush()
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        endPlaySegment()
        periodicFlushJob?.cancel()
        saveCurrentProgress()
        serviceScope.cancel()
        mediaSession.release()
        exoPlayer.removeListener(playerListener)
        exoPlayer.release()
        super.onDestroy()
    }
}
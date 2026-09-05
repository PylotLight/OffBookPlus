@file:OptIn(UnstableApi::class)

package com.devlight.offbookplus.playback

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
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
import com.devlight.offbookplus.data.MediaItemEntity
import com.devlight.offbookplus.data.PlayHistoryRecorder
import com.devlight.offbookplus.data.PlaybackProgressEntity
import com.devlight.offbookplus.data.PlaybackQueueEntity
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val TAG = "MediaPlaybackService"
private const val HISTORY_FLUSH_INTERVAL_MS = 60_000L
private const val HISTORY_TICK_MS = 30_000L
private const val HISTORY_MIN_RECORD_MS = 1_000L

class MediaPlaybackService : MediaSessionService() {

    private lateinit var exoPlayer: ExoPlayer
    private lateinit var mediaSession: MediaSession
    private lateinit var historyRecorder: PlayHistoryRecorder
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    // History/queue writes use their own scope so final flushes survive service destruction.
    private val historyScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // --- Active queue state: the app owns playback order (not ExoPlayer's shuffle),
    // --- so queues survive library switches and process restarts.
    private var activeQueueId: String? = null
    private var activeQueueMediaType: MediaType = MediaType.AUDIOBOOKS
    private var activeQueueIds: List<String> = emptyList()
    private var activeQueueShuffle: Boolean = false
    private var activeEntitiesById: Map<String, MediaItemEntity> = emptyMap()

    // --- Play history accounting (battery-conscious: no per-second writes) ---
    private var currentSessionItemId: String? = null
    private var currentSessionItemType: MediaType? = null
    private var playingSegmentStartElapsed = 0L
    private var pendingPlayTimeMs = 0L
    private var pendingFinish: MediaMeta? = null
    private var periodicFlushJob: kotlinx.coroutines.Job? = null

    private data class MediaMeta(
        val id: String,
        val playlistId: String,
        val mediaType: MediaType,
        val title: String,
        val artist: String
    )

    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) { Log.e(TAG, "!!! ExoPlayer ERROR !!!", error) }
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                finishPendingItem()
            }
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                noteCurrentItem()
                beginPlaySegment()
            } else {
                endPlaySegment()
                saveCurrentProgress()
                persistCurrentQueue()
            }
        }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            endPlaySegment()
            currentSessionItemId = mediaItem?.mediaId
            currentSessionItemType = mediaItem?.mediaMetadata?.extras
                ?.getString(PlaybackContract.EXTRA_MEDIA_TYPE)?.let {
                    try { MediaType.valueOf(it) } catch (e: Exception) { null }
                }
            pendingPlayTimeMs = 0L
            if (exoPlayer.isPlaying) {
                beginPlaySegment()
                noteCurrentItem()
            }
            saveCurrentProgress()
            persistCurrentQueue()
        }
        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)
        }
    }

    /** Remembers what is playing so it can be counted if it reaches its natural end. */
    private fun noteCurrentItem() {
        val mediaItem = exoPlayer.currentMediaItem ?: return
        val mediaId = mediaItem.mediaId
        if (mediaId.isBlank()) return
        if (pendingFinish?.id == mediaId) return
        val metadata = mediaItem.mediaMetadata
        val mediaType = try {
            metadata.extras?.getString(PlaybackContract.EXTRA_MEDIA_TYPE)?.let { MediaType.valueOf(it) }
        } catch (e: Exception) { null } ?: MediaType.AUDIOBOOKS
        currentSessionItemType = mediaType
        pendingFinish = MediaMeta(
            id = mediaId,
            playlistId = metadata.albumTitle?.toString() ?: "",
            mediaType = mediaType,
            title = metadata.title?.toString() ?: "Unknown",
            artist = metadata.artist?.toString() ?: ""
        )
    }

    /** History only counts music items that finished playing to their natural end. */
    private fun finishPendingItem() {
        val meta = pendingFinish ?: return
        pendingFinish = null
        if (meta.mediaType != MediaType.MUSIC) return
        historyScope.launch {
            try {
                historyRecorder.recordItemFinished(
                    mediaId = meta.id,
                    playlistId = meta.playlistId,
                    mediaType = meta.mediaType,
                    title = meta.title,
                    artist = meta.artist
                )
                Log.d(TAG, "Finished counted for '${meta.id}'")
            } catch (e: Exception) {
                Log.w(TAG, "Play count write failed", e)
            }
        }
    }

    private lateinit var audioManager: AudioManager
    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            val removedOutput = removedDevices.any {
                !it.isSource && (it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    it.type == AudioDeviceInfo.TYPE_USB_HEADSET)
            }
            if (removedOutput && exoPlayer.isPlaying) {
                exoPlayer.pause()
                Log.i(TAG, "Paused: active audio output device removed")
            }
        }

        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            if (addedDevices.any { !it.isSource && it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }) {
                Log.i(TAG, "Bluetooth A2DP device connected")
            }
        }
    }

    private val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY && exoPlayer.isPlaying) {
                exoPlayer.pause()
                Log.i(TAG, "Paused: audio becoming noisy (device disconnected)")
            }
        }
    }

    /** Reads the user-configured skip increments so BT FF/REW match in-app button behaviour. */
    private fun readSeekIncrementMs(key: String, defaultMs: Long): Long =
        getSharedPreferences(PlaybackContract.PREFS_NAME, MODE_PRIVATE)
            .getLong(key, defaultMs)

    private data class QueueLoad(
        val queueId: String,
        val mediaType: MediaType,
        val items: List<MediaItemEntity>,
        val startIndex: Int,
        val startPositionMs: Long,
        val shuffle: Boolean
    )

    private inner class MediaSessionCallback : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(Player.COMMAND_SEEK_TO_NEXT)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                .add(Player.COMMAND_SEEK_FORWARD)
                .add(Player.COMMAND_SEEK_BACK)
                .add(SessionCommand(PlaybackContract.COMMAND_LOAD_MEDIA_AND_PLAY, Bundle.EMPTY))
                .add(SessionCommand(PlaybackContract.COMMAND_RESUME_LAST_QUEUE, Bundle.EMPTY))                .add(SessionCommand(PlaybackContract.COMMAND_SHUFFLE_MUSIC, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .build()
        }

        override fun onPlaybackResumption(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, isForPlayback: Boolean): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            if (exoPlayer.mediaItemCount > 0) {
                // STATE_ENDED means the queue played out: restart from the top, not the end.
                val index = if (exoPlayer.playbackState == Player.STATE_ENDED) 0 else exoPlayer.currentMediaItemIndex
                val position = if (exoPlayer.playbackState == Player.STATE_ENDED) 0L else exoPlayer.currentPosition
                val mediaItems = List(exoPlayer.mediaItemCount) { exoPlayer.getMediaItemAt(it) }
                return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(mediaItems, index, position))
            }

            val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            serviceScope.launch {
                val result = withContext(Dispatchers.IO) { buildResumptionFromDb() }
                future.set(result)
            }
            return future
        }

        override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                PlaybackContract.COMMAND_LOAD_MEDIA_AND_PLAY -> {
                    val mediaId = args.getString(PlaybackContract.KEY_MEDIA_ID)
                    val mediaTypeString = args.getString(PlaybackContract.KEY_MEDIA_TYPE)
                    val mediaType = try { MediaType.valueOf(mediaTypeString ?: "AUDIOBOOKS") } catch (e: IllegalArgumentException) { MediaType.AUDIOBOOKS }
                    if (mediaId != null) {
                        loadPlaylistFor(mediaId, mediaType)
                    } else {
                        Log.w(TAG, "LOAD_MEDIA_AND_PLAY: mediaId null, ignoring")
                    }
                }
                PlaybackContract.COMMAND_RESUME_LAST_QUEUE -> resumeLastQueue(args.getString(PlaybackContract.KEY_MEDIA_TYPE))
                PlaybackContract.COMMAND_SHUFFLE_MUSIC -> shuffleAllMusic()
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        private fun loadPlaylistFor(bookId: String, mediaType: MediaType) {
            serviceScope.launch {
                val db = AppDatabase.getInstance(applicationContext)
                val selectedItemEntity = withContext(Dispatchers.IO) { resolveItem(db, bookId, mediaType) }
                if (selectedItemEntity == null) {
                    Log.w(TAG, "No media item found for id=$bookId type=$mediaType")
                    return@launch
                }
                val playlistId = selectedItemEntity.playlistId

                // Already the active queue: just move within it (no reload, no reshuffle).
                if (playlistId == activeQueueId && exoPlayer.mediaItemCount > 0) {
                    val tappedIndex = activeQueueIds.indexOf(selectedItemEntity.id)
                    if (tappedIndex >= 0) {
                        if (tappedIndex == exoPlayer.currentMediaItemIndex) {
                            exoPlayer.play()
                        } else {
                            exoPlayer.seekTo(tappedIndex, 0L)
                            exoPlayer.play()
                        }
                        return@launch
                    }
                }

                // Restore a persisted queue for this playlist if one exists.
                val restored = withContext(Dispatchers.IO) {
                    restoreQueue(db, playlistId, mediaType, selectedItemEntity.id)
                }
                if (restored != null) {
                    applyQueue(restored)
                    return@launch
                }

                // Build a fresh queue: app-owned order starts sequential so the tapped
                // track plays at its natural position; shuffle can be toggled in-player.
                val items = withContext(Dispatchers.IO) { db.mediaItemDao().getItemsByPlaylistId(playlistId) }
                if (items.isEmpty()) {
                    Log.w(TAG, "No items for playlist $playlistId, abort")
                    return@launch
                }
                applyQueue(QueueLoad(playlistId, mediaType, items, items.indexOfFirst { it.id == selectedItemEntity.id }.coerceAtLeast(0), 0L, false))
            }
        }

        private fun resumeLastQueue(mediaTypeString: String?) {
            val wantedType = mediaTypeString?.let {
                try { MediaType.valueOf(it) } catch (e: IllegalArgumentException) { null }
            }
            if (exoPlayer.mediaItemCount > 0) {
                // Something is loaded: playing is enough for a generic resume, and for a
                // same-library resume; a different library cuts over to its saved queue.
                if (wantedType == null || activeQueueMediaType == wantedType) {
                    exoPlayer.play()
                    return
                }
            }
            serviceScope.launch {
                val db = AppDatabase.getInstance(applicationContext)
                val load = withContext(Dispatchers.IO) {
                    val saved = if (wantedType != null) {
                        // Restore this library's own saved queue, falling back to the most
                        // recent one of that type (e.g. music survives podcast detours).
                        db.playbackQueueDao().getAll()
                            .filter { it.mediaType == wantedType }
                            .maxByOrNull { it.lastUpdatedTimestamp }
                    } else {
                        db.playbackQueueDao().getMostRecent()
                    } ?: return@withContext null
                    queueLoadFromSaved(db, saved)
                }
                if (load == null) {
                    Log.w(TAG, "RESUME_LAST_QUEUE: no restorable queue for type=$wantedType")
                    return@launch
                }
                applyQueue(load)
            }
        }

        /** Builds a fresh shuffled queue from all music tracks and starts a random one. */
        private fun shuffleAllMusic() {
            serviceScope.launch {
                val db = AppDatabase.getInstance(applicationContext)
                val items = withContext(Dispatchers.IO) {
                    db.mediaItemDao().getItemsByPlaylistId(PlaybackContract.MUSIC_QUEUE_ID)
                }
                if (items.isEmpty()) {
                    Log.w(TAG, "SHUFFLE_MUSIC: no music items found")
                    return@launch
                }
                val shuffled = items.shuffled()
                applyQueue(
                    QueueLoad(
                        queueId = PlaybackContract.MUSIC_QUEUE_ID,
                        mediaType = MediaType.MUSIC,
                        items = shuffled,
                        startIndex = 0,
                        startPositionMs = 0L,
                        shuffle = true
                    )
                )
            }
        }
    }

    private suspend fun resolveItem(db: AppDatabase, bookId: String, mediaType: MediaType): MediaItemEntity? {
        val allForType = db.mediaItemDao().getItemsByMediaType(mediaType.name)
        // DB ids are stored as Uri.fromFile().toString() which percent-encodes spaces as %20;
        // compare after Uri decoding so both forms match.
        val decodedBookId = android.net.Uri.decode(bookId)
        return allForType.find {
            it.id == bookId || android.net.Uri.decode(it.id) == decodedBookId || it.id == android.net.Uri.encode(bookId)
        }
    }

    private suspend fun queueLoadFromSaved(db: AppDatabase, saved: PlaybackQueueEntity): QueueLoad? {
        val savedIds = runCatching { Json.decodeFromString<List<String>>(saved.orderedIds) }.getOrNull()
        if (savedIds.isNullOrEmpty()) return null
        val entities = db.mediaItemDao().getItemsByIds(savedIds).associateBy { it.id }
        val ordered = savedIds.mapNotNull { entities[it] }
        if (ordered.isEmpty()) return null
        val index = saved.currentIndex.coerceIn(0, ordered.size - 1)
        return QueueLoad(
            queueId = saved.queueId,
            mediaType = saved.mediaType,
            items = ordered,
            startIndex = index,
            startPositionMs = saved.positionMs.coerceAtLeast(0L),
            shuffle = saved.shuffleEnabled
        )
    }

    private suspend fun restoreQueue(db: AppDatabase, playlistId: String, mediaType: MediaType, tappedId: String): QueueLoad? {
        val saved = db.playbackQueueDao().load(playlistId) ?: return null
        val load = queueLoadFromSaved(db, saved) ?: return null
        val tappedIndex = load.items.indexOfFirst { it.id == tappedId }
        if (tappedIndex < 0) {
            // Tapped item isn't in the saved queue (library changed): caller rebuilds fresh.
            return null
        }
        // Re-tapping the saved current track resumes its position; any other tap starts at 0.
        val position = if (tappedIndex == load.startIndex) load.startPositionMs else 0L
        return load.copy(startIndex = tappedIndex, startPositionMs = position, mediaType = mediaType)
    }

    private fun applyQueue(load: QueueLoad) {
        // Switching libraries: snapshot the outgoing queue's exact index/position so it
        // resumes later exactly as left (order + shuffle are already persisted).
        if (activeQueueId != null && activeQueueId != load.queueId && exoPlayer.mediaItemCount > 0) {
            persistCurrentQueue()
        }
        activeQueueId = load.queueId
        activeQueueMediaType = load.mediaType
        activeQueueIds = load.items.map { it.id }
        activeQueueShuffle = load.shuffle
        activeEntitiesById = load.items.associateBy { it.id }

        val index = load.startIndex.coerceIn(0, (load.items.size - 1).coerceAtLeast(0))
        setPlayerItems(load.queueId, load.items, index, load.startPositionMs.coerceAtLeast(0L))
        persistCurrentQueue()
        Log.i(TAG, "applyQueue '${load.queueId}' size=${load.items.size} index=$index pos=${load.startPositionMs} shuffle=${load.shuffle}")
    }

    private fun setPlayerItems(queueId: String, entities: List<MediaItemEntity>, index: Int, positionMs: Long) {
        val mediaItems = entities.map { it.toMediaItem(queueId, activeQueueShuffle) }
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        // Order is owned by the app (persisted in playback_queue), so ExoPlayer's own
        // shuffle mode stays off and can never reshuffle the queue behind our back.
        exoPlayer.shuffleModeEnabled = false
        exoPlayer.setMediaItems(mediaItems, index, positionMs)
        exoPlayer.prepare()
        applySavedSpeed(activeQueueMediaType)
        exoPlayer.play()
    }

    private fun MediaItemEntity.toMediaItem(queueId: String, shuffle: Boolean): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setAlbumTitle(queueId)
            .setTitle(title)
            .setArtist(artist)
            .setTrackNumber(trackNumber)
            .setExtras(Bundle().apply {
                putString(PlaybackContract.EXTRA_MEDIA_TYPE, mediaType.name)
                putString(PlaybackContract.EXTRA_QUEUE_ID, queueId)
                putBoolean(PlaybackContract.EXTRA_SHUFFLE_ENABLED, shuffle)
            })
            .build()
        return MediaItem.Builder()
            .setMediaId(id)
            .setUri(fileUri)
            .setMediaMetadata(metadata)
            .build()
    }

    private fun applySavedSpeed(mediaType: MediaType) {
        val speed = getSharedPreferences(PlaybackContract.PREFS_NAME, MODE_PRIVATE)
            .getFloat(PlaybackContract.KEY_SPEED_PREFIX + mediaType.name, 1.0f)
        exoPlayer.setPlaybackParameters(PlaybackParameters(speed))
    }

    private suspend fun buildResumptionFromDb(): MediaSession.MediaItemsWithStartPosition {
        val db = AppDatabase.getInstance(applicationContext)
        val saved = db.playbackQueueDao().getMostRecent()
        if (saved != null) {
            val load = queueLoadFromSaved(db, saved)
            if (load != null) {
                val mediaItems = load.items.map { it.toMediaItem(load.queueId, load.shuffle) }
                return MediaSession.MediaItemsWithStartPosition(mediaItems, load.startIndex, load.startPositionMs)
            }
        }
        // Fallback for installs upgraded from before the queue table existed.
        val recentProgress = db.progressDao().getMostRecentProgress()
            ?: return MediaSession.MediaItemsWithStartPosition(emptyList(), C.INDEX_UNSET, C.TIME_UNSET)
        val items = db.mediaItemDao().getItemsByPlaylistId(recentProgress.playlistId)
        if (items.isEmpty()) return MediaSession.MediaItemsWithStartPosition(emptyList(), C.INDEX_UNSET, C.TIME_UNSET)
        val mediaItems = items.map { it.toMediaItem(recentProgress.playlistId, false) }
        val index = recentProgress.trackIndex.coerceIn(0, items.size - 1)
        return MediaSession.MediaItemsWithStartPosition(mediaItems, index, recentProgress.playbackPositionMs.coerceAtLeast(0L))
    }

    private fun persistCurrentQueue() {
        val queueId = activeQueueId ?: return
        if (activeQueueIds.isEmpty()) return
        val index = exoPlayer.currentMediaItemIndex.coerceAtLeast(0)
        val position = if (exoPlayer.currentPosition > 0 && (exoPlayer.duration <= 0 || exoPlayer.currentPosition < exoPlayer.duration - 1000)) {
            exoPlayer.currentPosition
        } else {
            0L
        }
        val row = PlaybackQueueEntity(
            queueId = queueId,
            mediaType = activeQueueMediaType,
            orderedIds = Json.encodeToString(activeQueueIds),
            currentIndex = index,
            positionMs = position,
            shuffleEnabled = activeQueueShuffle
        )
        historyScope.launch {
            try {
                AppDatabase.getInstance(applicationContext).playbackQueueDao().save(row)
            } catch (e: Exception) {
                Log.w(TAG, "Queue save failed", e)
            }
        }
    }

    private fun saveCurrentProgress() {
        val mediaItem = exoPlayer.currentMediaItem ?: return
        val mediaTypeString = mediaItem.mediaMetadata.extras?.getString(PlaybackContract.EXTRA_MEDIA_TYPE)
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
            shuffleModeEnabled = activeQueueShuffle
        )
        historyScope.launch(Dispatchers.IO) {
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
        if (itemId == null || currentSessionItemType != MediaType.MUSIC) {
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
        if (currentSessionItemType != MediaType.MUSIC) return
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

    override fun onCreate() {
        super.onCreate()
        historyRecorder = PlayHistoryRecorder(AppDatabase.getInstance(applicationContext))
        historyScope.launch {
            runCatching { AppDatabase.getInstance(applicationContext).playHistoryDao().deleteNonMusic() }
        }
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val audioAttributes = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build()
        exoPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .setSeekBackIncrementMs(readSeekIncrementMs(PlaybackContract.KEY_REWIND_MS, PlaybackContract.DEFAULT_REWIND_MS))
            .setSeekForwardIncrementMs(readSeekIncrementMs(PlaybackContract.KEY_FORWARD_MS, PlaybackContract.DEFAULT_FORWARD_MS))
            .build()

        val audioOffloadPreferences = TrackSelectionParameters.AudioOffloadPreferences.Builder()
            .setAudioOffloadMode(TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED)
            .setIsGaplessSupportRequired(true)
            .build()

        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
            .buildUpon()
            .setAudioOffloadPreferences(audioOffloadPreferences)
            .build()

        exoPlayer.addListener(playerListener)

        // Tapping the system media notification (swipe-down shade) returns to the player.
        val sessionActivity = packageManager.getLaunchIntentForPackage(packageName)?.let { intent ->
            intent.putExtra(PlaybackContract.EXTRA_OPEN_PLAYER, true)
            PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        mediaSession = MediaSession.Builder(this, exoPlayer)
            .setCallback(MediaSessionCallback())
            .apply { sessionActivity?.let { setSessionActivity(it) } }
            .build()

        val noisyFilter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(becomingNoisyReceiver, noisyFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(becomingNoisyReceiver, noisyFilter)
        }

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
        persistCurrentQueue()
        serviceScope.cancel()
        runCatching { unregisterReceiver(becomingNoisyReceiver) }
        audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
        mediaSession.release()
        exoPlayer.removeListener(playerListener)
        exoPlayer.release()
        super.onDestroy()
    }
}

@file:OptIn(UnstableApi::class)

package com.devlight.offbookplus.data

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.media3.common.util.UnstableApi
import com.devlight.offbookplus.model.MediaType
import com.devlight.offbookplus.playback.PlaybackContract
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

private const val TAG = "LocalFileScanner"

class LocalFileScanner(private val context: Context) {
    private fun getValidExtensions(mediaType: MediaType): Set<String> {
        return when (mediaType) {
            MediaType.AUDIOBOOKS -> setOf("m4a", "m4b")
            MediaType.PODCASTS, MediaType.MUSIC -> setOf("mp3", "m4a", "ogg", "opus", "flac")
        }
    }

    private fun getMediaDirectory(mediaType: MediaType): File {
        val storageDir = Environment.getExternalStorageDirectory()
        return File(storageDir, mediaType.directoryName)
    }
    /**
     * Cheap change detection: walks the tree and stats each valid file (no metadata reads).
     * Returns the file count and a signature over every file's path, size and mtime, so
     * adds, removals, renames and replacements inside sub-folders are all detected.
     */
    fun getDirectoryState(mediaType: MediaType): Pair<Int, Long> {
        val mediaDir = getMediaDirectory(mediaType)
        val validExtensions = getValidExtensions(mediaType)

        if (!mediaDir.exists() || !mediaDir.isDirectory) {
            return Pair(0, 0L)
        }
        var count = 0
        var signature = 1125899906842597L
        mediaDir.walk()
            .filter { it.isFile && it.extension.lowercase() in validExtensions }
            .sortedBy { it.absolutePath }
            .forEach { file ->
                count++
                signature = 31 * signature + file.absolutePath.hashCode()
                signature = 31 * signature + file.length()
                signature = 31 * signature + file.lastModified()
            }
        return Pair(count, signature)
    }

    /**
     * Builds the entities for a library. Metadata is only extracted for files that are new
     * or whose size/mtime changed since the last scan; everything else comes from a small
     * per-library cache, so a rescan after adding a few files is near-instant.
     */
    fun performDeepScanFor(mediaType: MediaType): List<MediaItemEntity> {
        val items = mutableListOf<MediaItemEntity>()
        val mediaDir = getMediaDirectory(mediaType)
        val validExtensions = getValidExtensions(mediaType)

        if (!mediaDir.exists() || !mediaDir.isDirectory) {
            Log.e(TAG, "Directory not found: ${mediaDir.absolutePath}")
            return emptyList()
        }

        Log.i(TAG, "Scanning directory: ${mediaDir.absolutePath} for types: $validExtensions")

        val cacheFile = cacheFileFor(mediaType)
        val oldCache = readCache(cacheFile)
        val newCache = HashMap<String, CachedMeta>(oldCache.size)
        var extracted = 0

        mediaDir.walk()
            .filter { it.isFile && it.extension.lowercase() in validExtensions }
            .sortedBy { it.name }
            .forEachIndexed { index, file ->
                val path = file.absolutePath
                val size = file.length()
                val modified = file.lastModified()
                val cached = oldCache[path]?.takeIf { it.size == size && it.modified == modified }
                val meta = cached ?: run {
                    extracted++
                    val data = runBlocking { ChapterExtractor.extract(context, Uri.fromFile(file)) }
                    CachedMeta(size = size, modified = modified, title = data?.title, artist = data?.artist)
                }
                newCache[path] = meta
                val fileUri = Uri.fromFile(file).toString()

                val playlistId = if (mediaType == MediaType.MUSIC) {
                    PlaybackContract.MUSIC_QUEUE_ID
                } else {
                    (file.parentFile?.name ?: "unknown_album").replace("\\s".toRegex(), "_").lowercase()
                }

                items.add(
                    MediaItemEntity(
                        id = fileUri,
                        playlistId = playlistId,
                        mediaType = mediaType,
                        title = meta.title ?: file.nameWithoutExtension,
                        artist = meta.artist ?: file.parentFile?.name ?: "Unknown Artist",
                        trackNumber = index,
                        fileUri = fileUri
                    )
                )
            }

        writeCache(cacheFile, newCache)
        Log.d(TAG, "Scan complete for ${mediaType.name}. Found ${items.size} tracks, extracted metadata for $extracted.")
        return items
    }

    @Serializable
    private data class CachedMeta(
        val size: Long,
        val modified: Long,
        val title: String? = null,
        val artist: String? = null
    )

    private fun cacheFileFor(mediaType: MediaType) =
        File(context.filesDir, "scan_cache_${mediaType.name.lowercase()}.json")

    private fun readCache(file: File): Map<String, CachedMeta> =
        runCatching {
            if (!file.exists()) emptyMap()
            else cacheJson.decodeFromString<Map<String, CachedMeta>>(file.readText())
        }.getOrElse {
            Log.w(TAG, "Scan cache unreadable, rebuilding", it)
            emptyMap()
        }

    private fun writeCache(file: File, cache: Map<String, CachedMeta>) {
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(cacheJson.encodeToString(cache))
            tmp.renameTo(file)
        }.onFailure { Log.w(TAG, "Scan cache write failed", it) }
    }

    private companion object {
        val cacheJson = Json { ignoreUnknownKeys = true }
    }
}
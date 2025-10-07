@file:OptIn(UnstableApi::class)

package com.devlight.offbookplus.data

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.media3.common.util.UnstableApi
import com.devlight.offbookplus.model.MediaType
import kotlinx.coroutines.runBlocking
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

    fun scanLibraryFor(mediaType: MediaType): List<MediaItemEntity> {
        val items = mutableListOf<MediaItemEntity>()
        val mediaDir = getMediaDirectory(mediaType)
        val validExtensions = getValidExtensions(mediaType)

        if (!mediaDir.exists() || !mediaDir.isDirectory) {
            Log.e(TAG, "Directory not found: ${mediaDir.absolutePath}")
            return emptyList()
        }

        Log.i(TAG, "Scanning directory: ${mediaDir.absolutePath} for types: $validExtensions")

        // Group all found files by their parent directory
        mediaDir.walk()
            .filter { it.isFile && it.extension.lowercase() in validExtensions }
            .sortedBy { it.name }
            .forEachIndexed { index, file ->
                val extractedData = runBlocking { ChapterExtractor.extract(context, Uri.fromFile(file)) }
                val fileUri = Uri.fromFile(file).toString()
                val playlistId = (file.parentFile?.name ?: "unknown_album").replace("\\s".toRegex(), "_").lowercase()

                items.add(
                    MediaItemEntity(
                        id = fileUri,
                        playlistId = playlistId,
                        mediaType = mediaType,
                        title = extractedData?.title ?: file.nameWithoutExtension,
                        artist = extractedData?.artist ?: file.parentFile?.name ?: "Unknown Artist",
                        trackNumber = index,
                        fileUri = fileUri
                    )
                )
            }

        Log.d(TAG, "Scan complete for ${mediaType.name}. Found ${items.size} individual tracks.")
        return items
    }
}
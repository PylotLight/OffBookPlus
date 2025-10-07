package com.devlight.offbookplus.data

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.concurrent.futures.await
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.MetadataRetriever
import androidx.media3.extractor.metadata.id3.ChapterFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext



private const val TAG = "ChapterExtractor"

// A clean data class to hold all extracted information
data class ExtractedAudiobookData(
    val title: String,
    val artist: String?,
    val chapters: List<ChapterFrame>
)

@UnstableApi
object ChapterExtractor {

    suspend fun extract(context: Context, uri: Uri): ExtractedAudiobookData? {
        return withContext(Dispatchers.IO) {
            Log.d(TAG, "--- Starting Metadata Extraction for: ${uri.lastPathSegment} ---")
            try {
                val mediaItem = MediaItem.fromUri(uri)
                val retriever = MetadataRetriever.Builder(context, mediaItem).build()
                val trackGroups = retriever.retrieveTrackGroups().await()

                val chapters = mutableListOf<ChapterFrame>()
                var title: String? = null
                var artist: String? = null

                Log.d(TAG, "Found ${trackGroups.length} track groups.")

                for (i in 0 until trackGroups.length) {
                    val format = trackGroups[i].getFormat(0)
                    // --- NEW: DETAILED TRACK GROUP LOGGING ---
                    Log.d(TAG, "  > Track Group $i Info: mimeType=${format.sampleMimeType}, id=${format.id}, language=${format.language}")
                    format.metadata?.let { metadata ->
                        Log.d(TAG, "    > Track $i has ${metadata.length()} metadata entries.")
                        for (j in 0 until metadata.length()) {
                            val entry = metadata.get(j)
                            // Log every single metadata entry's type and value
                            Log.v(TAG, "      - Entry $j: Type = ${entry.javaClass.simpleName}, Value = $entry")
                            if (entry is ChapterFrame) {
                                chapters.add(entry)
                                Log.i(TAG, "        >> Found Chapter: ID=${entry.chapterId}")
                            }
                            if (entry is TextInformationFrame) {
                                when (entry.id) {
                                    "TIT2" -> {
                                        title = entry.value
                                        Log.i(TAG, "        >> Found Title (TIT2): $title")
                                    }
                                    "TPE1" -> {
                                        artist = entry.value
                                        Log.i(TAG, "        >> Found Artist (TPE1): $artist")
                                    }
                                }
                            }
                        }
                    }
                }

                if (title != null) {
                    ExtractedAudiobookData(
                        title = title,
                        artist = artist,
                        chapters = chapters.sortedBy { it.chapterId }
                    )
                } else {
                    Log.w(TAG, "Extraction failed: Could not find a title (TIT2) for '${uri.lastPathSegment}'")
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "!!! FATAL EXTRACTION ERROR for URI: $uri", e)
                null
            }
        }
    }
}
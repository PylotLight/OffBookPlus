@file:OptIn(UnstableApi::class)

package com.devlight.offbookplus.data

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.media3.common.util.UnstableApi
import com.devlight.offbookplus.model.Audiobook
import com.devlight.offbookplus.model.Chapter
import kotlinx.coroutines.runBlocking
import java.io.File

private const val TAG = "LocalFileScanner"
private const val AUDIOBOOKS_DIR = "Audiobooks"

class LocalFileScanner(private val context: Context) {

    private val validExtensions = setOf("m4a", "m4b")

    fun scanForAudiobooks(): List<Audiobook> {
        val audiobooks = mutableListOf<Audiobook>()
        val storageDir = Environment.getExternalStorageDirectory()
        val audiobooksDir = File(storageDir, AUDIOBOOKS_DIR)

        if (!audiobooksDir.exists() || !audiobooksDir.isDirectory) {
            Log.e(TAG, "Directory not found: ${audiobooksDir.absolutePath}")
            return emptyList()
        }

        Log.i(TAG, "Scanning directory: ${audiobooksDir.absolutePath}")

        audiobooksDir.walk().forEach { file ->
            if (file.isFile && file.extension.lowercase() in validExtensions) {
                // For each file, extract its rich metadata to build an Audiobook object.
                // runBlocking is acceptable here as this is a one-time scan on app start.
                val extractedData = runBlocking { ChapterExtractor.extract(context, Uri.fromFile(file)) }

                // Use the parent folder name as a unique ID
                val bookId = (file.parentFile?.name ?: file.nameWithoutExtension)
                    .replace("\\s".toRegex(), "_").lowercase()

                audiobooks.add(
                    Audiobook(
                        id = bookId,
                        title = extractedData?.title ?: bookId.replace("_", " "),
                        author = extractedData?.artist ?: "Unknown Author",
                        chapters = listOf(Chapter(
                            bookId = bookId,
                            index = 0,
                            title = file.nameWithoutExtension,
                            startTimeSec = 0,
                            fileUri = Uri.fromFile(file).toString()
                        ))
                    )
                )
            }
        }

        Log.d(TAG, "Scan complete. Found ${audiobooks.size} audiobooks.")
        return audiobooks
    }
}
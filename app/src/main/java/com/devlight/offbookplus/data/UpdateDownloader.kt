package com.devlight.offbookplus.data

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "UpdateDownloader"
private const val CHUNK_SIZE = 32 * 1024
private const val CONNECT_TIMEOUT_MS = 10_000
private const val READ_TIMEOUT_MS = 30_000

/**
 * Streams a file (e.g. a GitHub release APK) straight to disk, bypassing DownloadManager.
 *
 * Benefits over DownloadManager for a watch:
 *  - real time progress callback for the UI,
 *  - resume support via HTTP Range on interrupted/failed downloads,
 *  - no DownloadProvider dependency (which can be slow / pause on Wear devices).
 */
object UpdateDownloader {

    /**
     * Downloads [url] into [dest]. If [dest] already contains a partial file from a previous
     * attempt, the download resumes from there. [onProgress] is invoked periodically with
     * (bytesDownloaded, totalBytes). Total may be -1 if the server doesn't report a size.
     *
     * Returns true when the file is fully downloaded and passes a basic APK sanity check.
     * On failure the partial file is left on disk so a later call can resume.
     */
    suspend fun downloadToFile(
        urlString: String,
        dest: File,
        onProgress: (bytesDownloaded: Long, totalBytes: Long) -> Unit
    ): Boolean {
        val alreadyDone = dest.exists() && dest.length() > 0 && isApk(dest) && apkSizeMatches(urlString, dest.length())
        if (alreadyDone) {
            Log.i(TAG, "Download appears complete already (${dest.length()} bytes).")
            return true
        }

        var existingSize = if (dest.exists() && isPartialCandidate(dest)) dest.length() else 0L
        Log.i(TAG, "Attempting download. Partial bytes present: $existingSize")

        var connection = openConnection(urlString, existingSize)
        return try {
            var code = connection.responseCode
            if (code == HttpURLConnection.HTTP_OK && existingSize > 0) {
                // Server ignored our Range request; restart the file from zero.
                Log.w(TAG, "Server ignored Range header. Restarting download from scratch.")
                dest.delete()
                existingSize = 0L
                connection.disconnect()
                connection = openConnection(urlString, 0)
                code = connection.responseCode
            }

            if (code == HttpURLConnection.HTTP_PARTIAL || code == HttpURLConnection.HTTP_OK) {
                val totalBytes = if (code == HttpURLConnection.HTTP_PARTIAL && connection.contentLengthLong > 0) {
                    existingSize + connection.contentLengthLong
                } else {
                    connection.contentLengthLong
                }
                Log.i(TAG, "HTTP $code. Existing=$existingSize, Content-Length=${connection.contentLengthLong}, Total=$totalBytes")

                val input = connection.inputStream ?: return false
                var downloaded: Long = existingSize

                input.use { `is` ->
                    FileOutputStream(dest, true).use { out ->
                        val buffer = ByteArray(CHUNK_SIZE)
                        var lastReport = downloaded
                        while (true) {
                            val read = `is`.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            downloaded += read
                            if (downloaded - lastReport >= CHUNK_SIZE) {
                                lastReport = downloaded
                                onProgress(downloaded, totalBytes)
                            }
                        }
                        out.flush()
                    }
                }
                onProgress(downloaded, totalBytes)

                if (!isApk(dest)) {
                    Log.e(TAG, "Downloaded file is not a valid APK (missing ZIP magic).")
                    dest.delete()
                    return false
                }
                Log.i(TAG, "Download complete: ${dest.length()} bytes (total=$totalBytes).")
                true
            } else {
                Log.e(TAG, "Unexpected response code: $code")
                false
            }
        } catch (e: IOException) {
            Log.e(TAG, "Download interrupted (partial file kept for resume): ${e.message}")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Download failed: ${e.message}")
            false
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(urlString: String, resumeFrom: Long): HttpURLConnection {
        val connection = URL(urlString).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.setRequestProperty("User-Agent", "OffBookPlus-App")
        connection.setRequestProperty("Accept", "application/vnd.android.package-archive")
        if (resumeFrom > 0) {
            connection.setRequestProperty("Range", "bytes=$resumeFrom-")
        }
        connection.connect()
        return connection
    }

    private fun isPartialCandidate(dest: File): Boolean {
        return dest.exists() && dest.length() > 0 && !isApk(dest)
    }

    private fun isApk(file: File): Boolean {
        return try {
            file.inputStream().use { input ->
                val header = ByteArray(4)
                val read = input.read(header)
                read >= 2 && header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte()
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun apkSizeMatches(urlString: String, expected: Long): Boolean {
        return try {
            val connection = openConnection(urlString, 0)
            try {
                val length = connection.contentLengthLong
                if (length > 0) length == expected else false
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            false
        }
    }
}
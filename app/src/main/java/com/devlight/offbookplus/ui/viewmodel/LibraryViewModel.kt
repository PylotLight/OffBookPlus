package com.devlight.offbookplus.ui.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_NEW_TASK
import android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.devlight.offbookplus.data.AppDatabase
import com.devlight.offbookplus.data.GitHubRelease
import com.devlight.offbookplus.data.LocalFileScanner
import com.devlight.offbookplus.data.PlaybackProgressEntity
import com.devlight.offbookplus.data.UpdateDownloader
import com.devlight.offbookplus.model.MediaItem
import com.devlight.offbookplus.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.File
import java.net.URL

private const val TAG = "LibraryViewModel"

enum class UpdateStatus {
    IDLE, CHECKING, UPDATE_AVAILABLE, NO_UPDATE, ERROR, DOWNLOADING, DOWNLOAD_COMPLETE
}

private val json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private companion object {
        const val PREFS_NAME = "LibraryStatePrefs"
        const val KEY_PREFIX_FILE_COUNT = "file_count_"
        const val KEY_PREFIX_LAST_MODIFIED = "last_modified_"
        const val KEY_UPDATE_DOWNLOAD_URL = "update_download_url"
    }

    private var downloadJob: Job? = null
    private val _uiState = MutableStateFlow<List<MediaItem>>(emptyList())
    val uiState: StateFlow<List<MediaItem>> = _uiState.asStateFlow()
    private val _progressByPlaylist = MutableStateFlow<Map<String, PlaybackProgressEntity>>(emptyMap())
    val progressByPlaylist: StateFlow<Map<String, PlaybackProgressEntity>> = _progressByPlaylist.asStateFlow()
    private val _downloadUrl = MutableStateFlow<String?>(null)
//    val downloadUrl: StateFlow<String?> = _downloadUrl.asStateFlow()

    private val _updateStatus = MutableStateFlow(UpdateStatus.IDLE)
    val updateStatus: StateFlow<UpdateStatus> = _updateStatus.asStateFlow()
    private val _downloadProgress = MutableStateFlow(0)
    val downloadProgress: StateFlow<Int> = _downloadProgress.asStateFlow()
    private val mediaItemDao = AppDatabase.getInstance(application).mediaItemDao()
    private val progressDao = AppDatabase.getInstance(application).progressDao()
    private val queueDao = AppDatabase.getInstance(application).playbackQueueDao()
    private val prefs: SharedPreferences =
        application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _currentMediaType = MutableStateFlow(MediaType.AUDIOBOOKS)

    private val currentVersion: String by lazy {
        application.packageManager.getPackageInfo(application.packageName, 0).versionName ?: "0.0.0"
    }

    /**
     * Checks for a new release by attempting to fetch the latest version from a remote source.
     * In a real application, this would call the GitHub Releases API.
     * For this sample, it's a simulated check.
     */
    fun checkForUpdate(repoOwner: String = "pylotlight", repoName: String = "offbookplus") {
        if (updateStatus.value == UpdateStatus.CHECKING || updateStatus.value == UpdateStatus.DOWNLOADING) return
        viewModelScope.launch {
            _updateStatus.update { UpdateStatus.CHECKING }
            val (latestVersion, downloadUrl) = withContext(Dispatchers.IO) {
                try {
                    val url =
                        URL("https://api.github.com/repos/$repoOwner/$repoName/releases/latest")
                    val connection = url.openConnection()
                    connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
                    connection.setRequestProperty("User-Agent", "OffBookPlus-App")
                    connection.connectTimeout = 5000
                    connection.readTimeout = 5000


                    val httpConnection = connection as? java.net.HttpURLConnection
                    val responseCode = httpConnection?.responseCode ?: -1
                    Log.i(TAG, "GitHub API Response Code: $responseCode")
                    if (responseCode != 200) {
                        Log.e(TAG, "GitHub API call failed with response code $responseCode")
                        return@withContext Pair(null, null)
                    }

                    val jsonString =
                        connection.getInputStream().bufferedReader().use(BufferedReader::readText)
                    Log.v(TAG, "GitHub API Raw JSON Response: $jsonString")
                    val release = json.decodeFromString<GitHubRelease>(jsonString)
                    val tag = release.tagName.removePrefix("v")
                    val urlString = release.assets.firstOrNull {
                        it.name.endsWith(
                            ".apk",
                            ignoreCase = true
                        )
                    }?.browserDownloadUrl
                    if (urlString == null) {
                        Log.w(TAG, "No APK asset found in the release.")
                    }
                    Log.i(TAG, "Parsed Latest Tag: $tag, Download URL: $urlString")
                    Pair(tag, urlString)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to fetch latest version from GitHub API.", e)
                    Pair(null, null)
                }
            }

            if (latestVersion == null) {
                _updateStatus.update { UpdateStatus.ERROR }
                return@launch
            }

            if (compareVersions(latestVersion, currentVersion) > 0) {
                _downloadUrl.value = downloadUrl
                _updateStatus.update { UpdateStatus.UPDATE_AVAILABLE }
                Log.i(
                    TAG,
                    "Update available: $latestVersion. Download URL set. Current Flow Value: ${_downloadUrl.value}"
                )
            } else {
                _updateStatus.update { UpdateStatus.NO_UPDATE }
                Log.i(TAG, "No update available. Latest is $latestVersion.")
            }
        }
    }

    fun downloadAndInstallUpdate() {
        Log.d(
            TAG,
            "DownloadAndInstallUpdate called. Current _downloadUrl.value: ${_downloadUrl.value}"
        )
        val url = _downloadUrl.value ?: run {
            Log.e(TAG, "Download URL is null, cannot start download.")
            _updateStatus.update { UpdateStatus.ERROR }
            return
        }

        _updateStatus.value = UpdateStatus.DOWNLOADING
        _downloadProgress.value = 0

        val app = getApplication<Application>()
        val apkFile = File(app.getExternalFilesDir(null), "update.apk")

        // If a newer update is being downloaded, drop the old partial file so we don't
        // resume with bytes from a different APK.
        val previousUrl = prefs.getString(KEY_UPDATE_DOWNLOAD_URL, null)
        if (previousUrl != null && previousUrl != url) {
            Log.i(TAG, "Update URL changed, discarding partial download.")
            apkFile.delete()
        }

        downloadJob?.cancel()
        downloadJob = viewModelScope.launch(Dispatchers.IO) {
            val success = UpdateDownloader.downloadToFile(url, apkFile) { downloaded, total ->
                val percent = if (total > 0) (downloaded * 100 / total).toInt() else 0
                _downloadProgress.value = percent.coerceIn(0, 100)
                Log.d(TAG, "Downloading... $percent% ($downloaded/$total bytes)")
            }
            if (success) {
                prefs.edit { putString(KEY_UPDATE_DOWNLOAD_URL, url) }
                _downloadProgress.value = 100
                Log.i(TAG, "Download SUCCESSFUL. APK size: ${apkFile.length()} bytes. Initiating install.")
                _updateStatus.update { UpdateStatus.DOWNLOAD_COMPLETE }
                initiateInstall()
            } else {
                Log.e(TAG, "Download FAILED.")
                _updateStatus.value = UpdateStatus.ERROR
            }
        }
    }

    private fun initiateInstall() {
        Log.i(TAG, "Attempting to initiate APK installation.")
        val apkFile = File(getApplication<Application>().getExternalFilesDir(null), "update.apk")
        if (!apkFile.exists()) {
            Log.e(TAG, "APK file not found at expected path: ${apkFile.absolutePath}")
            return
        }
        try {
            val fileUri = FileProvider.getUriForFile(
                getApplication(),
                "${getApplication<Application>().packageName}.provider",
                apkFile
            )
            val installIntent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(fileUri, "application/vnd.android.package-archive")
                .addFlags(FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(FLAG_ACTIVITY_NEW_TASK)

            getApplication<Application>().startActivity(installIntent)
            Log.i(TAG, "Installation Intent sent successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "FATAL: Failed to initiate installation via FileProvider/Intent.", e)
            _updateStatus.value = UpdateStatus.ERROR
        }
    }

    override fun onCleared() {
        downloadJob?.cancel()
        super.onCleared()
    }

    /**
     * The single public function called by the UI. It runs the check and then loads the media.
     * This will be called every time the LibraryScreen for a specific type is displayed.
     */
    fun checkAndLoadMedia(mediaType: MediaType) {
        _currentMediaType.value = mediaType
        viewModelScope.launch {
            checkForLibraryUpdates(mediaType, forceRescan = false)
            loadMedia(mediaType)
        }
    }

    /**
     * The manual rescan button. Forces a full, clean scan of ALL directories.
     */
    fun forceFullRescan() {
        Log.i(TAG, "Manual full rescan initiated.")
        viewModelScope.launch {
            MediaType.entries.forEach { mediaType ->
                checkForLibraryUpdates(mediaType, forceRescan = true)
            }
            loadMedia(_currentMediaType.value)
        }
    }

    /**
     * Checks a single media type and only rescans if it has changed.
     */
    private suspend fun checkForLibraryUpdates(mediaType: MediaType, forceRescan: Boolean) {
        return withContext(Dispatchers.IO) {
            Log.d(TAG, "Checking for library updates for ${mediaType.name}...")
            val scanner = LocalFileScanner(getApplication())

            val storedFileCount = prefs.getInt(KEY_PREFIX_FILE_COUNT + mediaType.name, -1)
            val storedLastModified =
                prefs.getLong(KEY_PREFIX_LAST_MODIFIED + mediaType.name, -1L)

            val (currentFileCount, currentLastModified) = scanner.getDirectoryState(mediaType)

            if (currentFileCount != storedFileCount || currentLastModified != storedLastModified || forceRescan) {
                Log.i(
                    TAG,
                    "Change detected for ${mediaType.name} (Stored: Count=$storedFileCount, TS=$storedLastModified | Current: Count=$currentFileCount, TS=$currentLastModified). Rescanning..."
                )
                val newItems = scanner.performDeepScanFor(mediaType)
                mediaItemDao.deleteByMediaType(mediaType.name)
                mediaItemDao.insertAll(newItems)
                pruneQueuesForType(mediaType, newItems.map { it.id }.toSet())
                prefs.edit {
                    putInt(
                        KEY_PREFIX_FILE_COUNT + mediaType.name,
                        currentFileCount
                    )
                    putLong(
                        KEY_PREFIX_LAST_MODIFIED + mediaType.name,
                        currentLastModified
                    )
                }
                Log.i(TAG, "Scan complete for ${mediaType.name}. Found ${newItems.size} items.")
            } else {
                Log.d(TAG, "No changes detected for ${mediaType.name}. Scan skipped.")
            }
        }
    }

    private fun loadMedia(mediaType: MediaType) {
        viewModelScope.launch {
            val (itemsFromDb, progressList) = withContext(Dispatchers.IO) {
                mediaItemDao.getItemsByMediaType(mediaType.name) to progressDao.getAllProgressOnce()
            }
            _uiState.value = itemsFromDb.map {
                MediaItem(it.id, it.playlistId, it.mediaType, it.title, it.artist, it.fileUri)
            }
            _progressByPlaylist.value = progressList.associateBy { it.playlistId }
            Log.d(TAG, "Loaded ${uiState.value.size} items for '${mediaType.name}' from DB.")
        }
    }

    /**
     * Drops queue entries for files that disappeared in a rescan and clamps indices,
     * so a restored queue never points at stale media ids.
     */
    private suspend fun pruneQueuesForType(mediaType: MediaType, validIds: Set<String>) {
        queueDao.getAllForType(mediaType.name).forEach { queue ->
            val ids = runCatching { json.decodeFromString<List<String>>(queue.orderedIds) }.getOrNull()
            if (ids == null) {
                queueDao.delete(queue.queueId)
                return@forEach
            }
            val pruned = ids.filter { it in validIds }
            when {
                pruned.isEmpty() -> queueDao.delete(queue.queueId)
                pruned.size != ids.size -> queueDao.save(
                    queue.copy(
                        orderedIds = json.encodeToString(pruned),
                        currentIndex = queue.currentIndex.coerceIn(0, pruned.size - 1)
                    )
                )
            }
        }
    }

    private fun compareVersions(v1: String, v2: String): Int {
        val parts1 = v1.split(".").map { it.toIntOrNull() ?: 0 }
        val parts2 = v2.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(parts1.size, parts2.size)) {
            val p1 = parts1.getOrElse(i) { 0 }
            val p2 = parts2.getOrElse(i) { 0 }
            if (p1 != p2) return p1.compareTo(p2)
        }
        return 0
    }
}
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
import com.devlight.offbookplus.data.GitHubRelease
import com.devlight.offbookplus.data.UpdateDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "UpdatesViewModel"

enum class ReleaseListStatus { IDLE, LOADING, LOADED, ERROR }

/**
 * Shows every published GitHub release so the user can install a specific version
 * (including older ones) — effectively enabling rollback from a bad release.
 */
class UpdatesViewModel(application: Application) : AndroidViewModel(application) {

    private companion object {
        const val PREFS_NAME = "LibraryStatePrefs"
        const val KEY_UPDATE_DOWNLOAD_URL = "update_download_url"
        const val RELEASES_PER_PAGE = 50
    }

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }
    private val prefs: SharedPreferences =
        application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _releases = MutableStateFlow<List<GitHubRelease>>(emptyList())
    val releases: StateFlow<List<GitHubRelease>> = _releases.asStateFlow()

    private val _listStatus = MutableStateFlow(ReleaseListStatus.IDLE)
    val listStatus: StateFlow<ReleaseListStatus> = _listStatus.asStateFlow()

    private val _downloadStatus = MutableStateFlow(UpdateStatus.IDLE)
    val downloadStatus: StateFlow<UpdateStatus> = _downloadStatus.asStateFlow()

    private val _downloadProgress = MutableStateFlow(0)
    val downloadProgress: StateFlow<Int> = _downloadProgress.asStateFlow()

    private val _activeDownloadUrl = MutableStateFlow<String?>(null)
    val activeDownloadUrl: StateFlow<String?> = _activeDownloadUrl.asStateFlow()

    private val _activeCompleteUrl = MutableStateFlow<String?>(null)
    val activeCompleteUrl: StateFlow<String?> = _activeCompleteUrl.asStateFlow()

    private var downloadJob: Job? = null

    val currentVersion: String by lazy {
        getApplication<Application>().packageManager
            .getPackageInfo(getApplication<Application>().packageName, 0).versionName ?: "0.0.0"
    }

    fun loadReleases(repoOwner: String = "pylotlight", repoName: String = "offbookplus") {
        if (_listStatus.value == ReleaseListStatus.LOADING) return
        viewModelScope.launch {
            _listStatus.update { ReleaseListStatus.LOADING }
            val result = withContext(Dispatchers.IO) {
                fetchReleases(repoOwner, repoName)
            }
            if (result == null) {
                _listStatus.update { ReleaseListStatus.ERROR }
            } else {
                _releases.value = result
                _listStatus.update { ReleaseListStatus.LOADED }
            }
        }
    }

    private fun fetchReleases(owner: String, repo: String): List<GitHubRelease>? {
        return try {
            val url = URL("https://api.github.com/repos/$owner/$repo/releases?per_page=$RELEASES_PER_PAGE")
            val connection = url.openConnection() as HttpURLConnection
            connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
            connection.setRequestProperty("User-Agent", "OffBookPlus-App")
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            try {
                if (connection.responseCode != 200) {
                    Log.e(TAG, "Releases API call failed with response code ${connection.responseCode}")
                    return null
                }
                val jsonString = connection.inputStream.bufferedReader().use(BufferedReader::readText)
                json.decodeFromString<List<GitHubRelease>>(jsonString)
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch releases.", e)
            null
        }
    }

    /**
     * Download (and then install) the APK for [release]. Tapping again once a download has
     * finished triggers the install instead of re-downloading.
     */
    fun downloadOrInstall(release: GitHubRelease) {
        val apk = release.apkAsset ?: run {
            Log.w(TAG, "Release ${release.tagName} has no APK asset.")
            return
        }
        val url = apk.browserDownloadUrl
        when {
            _activeCompleteUrl.value == url -> {
                Log.i(TAG, "APK for ${release.tagName} already downloaded. Installing.")
                initiateInstall()
            }
            _downloadStatus.value == UpdateStatus.DOWNLOADING && _activeDownloadUrl.value == url -> {
                Log.d(TAG, "Download already in progress for ${release.tagName}.")
            }
            else -> startDownload(url)
        }
    }

    private fun startDownload(url: String) {
        val app = getApplication<Application>()
        val apkFile = File(app.getExternalFilesDir(null), "update.apk")

        // Discard a partial file from a different release so we never mix APK bytes.
        val previousUrl = prefs.getString(KEY_UPDATE_DOWNLOAD_URL, null)
        if (previousUrl != null && previousUrl != url) {
            Log.i(TAG, "Download target changed, discarding partial file.")
            apkFile.delete()
        }

        downloadJob?.cancel()
        _downloadStatus.update { UpdateStatus.DOWNLOADING }
        _downloadProgress.value = 0
        _activeDownloadUrl.value = url
        _activeCompleteUrl.value = null

        downloadJob = viewModelScope.launch(Dispatchers.IO) {
            val success = UpdateDownloader.downloadToFile(url, apkFile) { downloaded, total ->
                val percent = if (total > 0) (downloaded * 100 / total).toInt() else 0
                _downloadProgress.value = percent.coerceIn(0, 100)
            }
            if (success) {
                prefs.edit { putString(KEY_UPDATE_DOWNLOAD_URL, url) }
                _downloadProgress.value = 100
                _downloadStatus.update { UpdateStatus.DOWNLOAD_COMPLETE }
                _activeCompleteUrl.value = url
                Log.i(TAG, "Download SUCCESSFUL (${apkFile.length()} bytes). Initiating install.")
                initiateInstall()
            } else {
                Log.e(TAG, "Download FAILED.")
                _downloadStatus.update { UpdateStatus.ERROR }
                _activeDownloadUrl.value = null
            }
        }
    }

    private fun initiateInstall() {
        val app = getApplication<Application>()
        val apkFile = File(app.getExternalFilesDir(null), "update.apk")
        if (!apkFile.exists()) {
            Log.e(TAG, "APK file not found at expected path: ${apkFile.absolutePath}")
            return
        }
        try {
            val fileUri = FileProvider.getUriForFile(
                app,
                "${app.packageName}.provider",
                apkFile
            )
            val installIntent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(fileUri, "application/vnd.android.package-archive")
                .addFlags(FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(FLAG_ACTIVITY_NEW_TASK)
            app.startActivity(installIntent)
            Log.i(TAG, "Installation Intent sent successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initiate installation via FileProvider/Intent.", e)
            _downloadStatus.update { UpdateStatus.ERROR }
        }
    }

    override fun onCleared() {
        downloadJob?.cancel()
        super.onCleared()
    }
}
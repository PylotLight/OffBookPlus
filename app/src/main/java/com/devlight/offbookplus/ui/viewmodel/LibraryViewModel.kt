package com.devlight.offbookplus.ui.viewmodel

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.devlight.offbookplus.data.AppDatabase
import com.devlight.offbookplus.data.LocalFileScanner
import com.devlight.offbookplus.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.core.content.edit
import com.devlight.offbookplus.model.MediaItem

private const val TAG = "LibraryViewModel"

class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private companion object {
        const val PREFS_NAME = "LibraryStatePrefs"
        const val KEY_PREFIX_FILE_COUNT = "file_count_"
        const val KEY_PREFIX_LAST_MODIFIED = "last_modified_"
    }
    private val _uiState = MutableStateFlow<List<MediaItem>>(emptyList())
    val uiState: StateFlow<List<MediaItem>> = _uiState.asStateFlow()

    private val mediaItemDao = AppDatabase.getInstance(application).mediaItemDao()
    private val prefs: SharedPreferences = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _currentMediaType = MutableStateFlow(MediaType.AUDIOBOOKS)

    // Removed init { checkForLibraryUpdates() }

    /**
     * Fix: The single public function called by the UI. It runs the check and then loads the media.
     * This will be called every time the LibraryScreen for a specific type is displayed.
     */
    fun checkAndLoadMedia(mediaType: MediaType) {
        _currentMediaType.value = mediaType
        viewModelScope.launch {
            // Check for updates for this specific media type
            checkForLibraryUpdates(mediaType, forceRescan = false)
            // Always load from DB after the check (which may have updated the DB)
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
                // Run the full check, forcing a rescan for each type
                checkForLibraryUpdates(mediaType, forceRescan = true)
            }
            // After all scans, reload the current view's media
            loadMedia(_currentMediaType.value)
        }
    }

    /**
     * Fix: Checks a single media type and only rescans if it has changed.
     */
    private suspend fun checkForLibraryUpdates(mediaType: MediaType, forceRescan: Boolean) {
        return withContext(Dispatchers.IO) {
            Log.d(TAG, "Checking for library updates for ${mediaType.name}...")
            val scanner = LocalFileScanner(getApplication())

            val storedFileCount = prefs.getInt(KEY_PREFIX_FILE_COUNT + mediaType.name, -1)
            val storedLastModified = prefs.getLong(KEY_PREFIX_LAST_MODIFIED + mediaType.name, -1L)

            val (currentFileCount, currentLastModified) = scanner.getDirectoryState(mediaType)

            if (currentFileCount != storedFileCount || currentLastModified != storedLastModified || forceRescan) {
                Log.i(TAG, "Change detected for ${mediaType.name} (Stored: Count=$storedFileCount, TS=$storedLastModified | Current: Count=$currentFileCount, TS=$currentLastModified). Rescanning...")
                val newItems = scanner.performDeepScanFor(mediaType)
                mediaItemDao.deleteByMediaType(mediaType.name)
                mediaItemDao.insertAll(newItems)
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
            val itemsFromDb = withContext(Dispatchers.IO) {
                mediaItemDao.getItemsByMediaType(mediaType.name)
            }
            _uiState.value = itemsFromDb.map {
                MediaItem(it.id, it.playlistId, it.mediaType, it.title, it.artist, it.fileUri)
            }
            Log.d(TAG, "Loaded ${uiState.value.size} items for '${mediaType.name}' from DB.")
        }
    }
}
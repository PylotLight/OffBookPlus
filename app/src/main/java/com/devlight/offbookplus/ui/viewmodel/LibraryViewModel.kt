package com.devlight.offbookplus.ui.viewmodel

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.devlight.offbookplus.data.AppDatabase
import com.devlight.offbookplus.data.LocalFileScanner
import com.devlight.offbookplus.data.MediaItemEntity
import com.devlight.offbookplus.model.MediaItem
import com.devlight.offbookplus.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.core.content.edit

private const val TAG = "LibraryViewModel"

class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private companion object {
        const val PREFS_NAME = "LibraryStatePrefs"
        const val KEY_PREFIX_FILE_COUNT = "file_count_"
    }
    private val _uiState = MutableStateFlow<List<MediaItem>>(emptyList())
    val uiState: StateFlow<List<MediaItem>> = _uiState.asStateFlow()

    private val mediaItemDao = AppDatabase.getInstance(application).mediaItemDao()
    private val prefs: SharedPreferences = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _currentMediaType = MutableStateFlow(MediaType.AUDIOBOOKS)
    init {
        checkForLibraryUpdates()
    }
    /**
     * The manual rescan button. Forces a full, clean scan of ALL directories.
     */
    fun forceFullRescan() {
        Log.i(TAG, "Manual full rescan initiated.")
        checkForLibraryUpdates(forceRescan = true)
    }
    /**
     * The main orchestrator. Checks each media type and only rescans if it has changed.
     * Runs each check in parallel.
     */
    private fun checkForLibraryUpdates(forceRescan: Boolean = false) {
        viewModelScope.launch {
            Log.d(TAG, "Checking for library updates...")
            val scanner = LocalFileScanner(getApplication())

            MediaType.entries.forEach { mediaType ->
                launch(Dispatchers.IO) {
                    val storedFileCount = prefs.getInt(KEY_PREFIX_FILE_COUNT + mediaType.name, -1)
                    val currentFileCount = scanner.getDirectoryFileCount(mediaType)

                    if (currentFileCount != storedFileCount || forceRescan) {
                        Log.i(TAG, "Change detected for ${mediaType.name} (Stored: $storedFileCount, Current: $currentFileCount). Rescanning...")
                        val newItems = scanner.performDeepScanFor(mediaType)
                        mediaItemDao.deleteByMediaType(mediaType.name)
                        mediaItemDao.insertAll(newItems)
                        prefs.edit {
                            putInt(
                                KEY_PREFIX_FILE_COUNT + mediaType.name,
                                currentFileCount
                            )
                        }
                        Log.i(TAG, "Scan complete for ${mediaType.name}. Found ${newItems.size} items.")
                        if (_currentMediaType.value == mediaType) {
                            withContext(Dispatchers.Main) {
                                loadMedia(mediaType)
                            }
                        }
                    } else {
                        Log.d(TAG, "No changes detected for ${mediaType.name}. Scan skipped.")
                    }
                }
            }
        }
    }
    fun loadMedia(mediaType: MediaType) {
        _currentMediaType.value = mediaType
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
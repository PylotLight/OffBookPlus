package com.devlight.offbookplus.ui.viewmodel

import android.app.Application
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

private const val TAG = "LibraryViewModel"

class LibraryViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow<List<MediaItem>>(emptyList())
    val uiState: StateFlow<List<MediaItem>> = _uiState.asStateFlow()

    private val mediaItemDao = AppDatabase.getInstance(application).mediaItemDao()
    private val _currentMediaType = MutableStateFlow(MediaType.AUDIOBOOKS)

    fun rescanLibrary() {
        viewModelScope.launch {
            Log.i(TAG, "Manual rescan initiated.")
            withContext(Dispatchers.IO) {
                val scanner = LocalFileScanner(getApplication())
                // Clear the old library before scanning
                mediaItemDao.deleteAll()
                // Scan and insert items for each media type
//                val allItems = MediaType.entries.flatMap { mediaType ->
//                    scanner.scanLibraryFor(mediaType)
//                }
                val allItems: List<MediaItemEntity> = MediaType.entries.flatMap { mediaType ->
                    scanner.scanLibraryFor(mediaType)
                }
                mediaItemDao.insertAll(allItems)
            }
            // Refresh the currently viewed list
            loadMedia(_currentMediaType.value)
            Log.i(TAG, "Manual rescan finished and DB updated.")
        }
    }

    fun loadMedia(mediaType: MediaType) {
        _currentMediaType.value = mediaType
        viewModelScope.launch {
            val itemsFromDb = withContext(Dispatchers.IO) {
                mediaItemDao.getItemsByMediaType(mediaType.name)
            }
            _uiState.value = itemsFromDb.map {
                // Convert Entity to UI Model
                MediaItem(it.id, it.playlistId, it.mediaType, it.title, it.artist, it.fileUri)
            }
            Log.d(TAG, "Loaded ${uiState.value.size} items for '${mediaType.name}' from DB.")
        }
    }
}
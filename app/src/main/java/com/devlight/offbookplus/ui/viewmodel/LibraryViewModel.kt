package com.devlight.offbookplus.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.devlight.offbookplus.data.LocalFileScanner
import com.devlight.offbookplus.model.Audiobook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel responsible for loading the list of audiobooks available on the device.
 */
class LibraryViewModel(application: Application) : AndroidViewModel(application) {

    // Internal mutable state flow for the list of audiobooks
    private val _uiState = MutableStateFlow<List<Audiobook>>(emptyList())

    // External immutable state flow for the UI to observe
    val uiState: StateFlow<List<Audiobook>> = _uiState.asStateFlow()

    // Initialize the ViewModel by loading the data
    init {
        loadAudiobooks()
    }

    private fun loadAudiobooks() {
        viewModelScope.launch {
            // CRITICAL: File scanning is an IO operation and must be run on the IO Dispatcher
            withContext(Dispatchers.IO) {
                // Instantiate the scanner with the Application context
                val scanner = LocalFileScanner(getApplication())

                // Execute the scan
                val audiobooks = scanner.scanForAudiobooks()

                // Update the state on the main thread
                _uiState.value = audiobooks
            }
        }
    }
}
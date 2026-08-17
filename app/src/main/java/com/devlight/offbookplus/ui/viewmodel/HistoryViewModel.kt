package com.devlight.offbookplus.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.devlight.offbookplus.data.AppDatabase
import com.devlight.offbookplus.data.PlayHistoryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HistoryViewModel(application: Application) : AndroidViewModel(application) {

    private val dao = AppDatabase.getInstance(application).playHistoryDao()

    /** All recorded listening history, newest first. Updated live from the DB. */
    val history: StateFlow<List<PlayHistoryEntity>> =
        dao.getAllHistoryFlow()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyList()
            )

    fun clearHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            dao.clearAll()
        }
    }
}
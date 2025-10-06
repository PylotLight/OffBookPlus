package com.devlight.offbookplus.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material.ScalingLazyColumn
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.items
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.MaterialTheme
import com.devlight.offbookplus.model.Audiobook
import com.devlight.offbookplus.ui.viewmodel.LibraryViewModel

@Composable
fun LibraryScreen(
    onBookClick: (String) -> Unit,
    viewModel: LibraryViewModel = viewModel()
) {
    val audiobooks by viewModel.uiState.collectAsState()

    if (audiobooks.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No audiobooks found.")
        }
    } else {
        ScalingLazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                Text("Your Library", style = MaterialTheme.typography.titleMedium)
            }
            items(audiobooks) { book ->
                BookCard(book = book, onClick = onBookClick)
            }
        }
    }
}

@Composable
private fun BookCard(book: Audiobook, onClick: (String) -> Unit) {
    Card(onClick = { onClick(book.id) }) {
        Text(book.title, style = MaterialTheme.typography.titleSmall)
        Text(book.author, style = MaterialTheme.typography.bodySmall)
    }
}
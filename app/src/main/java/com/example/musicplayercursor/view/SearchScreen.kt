package com.example.musicplayercursor.view

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.musicplayercursor.R
import com.example.musicplayercursor.model.Song
import com.example.musicplayercursor.viewmodel.MusicViewModel

@Composable
fun SearchScreen(
    allSongs: List<Song>,
    viewModel: MusicViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    
    // Filter songs based on search query
    val filteredSongs = remember(searchQuery, allSongs) {
        if (searchQuery.isBlank()) {
            emptyList()
        } else {
            val query = searchQuery.lowercase().trim()
            allSongs.filter { song ->
                song.title.lowercase().contains(query) ||
                song.artist.lowercase().contains(query)
            }
        }
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Header with back button and search textfield
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Back button
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "Back"
                )
            }
            
            // Search textfield
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp),
                placeholder = { Text("Search songs...") },
                singleLine = true,
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search"
                    )
                }
            )
        }
        
        // Results list
        if (searchQuery.isBlank()) {
            // Show empty state when no search query
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Type to search for songs",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.Gray
                )
            }
        } else if (filteredSongs.isEmpty()) {
            // Show no results message
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No songs found",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.Gray
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize()
            ) {
                items(filteredSongs, key = { it.id }) { song ->
                    SearchSongRow(
                        song = song,
                        onClick = {
                            // Play the song from all songs queue
                            val queueIds = allSongs.map { it.id }
                            viewModel.playFromQueue(context, queueIds, song.id, "all")
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchSongRow(
    song: Song,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            // Music icon
            Icon(
                painter = painterResource(id = R.drawable.music),
                contentDescription = "Song",
                tint = Color.Unspecified,
                modifier = Modifier
                    .size(40.dp)
                    .padding(end = 8.dp)
            )

            // Song title (single line, ellipsis if too long)
            Text(
                text = song.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f) // Takes available space
            )
        }
        // Artist (same line, smaller, gray)
        Text(
            text = song.artist,
            style = MaterialTheme.typography.bodyMedium.copy(color = Color.Gray),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        // Grey divider line
        Divider(
            color = Color.LightGray.copy(alpha = 0.5f),
            thickness = 0.5.dp,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

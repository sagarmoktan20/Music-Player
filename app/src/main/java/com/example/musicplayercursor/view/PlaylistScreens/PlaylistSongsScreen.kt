package com.example.musicplayercursor.view.PlaylistScreens

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.musicplayercursor.R
import com.example.musicplayercursor.model.Song

@Composable
fun PlaylistSongsScreen(
    songs: List<Song>,
    isSelectionMode: Boolean,
    selectedSongs: Set<Long>,
    currentSongId: Long? = null, // ADD THIS: Pass current song ID
    onPlay: (Song) -> Unit,
    onLongPress: (Song) -> Unit,
    onToggleSelection: (Long) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        if (songs.isEmpty()) {
            item {
                Text(
                    text = "No songs in this playlist",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                )
            }
        } else {
            items(songs, key = { it.id }) { song ->
                val isSelected = selectedSongs.contains(song.id)
                PlaylistSongRow(
                    song = song,
                    isSelected = isSelected,
                    isSelectionMode = isSelectionMode,
                    isCurrentlyPlaying = currentSongId != null && currentSongId == song.id, // ADD THIS
                    onClick = {
                        if (isSelectionMode) {
                            onToggleSelection(song.id)
                        } else {
                            onPlay(song)
                        }
                    },
                    onLongPress = { onLongPress(song) }
                )
            }
        }
    }
}

@Composable
private fun PlaylistSongRow(
    song: Song,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    isCurrentlyPlaying: Boolean = false, // ADD THIS
    onClick: () -> Unit,
    onLongPress: () -> Unit
) {
    // Determine background color
    val backgroundColor = when {
        isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        isCurrentlyPlaying -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)
        else -> Color.Transparent
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor) // MODIFY THIS
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongPress
            )
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            // Selection indicator (checkbox) or music icon
            if (isSelectionMode) {
                Icon(
                    imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                    contentDescription = if (isSelected) "Selected" else "Not selected",
                    tint = if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray,
                    modifier = Modifier
                        .size(40.dp)
                        .padding(end = 8.dp)
                )
            } else {
                // Music icon
                Icon(
                    painter = painterResource(id = R.drawable.music),
                    contentDescription = "Song",
                    tint = Color.Unspecified,
                    modifier = Modifier
                        .size(40.dp)
                        .padding(end = 8.dp)
                )
            }

            // Song info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = song.artist,
                    style = MaterialTheme.typography.bodyMedium.copy(color = Color.Gray),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Grey divider line
        Divider(
            color = Color.LightGray.copy(alpha = 0.5f),
            thickness = 0.5.dp,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}


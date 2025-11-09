package com.example.musicplayercursor.view.PlaylistScreens

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
fun RecentlyPlayed(
    songs: List<Song> = emptyList(),
    isSelectionMode: Boolean = false,
    selectedSongs: Set<Long> = emptySet(),
    onPlay: (Song) -> Unit = {},
    onLongPress: (Song) -> Unit = {},
    onToggleSelection: (Long) -> Unit = {}
) {
    val recent = songs
        .filter { it.lastPlayed > 0L }
        .sortedByDescending { it.lastPlayed }
        .take(100)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        if (recent.isEmpty()) {
            item {
                Text(
                    text = "No songs played recently",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                )
            }
        } else {
            items(recent, key = { it.id }) { song ->
                val isSelected = selectedSongs.contains(song.id)
                RecentlyPlayedRow(
                    song = song,
                    isSelected = isSelected,
                    isSelectionMode = isSelectionMode,
                    onPlay = {
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
private fun RecentlyPlayedRow(
    song: Song,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onPlay: () -> Unit,
    onLongPress: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                else Color.Transparent
            )
            .combinedClickable(
                onClick = onPlay,
                onLongClick = onLongPress
            )
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
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
                Icon(
                    painter = painterResource(id = R.drawable.music),
                    contentDescription = "Song",
                    tint = Color.Unspecified,
                    modifier = Modifier
                        .size(40.dp)
                        .padding(end = 8.dp)
                )
            }
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = song.artist,
                    style = MaterialTheme.typography.bodyMedium.copy(color = Color.Gray),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Divider(
            color = Color.LightGray.copy(alpha = 0.5f),
            thickness = 0.5.dp,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}
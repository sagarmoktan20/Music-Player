package com.example.musicplayercursor.view.PlaylistScreens

    import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
fun MostPlayed(
    songs: List<Song>,
    onPlay: (Song) -> Unit
) {
    // Filter songs with playCount > 0, sort by playCount descending, take top 100
    val mostPlayedSongs = songs
        .filter { it.playCount > 0 }
        .sortedByDescending { it.playCount }
        .take(100)
    
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        if (mostPlayedSongs.isEmpty()) {
            item {
                Text(
                    text = "No songs played yet",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                )
            }
        } else {
            items(mostPlayedSongs, key = { it.id }) { song ->
                MostPlayedSongRow(song = song, onPlay = { onPlay(song) })
            }
        }
    }
}

@Composable
private fun MostPlayedSongRow(song: Song, onPlay: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onPlay() }
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

            // Song info
            Column(
                modifier = Modifier.weight(1f)
            ) {
                // Song title
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // Artist
                Text(
                    text = song.artist,
                    style = MaterialTheme.typography.bodyMedium.copy(color = Color.Gray),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            
            // Play count
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "${song.playCount}",
                style = MaterialTheme.typography.bodySmall.copy(color = Color.Gray),
                modifier = Modifier.padding(start = 8.dp)
            )
        }

        // Grey divider line
        Divider(
            color = Color.LightGray.copy(alpha = 0.5f),
            thickness = 0.5.dp,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}
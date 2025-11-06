package com.example.musicplayercursor.view

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.musicplayercursor.model.Song

@Composable
fun FavouritesScreen(
    songs: List<Song>,
    onPlay: (Song) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        items(songs.filter { it.isFavourite }, key = { it.id }) { song ->
            FavouriteSongRow(song = song) { onPlay(song) }
        }
    }
}

@Composable
private fun FavouriteSongRow(song: Song, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(text = song.title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
        Spacer(Modifier.height(2.dp))
        Text(text = song.artist, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
    }
}
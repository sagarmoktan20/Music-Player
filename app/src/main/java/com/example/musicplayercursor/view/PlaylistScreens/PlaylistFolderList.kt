package com.example.musicplayercursor.view.PlaylistScreens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.musicplayercursor.R
import com.example.musicplayercursor.model.Playlist
import com.example.musicplayercursor.view.CreatePlaylistDialog
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect

private data class PlaylistFolderItem(
    val id: String? = null,
    val title: String,
    val isCreateNew: Boolean = false
)

@Composable
fun PlaylistFolderList(
    playlists: List<Playlist> = emptyList(),
    selectedSongIds: Set<Long> = emptySet(),
    onCreatePlaylist: (String) -> Unit = {},
    onPlaylistSelected: (String?, Set<Long>) -> Unit = {_, _ -> Unit},
    onDismiss: () -> Unit = {},
    onLoadPlaylists: () -> Unit = {}
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    
    // Load playlists when screen is shown
    LaunchedEffect(Unit) {
        onLoadPlaylists()
    }
    
    // Combine default folders with user-created playlists
    val defaultFolders = listOf(
        PlaylistFolderItem(id = "favourites", title = "Favourites")//        PlaylistFolderItem(title = "Most played"),
//        PlaylistFolderItem(title = "Recently played")
    )
    
    val userPlaylists = playlists.map { playlist ->
        PlaylistFolderItem(id = playlist.id, title = playlist.name)
    }
//
    val allFolders = (  defaultFolders + userPlaylists + listOf(
        PlaylistFolderItem(title = "Create new playlist", isCreateNew = true)
    ))
    
    Surface(tonalElevation = 0.dp) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 12.dp)
        ) {
            items(allFolders, key = { it.id ?: it.title }) { folder ->
                PlaylistFolderItemCard(
                    folder = folder,
                    onClick = {
                        if (folder.isCreateNew) {
                            showCreateDialog = true
                        } else {
                            // Add selected songs to the playlist
                            // For "Favourites" folder (id is null), handle it later
                            if (folder.id != null && selectedSongIds.isNotEmpty()) {
                                onPlaylistSelected(folder.id, selectedSongIds)
                            } else if (folder.id == null) {
                                // Default folder like "Favourites" - handle later
                                onPlaylistSelected(null, selectedSongIds)
                            }
                        }
                    }
                )
            }
        }
    }
    
    // Show create playlist dialog
    if (showCreateDialog) {
        CreatePlaylistDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { name ->
                onCreatePlaylist(name)
                showCreateDialog = false
            }
        )
    }
}

@Composable
private fun PlaylistFolderItemCard(folder: PlaylistFolderItem, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFFF2F2F2))
                .clickable { onClick() }
        ) {
            if (folder.isCreateNew) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Create playlist",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth(0.35f)
                )
            } else {
                Image(
                    painter = painterResource(id = R.drawable.music),
                    contentDescription = folder.title,
                )
            }
        }
        Text(
            text = folder.title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .clickable { onClick() }
        )
    }
}


package com.example.musicplayercursor.view.PlaylistScreens
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.musicplayercursor.R
import androidx.activity.compose.BackHandler
import com.example.musicplayercursor.model.Song
import com.example.musicplayercursor.model.Playlist
import com.example.musicplayercursor.view.CreatePlaylistDialog
import com.example.musicplayercursor.view.FavouritesScreen

private data class PlaylistFolderUi(
    val id: String? = null,
    val title: String,
    val isAddFolder: Boolean = false
)

    @Composable
    fun PlaylistsScreen(
        songs: List<Song> = emptyList(),
        playlists: List<Playlist> = emptyList(),
        isSelectionMode: Boolean = false,
        selectedSongs: Set<Long> = emptySet(),
        onPlay: (Song) -> Unit = {},
        onLongPress: (Song) -> Unit = {},
        onToggleSelection: (Long) -> Unit = {},
        onCreatePlaylist: (String) -> Unit = {},
        onLoadPlaylists: () -> Unit = {},
        onPlaylistClicked: (String) -> Unit = {},
        onDefaultFolderOpened: (String?) -> Unit = {}
    ) {
        val context = LocalContext.current
        var showCreateDialog by remember { mutableStateOf(false) }
        var opened by remember { mutableStateOf<String?>(null) }
        
        // Notify parent when default folder open state changes
        LaunchedEffect(opened) {
            onDefaultFolderOpened(opened)
        }
        
        // Load playlists when screen is shown
        LaunchedEffect(Unit) {
            onLoadPlaylists()
        }
        
        // Combine default folders with user-created playlists
        val defaultFolders = listOf(
            PlaylistFolderUi(title = "Recently added"),
            PlaylistFolderUi(title = "Most played"),
            PlaylistFolderUi(title = "Recently played"),
            PlaylistFolderUi(title = "Favourites")
        )
        
        val userPlaylists = playlists.map { playlist ->
            PlaylistFolderUi(id = playlist.id, title = playlist.name)
        }
        
        val allFolders = (defaultFolders + userPlaylists + listOf(
            PlaylistFolderUi(title = "New playlist", isAddFolder = true)
        ))

        when (opened) {
            "Recently added" -> {
                BackHandler { opened = null }
                RecentlyAdded(
                    songs = songs,
                    isSelectionMode = isSelectionMode,
                    selectedSongs = selectedSongs,
                    onPlay = onPlay,
                    onLongPress = onLongPress,
                    onToggleSelection = onToggleSelection
                )
            }
            "Most played" -> {
                BackHandler { opened = null }
                MostPlayed(
                    songs = songs,
                    isSelectionMode = isSelectionMode,
                    selectedSongs = selectedSongs,
                    onPlay = onPlay,
                    onLongPress = onLongPress,
                    onToggleSelection = onToggleSelection
                )
            }
            "Recently played" -> {
                BackHandler { opened = null }
                RecentlyPlayed(
                    songs = songs,
                    isSelectionMode = isSelectionMode,
                    selectedSongs = selectedSongs,
                    onPlay = onPlay,
                    onLongPress = onLongPress,
                    onToggleSelection = onToggleSelection
                )
            }
            "Favourites" ->{
                BackHandler { opened = null }
                FavouritesScreen(
                    songs = songs,
                    isSelectionMode = isSelectionMode,
                    selectedSongs = selectedSongs,
                    onPlay = { onPlay(it) },
                    onLongPress = onLongPress,
                    onToggleSelection = onToggleSelection
                )
            }
            else -> {
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
                            PlaylistFolderCard(
                                folder = folder,
                                onClick = {
                                    if (folder.isAddFolder) {
                                        showCreateDialog = true
                                    } else {
                                        // Check if it's a user-created playlist or default folder
                                        if (folder.id != null) {
                                            // User-created playlist - open playlist songs screen
                                            onPlaylistClicked(folder.id)
                                        } else {
                                            // Default folder
                                            opened = folder.title
                                        }
                                    }
                                }
                            )
                        }
                    }
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
private fun PlaylistFolderCard(folder: PlaylistFolderUi, onClick: () -> Unit) {
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
            if (folder.isAddFolder) {
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

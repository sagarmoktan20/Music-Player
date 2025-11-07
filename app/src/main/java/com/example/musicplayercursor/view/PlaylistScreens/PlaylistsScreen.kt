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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.musicplayercursor.R
import androidx.activity.compose.BackHandler
import com.example.musicplayercursor.model.Song

private data class PlaylistFolderUi(
    val title: String,
    val isAddFolder: Boolean = false
)

@Composable
fun PlaylistsScreen(
    songs: List<Song> = emptyList(),
    onPlay: (Song) -> Unit = {}
) {
    val folders = listOf(
        PlaylistFolderUi("Recently added"),
        PlaylistFolderUi("Most played"),
        PlaylistFolderUi("Recently played"),
        PlaylistFolderUi("New playlist", isAddFolder = true)
    )

    var opened by remember { mutableStateOf<String?>(null) }

    when (opened) {
        "Recently added" -> {
            BackHandler { opened = null }
            RecentlyAdded(songs = songs, onPlay = onPlay)
        }
        "Most played" -> {
            BackHandler { opened = null }
            MostPlayed(songs = songs, onPlay = onPlay)
        }
        "Recently played" -> {
            BackHandler { opened = null }
            RecentlyPlayed(songs = songs, onPlay = onPlay)
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
                    items(folders) { folder ->
                        PlaylistFolderCard(
                            folder = folder,
                            onClick = {
                                if (!folder.isAddFolder) {
                                    opened = folder.title
                                } else {
                                    // TODO: create playlist flow (later)
                                }
                            }
                        )
                    }
                }
            }
        }
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
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .clickable { onClick() }
        )
    }
}

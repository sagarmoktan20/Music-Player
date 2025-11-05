package com.example.musicplayercursor.view

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.musicplayercursor.model.Song
import com.example.musicplayercursor.viewmodel.MusicViewModel
import com.example.musicplayercursor.viewmodel.PermissionViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MusicScreen(
	viewModel: MusicViewModel,
	permissionViewModel: PermissionViewModel,
	onRequestSongs: () -> Unit,
	onPlay: (Song) -> Unit,
	onToggle: () -> Unit
): Unit {
	val uiState by viewModel.uiState.collectAsStateWithLifecycle()
	val context = LocalContext.current

	val hasPermission by permissionViewModel.hasAudioPermission.collectAsStateWithLifecycle()

	LaunchedEffect(Unit) {
		permissionViewModel.refreshPermissionStatus(context)
	}

	LaunchedEffect(hasPermission) {
		if (hasPermission) {
			onRequestSongs()
		}
	}

	Scaffold(
		bottomBar = {
			if (uiState.current != null) {
				NowPlayingBar(
					title = uiState.current!!.title,
					artist = uiState.current!!.artist,
					isPlaying = uiState.isPlaying,
					onToggle = onToggle
				)
			}
		}
	) { padding ->
		val tabs = listOf("Favourites", "Playlists", "Tracks")
		val pagerState = rememberPagerState(initialPage = 2) { tabs.size }
		val scope = rememberCoroutineScope()

		Column(
			modifier = Modifier
				.fillMaxSize()
				.padding(padding)
		) {
			TabRow(selectedTabIndex = pagerState.currentPage) {
				tabs.forEachIndexed { index, title ->
					Tab(
						selected = pagerState.currentPage == index,
						onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
						text = { Text(title) }
					)
				}
			}

			HorizontalPager(state = pagerState) { page ->
				when (page) {
					0 -> {
						// Favourites screen (already implemented elsewhere)
						FavouritesScreen()
					}

					1 -> {
						// Playlists screen (already implemented elsewhere)
						PlaylistsScreen()
					}

					else -> {
						// Tracks page reuses the existing list to avoid breaking MVVM
						LazyColumn(
							modifier = Modifier
								.fillMaxSize()
						) {
							items(uiState.songs, key = { it.id }) { song ->
								SongRow(song = song) { onPlay(song) }
							}
						}
					}
				}
			}
		}
	}
}
@Composable
private fun SongRow(song: Song, onClick: () -> Unit) {
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.clickable { onClick() }
			.padding(horizontal = 16.dp, vertical = 12.dp)
	) {
		Text(text = song.title, style = MaterialTheme.typography.titleMedium)
		Spacer(Modifier.height(2.dp))
		Text(text = song.artist, style = MaterialTheme.typography.bodyMedium)
	}
}
@Composable
private fun NowPlayingBar(
	title: String,
	artist: String,
	isPlaying: Boolean,
	onToggle: () -> Unit
) {
	Surface(tonalElevation = 2.dp) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(12.dp),
			horizontalArrangement = Arrangement.SpaceBetween
		) {
			Column(Modifier.weight(1f)) {
				Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
				Text(artist, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
			}
			Spacer(Modifier.width(12.dp))
			Button(onClick = onToggle) {
				Text(if (isPlaying) "Pause" else "Play")
			}
		}
	}
}
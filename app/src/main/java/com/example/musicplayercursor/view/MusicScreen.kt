package com.example.musicplayercursor.view

import androidx.compose.foundation.ExperimentalFoundationApi
//import androidx.compose.foundation.R
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.musicplayercursor.model.Song
import com.example.musicplayercursor.viewmodel.MusicViewModel
import com.example.musicplayercursor.viewmodel.PermissionViewModel
import kotlinx.coroutines.launch
import com.example.musicplayercursor.R   // <-- replace with your actual package name
import com.example.musicplayercursor.view.PlaylistScreens.PlaylistsScreen

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

	var showTrackScreen by remember { mutableStateOf(false) }

	Scaffold(
		bottomBar = {
			if (!showTrackScreen) {
			if (uiState.current != null) {
				NowPlayingBar(
					title = uiState.current!!.title,
					artist = uiState.current!!.artist,
					isPlaying = uiState.isPlaying,
					onToggle = onToggle,
					onPrevious = { viewModel.playPreviousSong(context) },
					onNext = { viewModel.playNextSong(context) },
					onClick = { showTrackScreen = true }
				)
			}
		}
		}
	) { padding ->
		if (showTrackScreen && uiState.current != null) {
			BackHandler(onBack = { showTrackScreen = false })
			Box(
				modifier = Modifier
					.fillMaxSize()
					.padding(padding)
			) {
				TrackScreen(
					title = uiState.current!!.title,
					artist = uiState.current!!.artist,
					isPlaying = uiState.isPlaying,
					currentPosition = uiState.currentPosition,
					duration = uiState.duration,
					isFavourite = uiState.current!!.isFavourite,
					onToggle = onToggle,
					onPrevious = { viewModel.playPreviousSong(context) },
					onNext = { viewModel.playNextSong(context) },
					onSeek = { position -> viewModel.seekTo(position) },
					onToggleFavourite = { viewModel.toggleFavourite(context, uiState.current!!) }
				)
			}
		} else {
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
                        FavouritesScreen(
                            songs = uiState.songs,
                            onPlay = { onPlay(it) }
                        )
                    }

						1 -> {
							// Playlists screen (already implemented elsewhere)
							PlaylistsScreen(
								songs = uiState.songs,
								onPlay = { onPlay(it) }
							)
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
}
@Composable
private fun SongRow(song: Song, onClick: () -> Unit) {
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.clickable { onClick() }
			.padding(horizontal = 16.dp, vertical = 8.dp)
	) {
		Row(
			verticalAlignment = Alignment.CenterVertically,
			modifier = Modifier.fillMaxWidth()
		) {
			// Music icon
			Icon(
				painter = painterResource(id = R.drawable.music),
				contentDescription = "Song",//
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
@Composable
private fun NowPlayingBar(
	title: String,
	artist: String,
	isPlaying: Boolean,
	onToggle: () -> Unit,
	onPrevious: () -> Unit,
	onNext: () -> Unit,
	onClick: () -> Unit
) {
	Surface(shape = RoundedCornerShape(36.dp),
		tonalElevation = 2.dp,
		shadowElevation = 4.dp,                    // optional: a little lift
		modifier = Modifier.fillMaxWidth()) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(12.dp),
			horizontalArrangement = Arrangement.SpaceBetween,
			verticalAlignment = Alignment.CenterVertically
		) {
			// Song info - clickable to open TrackScreen
			Column(
				modifier = Modifier
					.weight(1f)
					.clickable { onClick() }
			) {
				Row(
					verticalAlignment = Alignment.CenterVertically,
					modifier = Modifier.fillMaxWidth()
				) {
					// Music icon
					Icon(
						painter = painterResource(id = R.drawable.music),
						contentDescription = "Song",//
						tint = Color.Unspecified,
						modifier = Modifier
							.size(40.dp)
							.padding(end = 8.dp)
					)
				Text(
					title,
					style = MaterialTheme.typography.bodyLarge,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis
				)}
				Text(
					artist,
					style = MaterialTheme.typography.bodyMedium,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis
				)
			}
			
			Spacer(Modifier.width(8.dp))
			
			// Playback controls
			Row(
				horizontalArrangement = Arrangement.spacedBy(4.dp),
				verticalAlignment = Alignment.CenterVertically
			) {
				// Previous button
				IconButton(onClick = onPrevious) {
					Icon(
						imageVector = Icons.Default.SkipPrevious,
						contentDescription = "Previous",
						modifier = Modifier.size(28.dp)
					)
				}
				
				// Play/Pause button
				IconButton(onClick = onToggle) {
					Icon(
						imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
						contentDescription = if (isPlaying) "Pause" else "Play",
						modifier = Modifier.size(32.dp)
					)
				}
				
				// Next button
				IconButton(onClick = onNext) {
					Icon(
						imageVector = Icons.Default.SkipNext,
						contentDescription = "Next",
						modifier = Modifier.size(28.dp)
					)
				}
			}
		}
	}
}
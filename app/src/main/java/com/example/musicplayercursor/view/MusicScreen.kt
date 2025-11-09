package com.example.musicplayercursor.view

import androidx.compose.foundation.ExperimentalFoundationApi
//import androidx.compose.foundation.R
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
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
import com.example.musicplayercursor.view.PlaylistScreens.PlaylistFolderList
import com.example.musicplayercursor.view.PlaylistScreens.PlaylistSongsScreen

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
	var showPlaylistFolderList by remember { mutableStateOf(false) }
	var selectedPlaylistId by remember { mutableStateOf<String?>(null) }
	
	// Handle back button in selection mode
	BackHandler(enabled = uiState.isSelectionMode && !showPlaylistFolderList && selectedPlaylistId == null) {
		viewModel.exitSelectionMode()
	}
	
	// Handle back button for playlist folder list
	BackHandler(enabled = showPlaylistFolderList) {
		showPlaylistFolderList = false
	}
	
	// Handle back button for playlist songs screen
	BackHandler(enabled = selectedPlaylistId != null) {
		selectedPlaylistId = null
	}

	Scaffold(
		bottomBar = {
			if (!showTrackScreen) {
				// Show selection bottom bar when in selection mode and songs are selected
				if (uiState.isSelectionMode && uiState.selectedSongs.isNotEmpty() && !showPlaylistFolderList && selectedPlaylistId == null) {
					SelectionBottomBar(
						selectedCount = uiState.selectedSongs.size,
						onShare = { viewModel.shareSelectedSongs(context) },
						onDelete = { viewModel.deleteSelectedSongs(context) },
						onAdd = { showPlaylistFolderList = true }
					)
				} else if (uiState.current != null && selectedPlaylistId == null) {
					// Show now playing bar when not in selection mode
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
		if (showPlaylistFolderList) {
			Box(
				modifier = Modifier
					.fillMaxSize()
					.padding(padding)
			) {
				PlaylistFolderList(
					playlists = uiState.playlists,
					selectedSongIds = uiState.selectedSongs,
					onCreatePlaylist = { name ->
						viewModel.createPlaylist(context, name)
						viewModel.loadPlaylists(context)
					},
					onPlaylistSelected = { playlistId, songIds ->
						if (playlistId == "favourites") {
							// Favourites folder - add songs to favourites
							viewModel.addSongsToFavourites(context, songIds)
							showPlaylistFolderList = false
						} else if (playlistId != null) {
							// User-created playlist - add songs to it
							viewModel.addSongsToPlaylist(context, playlistId, songIds)
							showPlaylistFolderList = false
							// Show the playlist songs screen after adding
							selectedPlaylistId = playlistId
						} else {
							// Other default folders - handle later if needed
							showPlaylistFolderList = false
						}
					},
					onDismiss = { showPlaylistFolderList = false },
					onLoadPlaylists = { viewModel.loadPlaylists(context) }
				)
			}
		} else if (selectedPlaylistId != null) {
			Box(
				modifier = Modifier
					.fillMaxSize()
					.padding(padding)
			) {
				// Get songs in the selected playlist - this will update when playlists or songs change
				val playlistSongs = remember(selectedPlaylistId, uiState.playlists, uiState.songs) {
					viewModel.getSongsInPlaylist(selectedPlaylistId!!)
				}
				PlaylistSongsScreen(
					songs = playlistSongs,
					isSelectionMode = uiState.isSelectionMode,
					selectedSongs = uiState.selectedSongs,
					onPlay = { onPlay(it) },
					onLongPress = { song -> viewModel.enterSelectionMode(song.id) },
					onToggleSelection = { songId -> viewModel.toggleSongSelection(songId) }
				)
			}
		} else if (showTrackScreen && uiState.current != null) {
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
                            isSelectionMode = uiState.isSelectionMode,
                            selectedSongs = uiState.selectedSongs,
                            onPlay = { onPlay(it) },
                            onLongPress = { song -> viewModel.enterSelectionMode(song.id) },
                            onToggleSelection = { songId -> viewModel.toggleSongSelection(songId) }
                        )
                    }

						1 -> {
							// Playlists screen (already implemented elsewhere)
							PlaylistsScreen(
								songs = uiState.songs,
								playlists = uiState.playlists,
								isSelectionMode = uiState.isSelectionMode,
								selectedSongs = uiState.selectedSongs,
								onPlay = { onPlay(it) },
								onLongPress = { song -> viewModel.enterSelectionMode(song.id) },
								onToggleSelection = { songId -> viewModel.toggleSongSelection(songId) },
								onCreatePlaylist = { name -> viewModel.createPlaylist(context, name) },
								onLoadPlaylists = { viewModel.loadPlaylists(context) },
								onPlaylistClicked = { playlistId -> selectedPlaylistId = playlistId }
							)
						}

						else -> {
							// Tracks page reuses the existing list to avoid breaking MVVM
							LazyColumn(
								modifier = Modifier
									.fillMaxSize()
							) {
								items(uiState.songs, key = { it.id }) { song ->
									SongRow(
										song = song,
										isSelected = uiState.selectedSongs.contains(song.id),
										isSelectionMode = uiState.isSelectionMode,
										onClick = {
											if (uiState.isSelectionMode) {
												viewModel.toggleSongSelection(song.id)
											} else {
												onPlay(song)
											}
										},
										onLongPress = {
											viewModel.enterSelectionMode(song.id)
										}
									)
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
private fun SongRow(
	song: Song,
	isSelected: Boolean,
	isSelectionMode: Boolean,
	onClick: () -> Unit,
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
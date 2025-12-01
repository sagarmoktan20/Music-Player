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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.material3.Button
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
import com.example.musicplayercursor.view.SearchScreen
import com.example.musicplayercursor.view.BroadcastDialog
import com.example.musicplayercursor.view.BroadcastScreen
import com.example.musicplayercursor.view.ConnectBottomSheet
import com.example.musicplayercursor.viewmodel.BroadcastViewModel
import com.example.musicplayercursor.viewmodel.ConnectViewModel
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.graphics.asImageBitmap
import com.example.musicplayercursor.util.QRCodeGenerator
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MusicScreen(
	viewModel: MusicViewModel,
	permissionViewModel: PermissionViewModel,
	broadcastViewModel: BroadcastViewModel,
	connectViewModel: ConnectViewModel,
	onRequestSongs: () -> Unit,
	onPlay: (Song) -> Unit,
	onToggle: () -> Unit
): Unit {
	val uiState by viewModel.uiState.collectAsStateWithLifecycle()
	val context = LocalContext.current
	val connectState by connectViewModel.connectState.collectAsStateWithLifecycle()


	val hasPermission by permissionViewModel.hasAudioPermission.collectAsStateWithLifecycle()
	var showConnectBottomSheet by remember { mutableStateOf(false) }
	var showQRDialog by remember { mutableStateOf(false) } // ADD THIS
	
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
	var showDeleteDialog by remember { mutableStateOf(false) }
	var openedDefaultFolder by remember { mutableStateOf<String?>(null) }
	var showSearchScreen by remember { mutableStateOf(false) }
	var showMenu by remember { mutableStateOf(false) }
	var showBroadcastDialog by remember { mutableStateOf(false) }
	var showBroadcastScreen by remember { mutableStateOf(false) }
	
	val broadcastState by broadcastViewModel.broadcastState.collectAsStateWithLifecycle()
	// Auto-open TrackScreen when receiver connects

	LaunchedEffect(connectState.isConnected) {
		if (connectState.isConnected) {
			showTrackScreen = true  // Automatically open TrackScreen when connected
		}
	}
	// Auto-show BroadcastScreen when broadcast starts
	LaunchedEffect(broadcastState.isBroadcasting) {
		if (broadcastState.isBroadcasting) {
			showBroadcastScreen = true
		}
	}
	
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
		if (uiState.isSelectionMode) {
			// If in selection mode, exit selection mode first
			viewModel.exitSelectionMode()
		} else {
			// Otherwise, exit the playlist view
			selectedPlaylistId = null
		}
	}
	
	// Handle back button for search screen
	BackHandler(enabled = showSearchScreen) {
		showSearchScreen = false
	}

	// ADD THIS: Generate QR code for the dialog (only when broadcasting)
	val qrCodeBitmap = remember(broadcastState.serverIP, broadcastState.token, showQRDialog) {
		if (showQRDialog && broadcastState.serverIP != null && broadcastState.token != null) {
			val deepLink = "musicplayer://broadcast?ip=${broadcastState.serverIP}&token=${broadcastState.token}"
			QRCodeGenerator.generateQRCode(deepLink, 512)
		} else {
			null
		}
	}

	Scaffold(
		bottomBar = {
			if (!showTrackScreen) {
				// Show selection bottom bar when in selection mode and songs are selected
				if (uiState.isSelectionMode && uiState.selectedSongs.isNotEmpty() && !showPlaylistFolderList) {
					SelectionBottomBar(
						selectedCount = uiState.selectedSongs.size,
						onShare = { viewModel.shareSelectedSongs(context) },
						onDelete = { showDeleteDialog = true },
						onRemove = when {
							// Inside a user-created playlist
							selectedPlaylistId != null -> {
								{ viewModel.removeSelectedSongsFromPlaylist(context, selectedPlaylistId!!) }
							}
							// Inside Favourites (either opened via default folder or on the Favourites tab)
							openedDefaultFolder == "Favourites" -> {
								{ viewModel.removeSelectedSongsFromFavourites(context) }
							}
							else -> null
						},
						onAdd = { showPlaylistFolderList = true }
					)
				} else {
					// ALWAYS show now playing bar (even when empty or in receiver mode)
					val hasCurrentSong = uiState.current != null
					val isReceiverMode = connectState.isConnected

					// Determine title and artist
					val title = when {
						isReceiverMode -> "BroadCasted Song.mp3"  // Receiver mode: always show this
						hasCurrentSong -> uiState.current!!.title  // Local song
						else -> "No song playing"  // Empty state
					}

					val artist = when {
						isReceiverMode -> {
							connectState.broadcastSongInfo?.title ?: "Broadcasting..."  // Show broadcaster's song if available
						}
						hasCurrentSong -> uiState.current!!.artist  // Local song
						else -> "Tap a song to play"  // Empty state
					}

					val isPlaying = if (isReceiverMode) {
						connectState.broadcastSongInfo?.isPlaying ?: false
					} else {
						uiState.isPlaying
					}

					// MODIFY THIS: Wrap NowPlayingBar with windowInsetsPadding for consistent placement
					Box(
						modifier = Modifier
							.fillMaxWidth()
							.windowInsetsPadding(WindowInsets.navigationBars)
					) {
						NowPlayingBar(
							title = title,
							artist = artist,
							isPlaying = isPlaying,
							isEmpty = !hasCurrentSong && !isReceiverMode,  // Empty = no local song AND not in receiver mode
							onToggle = { if (hasCurrentSong || isReceiverMode) onToggle() },
							onPrevious = { if (hasCurrentSong && !isReceiverMode) viewModel.playPreviousSong(context) },
							onNext = { if (hasCurrentSong && !isReceiverMode) viewModel.playNextSong(context) },
							onClick = {
								if (hasCurrentSong || isReceiverMode) {
									showTrackScreen = true  // Open TrackScreen for both local songs and receiver mode
								}
							}
						)
					}
				}
			}
		}
	) { padding ->
		if (showSearchScreen) {
			BackHandler(onBack = { showSearchScreen = false })
			Box(
				modifier = Modifier
					.fillMaxSize()
					.padding(padding)
			) {
				SearchScreen(
					allSongs = uiState.songs,
					viewModel = viewModel,
					onBack = { showSearchScreen = false }
				)
			}
		} else if (showPlaylistFolderList) {
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
					onLoadPlaylists = { viewModel.loadPlaylists(context) },
					onDefaultFolderOpened = { folder: String? -> openedDefaultFolder = folder }
				)
			}
		} else if ((showTrackScreen && uiState.current != null) || connectState.isConnected) {
			// Prevent back navigation when in receiver mode
			if (connectState.isConnected) {
				BackHandler(enabled = true) {
					// Disabled - user must disconnect to go back
				}
			} else {
				BackHandler(onBack = { showTrackScreen = false })
			}

			Box(
				modifier = Modifier
					.fillMaxSize()
					.padding(padding)
			) {
				// Determine title and artist based on receiver mode
				val title = if (connectState.isConnected) {
					"BroadCasted Song.mp3"  // Always show this in receiver mode
				} else {
					uiState.current!!.title  // Normal local song
				}

				val artist = if (connectState.isConnected) {
					connectState.broadcastSongInfo?.title ?: "Broadcasting..."  // Show broadcaster's song info if available
				} else {
					uiState.current!!.artist  // Normal local song
				}

				val isReceiverMode = connectState.isConnected

				TrackScreen(
					title = title,
					artist = artist,
					isPlaying = if (isReceiverMode) {
						connectState.broadcastSongInfo?.isPlaying ?: false  // Use broadcast state
					} else {
						uiState.isPlaying  // Use local state
					},
					currentPosition = if (isReceiverMode) {
						connectState.broadcastSongInfo?.positionMs ?: 0L  // Use broadcast position
					} else {
						uiState.currentPosition  // Use local position
					},
					duration = if (isReceiverMode) {
						connectState.broadcastSongInfo?.durationMs ?: 0L  // Use broadcast duration
					} else {
						uiState.duration  // Use local duration
					},
					isFavourite = if (isReceiverMode) false else uiState.current!!.isFavourite,  // No favourite in receiver mode
					isLooping = if (isReceiverMode) false else uiState.isLooping,  // No loop in receiver mode
					onToggle = onToggle,
					onPrevious = { if (!isReceiverMode) viewModel.playPreviousSong(context) },
					onNext = { if (!isReceiverMode) viewModel.playNextSong(context) },
					onSeek = { position -> if (!isReceiverMode) viewModel.seekTo(position) },
					onToggleFavourite = { 
						if (!isReceiverMode && uiState.current != null) {
							// Use the current song from the latest state
							viewModel.toggleFavourite(context, uiState.current!!)
						}
					},
					onToggleLoop = { if (!isReceiverMode) viewModel.toggleLoop() },
					isReceiverMode = isReceiverMode,
					isConnectedToBroadcast = connectState.isConnected,
					onDisconnect = { connectViewModel.disconnectFromBroadcast() }
				)
			}
		}else if (selectedPlaylistId != null) {
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
					currentSongId = if (!connectState.isConnected) uiState.current?.id else null, // ADD THIS
					onPlay = { song ->
						val queueIds = playlistSongs.map { it.id }
						viewModel.playFromQueue(context, queueIds, song.id, selectedPlaylistId)
					},
					onLongPress = { song -> viewModel.enterSelectionMode(song.id) },
					onToggleSelection = { songId -> viewModel.toggleSongSelection(songId) }
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
				// Header row - show "Stop Broadcast" button when broadcasting, "Disconnect" when connected, otherwise "Music Player" text
				Row(
					modifier = Modifier
						.fillMaxWidth()
						.padding(horizontal = 16.dp, vertical = 8.dp),
					horizontalArrangement = Arrangement.SpaceBetween,
					verticalAlignment = Alignment.CenterVertically
				) {
					// Show "Stop Broadcast" button when broadcasting, "Disconnect" when connected, otherwise "Music Player" text
					if (broadcastState.isBroadcasting) {
						Button(
							onClick = {
								broadcastViewModel.stopBroadcast(context)
							}
						) {
							Text("Stop Broadcast")
						}
					} else if (connectState.isConnected) {
						Button(
							onClick = {
								connectViewModel.disconnectFromBroadcast()
							},
							colors = androidx.compose.material3.ButtonDefaults.buttonColors(
							 containerColor = MaterialTheme.colorScheme.error
							)
						) {
							Text("Disconnect", color = MaterialTheme.colorScheme.onError)
						}
					} else {
						Text(
							text = "Music Player",
							style = MaterialTheme.typography.titleLarge
						)
					}
					
					// Search icon and Menu icon on the right
					Row(
						horizontalArrangement = Arrangement.spacedBy(8.dp),
						verticalAlignment = Alignment.CenterVertically
					) {
						// Search icon
						IconButton(onClick = { showSearchScreen = true }) {
							Icon(
								imageVector = Icons.Default.Search,
								contentDescription = "Search"
							)
						}
						
						// Three-dot menu icon
						Box {
							IconButton(onClick = { showMenu = true }) {
								Icon(
									imageVector = Icons.Default.MoreVert,
									contentDescription = "Menu"
								)
							}
							
							// Dropdown menu
							DropdownMenu(
								expanded = showMenu,
								onDismissRequest = { showMenu = false }
							) {
								DropdownMenuItem(
									text = { Text("Broadcast") },
									onClick = {
										showMenu = false
										if (broadcastViewModel.isHotspotActive()) {
											// Hotspot is active, start broadcast
											broadcastViewModel.startBroadcast(context)
										} else {
											// Show dialog to enable hotspot
											showBroadcastDialog = true
										}
									}
								)
								DropdownMenuItem(
									text = { Text("Connect") },
									onClick = {
										showMenu = false
										showConnectBottomSheet = true
									},
									enabled = !connectState.isConnected && !broadcastState.isBroadcasting
								)
								// ADD THIS: Show QR button (only when broadcasting)
								if (broadcastState.isBroadcasting) {
									DropdownMenuItem(
										text = { Text("Show QR") },
										onClick = {
											showMenu = false
											showQRDialog = true
										}
									)
								}
							}
						}
					}
				}
				
				// Show BroadcastScreen content when broadcasting and showBroadcastScreen is true, otherwise show tabs and pager
				if (broadcastState.isBroadcasting && showBroadcastScreen) {
					BroadcastScreen(
						viewModel = broadcastViewModel,
						onStop = {
							broadcastViewModel.stopBroadcast(context)
							showBroadcastScreen = false
						},
						onBack = {
							// Just hide BroadcastScreen, don't stop broadcast
							showBroadcastScreen = false
						}
					)
				} else {
					TabRow(selectedTabIndex = pagerState.currentPage) {
					tabs.forEachIndexed { index, title ->
						Tab(
							selected = pagerState.currentPage == index,
							onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
							text = { Text(title) }
						)
					}
				}

				HorizontalPager(
					state = pagerState,
					userScrollEnabled = !uiState.isSelectionMode && uiState.selectedSongs.isEmpty()
				) { page ->
					when (page) {
                    0 -> {
                        val favouriteSongs = remember(uiState.songs) { uiState.songs.filter { it.isFavourite } }
                        FavouritesScreen(
                            songs = favouriteSongs,
                            isSelectionMode = uiState.isSelectionMode,
                            selectedSongs = uiState.selectedSongs,
                            currentSongId = if (!connectState.isConnected) uiState.current?.id else null, // ADD THIS
                            onPlay = { song ->
                                val queueIds = favouriteSongs.map { it.id }
                                viewModel.playFromQueue(context, queueIds, song.id, "favourites")
                            },
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
                                onPlay = { song ->
                                    if (openedDefaultFolder == "Favourites") {
                                        val favs = uiState.songs.filter { it.isFavourite }
                                        viewModel.playFromQueue(context, favs.map { it.id }, song.id, "favourites")
                                    } else {
                                        onPlay(song)
                                    }
                                },
								onLongPress = { song -> viewModel.enterSelectionMode(song.id) },
								onToggleSelection = { songId -> viewModel.toggleSongSelection(songId) },
								onCreatePlaylist = { name -> viewModel.createPlaylist(context, name) },
								onLoadPlaylists = { viewModel.loadPlaylists(context) },
								onPlaylistClicked = { playlistId ->
									selectedPlaylistId = playlistId
									openedDefaultFolder = null
								},
								onDefaultFolderOpened = { folder ->
									openedDefaultFolder = folder
								},
								onDeletePlaylist = { playlistId ->
									viewModel.deletePlaylist(context, playlistId)
									viewModel.loadPlaylists(context)
									// If the deleted playlist was open, close it
									if (selectedPlaylistId == playlistId) {
										selectedPlaylistId = null
									}
								}
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
										isCurrentlyPlaying = !connectState.isConnected && uiState.current?.id == song.id, // ADD THIS: Check if this is the current song
										onClick = {
											if (uiState.isSelectionMode) {
												viewModel.toggleSongSelection(song.id)
											} else {
                                                viewModel.playFromQueue(
                                                    context,
                                                    uiState.songs.map { it.id },
                                                    song.id,
                                                    "all"
                                                )
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
	
	// Broadcast dialog
	if (showBroadcastDialog) {
		BroadcastDialog(
			viewModel = broadcastViewModel,
			onDismiss = { showBroadcastDialog = false }
		)
	}
	
	// Connect bottom sheet
	if (showConnectBottomSheet) {
		BackHandler(onBack = { showConnectBottomSheet = false })
		ConnectBottomSheet(
			connectViewModel = connectViewModel,
			onDismiss = { showConnectBottomSheet = false },
			onConnected = {
				showConnectBottomSheet = false
			}
		)
	}
	
	// Delete confirmation dialog
	if (showDeleteDialog) {
		DeleteConfirmationDialog(
			songCount = uiState.selectedSongs.size,
			onDismiss = { showDeleteDialog = false },
			onConfirm = {
				viewModel.deleteSelectedSongs(context)
				showDeleteDialog = false
			}
		)
	}

	// QR Code Dialog
	if (showQRDialog) {
		AlertDialog(
			onDismissRequest = { showQRDialog = false },
			title = {
				Text("Scan to Connect")
			},
			text = {
				Column(
					modifier = Modifier
						.fillMaxWidth()
						.padding(16.dp),
					horizontalAlignment = Alignment.CenterHorizontally
				) {
					if (qrCodeBitmap != null) {
						Image(
							bitmap = qrCodeBitmap.asImageBitmap(),
							contentDescription = "QR Code for broadcast connection",
							modifier = Modifier.size(300.dp)
						)
//						Spacer(modifier = Modifier.height(16.dp))
						Spacer(modifier = Modifier.height(16.dp))
						if (broadcastState.serverIP != null) {
							Text(
								text = "${broadcastState.serverIP}:8080",
								style = MaterialTheme.typography.bodyMedium,
								textAlign = TextAlign.Center
							)
						}
					} else {
						Text(
							text = "Generating QR code...",
							style = MaterialTheme.typography.bodyMedium
						)
					}
				}
			},
			confirmButton = {
				TextButton(onClick = { showQRDialog = false }) {
					Text("Close")
				}
			}
		)
	}
}
@Composable
private fun SongRow(
	song: Song,
	isSelected: Boolean,
	isSelectionMode: Boolean,
	isCurrentlyPlaying: Boolean = false, // ADD THIS: Parameter to indicate if this is the current song
	onClick: () -> Unit,
	onLongPress: () -> Unit
) {
	// Determine background color: selection takes priority, then current song highlight
	val backgroundColor = when {
		isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
		isCurrentlyPlaying -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f) // Lighter highlight for current song
		else -> Color.Transparent
	}
	
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.background(backgroundColor) // MODIFY THIS: Use the determined background color
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
	isEmpty: Boolean = false,  // ADD THIS PARAMETER
	onToggle: () -> Unit,
	onPrevious: () -> Unit,
	onNext: () -> Unit,
	onClick: () -> Unit
) {
	Surface(
		shape = RoundedCornerShape(36.dp),
		tonalElevation = 2.dp,
		shadowElevation = 4.dp,
		modifier = Modifier.fillMaxWidth(),
		color = if (isEmpty) {
			MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)  // Dimmed when empty
		} else {
			MaterialTheme.colorScheme.surface
		}
	) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(12.dp),
			horizontalArrangement = Arrangement.SpaceBetween,
			verticalAlignment = Alignment.CenterVertically
		) {
			// Song info - clickable only when NOT empty
			Column(
				modifier = Modifier
					.weight(1f)
					.then(
						if (!isEmpty) {
							Modifier.clickable { onClick() }
						} else {
							Modifier  // No click when empty
						}
					)
			) {
				Row(
					verticalAlignment = Alignment.CenterVertically,
					modifier = Modifier.fillMaxWidth()
				) {
					// Music icon
					Icon(
						painter = painterResource(id = R.drawable.music),
						contentDescription = "Song",
						tint = if (isEmpty) {
							MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)  // Dimmed when empty
						} else {
							Color.Unspecified
						},
						modifier = Modifier
							.size(40.dp)
							.padding(end = 8.dp)
					)
					Text(
						title,
						style = MaterialTheme.typography.bodyLarge,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
						color = if (isEmpty) {
							MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)  // Dimmed when empty
						} else {
							MaterialTheme.colorScheme.onSurface
						}
					)
				}
				Text(
					artist,
					style = MaterialTheme.typography.bodyMedium,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
					color = if (isEmpty) {
						MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)  // Dimmed when empty
					} else {
						MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
					}
				)
			}

			Spacer(Modifier.width(8.dp))

			// Playback controls - disabled when empty
			Row(
				horizontalArrangement = Arrangement.spacedBy(4.dp),
				verticalAlignment = Alignment.CenterVertically
			) {
				// Previous button
				IconButton(
					onClick = onPrevious,
					enabled = !isEmpty  // Disable when empty
				) {
					Icon(
						imageVector = Icons.Default.SkipPrevious,
						contentDescription = "Previous",
						modifier = Modifier.size(28.dp),
						tint = if (isEmpty) {
							MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)  // Dimmed when disabled
						} else {
							Color.Unspecified
						}
					)
				}

				// Play/Pause button
				IconButton(
					onClick = onToggle,
					enabled = !isEmpty  // Disable when empty
				) {
					Icon(
						imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
						contentDescription = if (isPlaying) "Pause" else "Play",
						modifier = Modifier.size(32.dp),
						tint = if (isEmpty) {
							MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)  // Dimmed when empty
						} else {
							Color.Unspecified
						}
					)
				}

				// Next button
				IconButton(
					onClick = onNext,
					enabled = !isEmpty  // Disable when empty
				) {
					Icon(
						imageVector = Icons.Default.SkipNext,
						contentDescription = "Next",
						modifier = Modifier.size(28.dp),
						tint = if (isEmpty) {
							MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)  // Dimmed when disabled
						} else {
							Color.Unspecified
						}
					)
				}
			}
		}
	}
}
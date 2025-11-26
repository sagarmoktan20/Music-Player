package com.example.musicplayercursor.viewmodel

import android.app.Activity
import android.content.ContentUris
import android.content.ContentValues.TAG
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.OptIn
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.example.musicplayercursor.model.Song
import com.example.musicplayercursor.model.Playlist
import com.example.musicplayercursor.repository.CurrentSongRepository
import com.example.musicplayercursor.repository.FavouritesRepository
import com.example.musicplayercursor.repository.MusicRepository
import com.example.musicplayercursor.repository.PlayCountRepository
import com.example.musicplayercursor.repository.LastPlayedRepository
import com.example.musicplayercursor.repository.PlaylistRepository
import com.example.musicplayercursor.service.MusicService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch


data class MusicUiState(
    val songs: List<Song> = emptyList(),
    val current: Song? = null,
    val isPlaying: Boolean = false,
    val currentPosition: Long = 0L,
    val duration: Long = 0L,
    val selectedSongs: Set<Long> = emptySet(),
    val isSelectionMode: Boolean = false,
    val playlists: List<Playlist> = emptyList(),
    val isLooping: Boolean = false
)
class MusicViewModel: ViewModel() {

    private var player: ExoPlayer? = null
    private var progressUpdateJob: Job? = null
    private var playerListener: androidx.media3.common.Player.Listener? = null
    private var favouritesRepository: FavouritesRepository? = null
    //private var playCountRepository: PlayCountRepository? = null
    //private var lastPlayedRepository: LastPlayedRepository? = null
    private var playlistRepository: PlaylistRepository? = null
    private var contentObserver: ContentObserver? = null
    private var contentResolver: android.content.ContentResolver? = null

    private var applicationContext: Context? = null
    private var musicService: MusicService? = null  // ADD THIS
    //private var applicationContext: Context? = null
    init {
        LastPlayedRepository.init(android.app.Application()) // Static initialization
        PlayCountRepository.init(android.app.Application()) // Static initialization
    }
    // Playback queue context (ids of songs in the active context order)
    private var currentQueueSongIds: List<Long> = emptyList()
    private var currentQueueSource: String? = null // e.g., "all", "favourites", or playlistId
    private val _uiState = MutableStateFlow(MusicUiState())
    val uiState: StateFlow<MusicUiState> = _uiState
    
    // Handler for ContentObserver (runs on main thread)
    private val handler = Handler(Looper.getMainLooper())
    
    // Debounce mechanism to avoid multiple rapid reloads
    private var reloadJob: Job? = null
    // Callback for MusicViewModel to handle notification actions
    private var viewModelActionCallback: ViewModelActionCallback? = null

    interface ViewModelActionCallback {
        fun onPlayPauseRequested()
        fun onNextRequested()
        fun onPreviousRequested()
    }

    fun setViewModelActionCallback(callback: ViewModelActionCallback?) {
        viewModelActionCallback = callback
        Log.d(TAG, "[setViewModelActionCallback] Callback set: ${callback != null}")
    }
    fun loadSongs(context: Context) {
        // Store content resolver reference for ContentObserver
        if (contentResolver == null) {
            applicationContext = context.applicationContext
            contentResolver = applicationContext?.contentResolver
            registerMediaStoreObserver()
        }

        // Initialize repositories
        LastPlayedRepository.init(context)
        PlayCountRepository.init(context)
        CurrentSongRepository.init(context) // ADD THIS
        
        viewModelScope.launch {
            favouritesRepository = FavouritesRepository(context)
            playlistRepository = PlaylistRepository(context)
            val repo = MusicRepository(context.contentResolver)
            val loadedSongs = repo.loadAudio()
            val favouriteIds = favouritesRepository?.getFavouriteSongIds() ?: emptySet()
            val playCounts = PlayCountRepository.getAllPlayCounts() ?: emptyMap()
            val lastPlayedMap = LastPlayedRepository.getAllLastPlayed() ?: emptyMap()
            val playlists = playlistRepository?.getAllPlaylists() ?: emptyList()

            // Restore current song state from persistence
            val savedSongId = CurrentSongRepository.getCurrentSongId()
            val savedPosition = CurrentSongRepository.getCurrentPosition()
            val savedIsPlaying = CurrentSongRepository.getIsPlaying()
            val savedIsReceiverMode = CurrentSongRepository.getIsReceiverMode()
            val savedReceiverStreamUrl = CurrentSongRepository.getReceiverStreamUrl()
            
            // Mark songs as favourite and add play counts based on SharedPreferences
            val songsWithFavouritesAndCounts = loadedSongs.map { song ->
                song.copy(
                    isFavourite = favouriteIds.contains(song.id),
                    playCount = playCounts[song.id] ?: 0,
                    lastPlayed = lastPlayedMap[song.id] ?: 0L
                )
            }
            
            // Restore current song if it exists
            val restoredCurrentSong = savedSongId?.let { id ->
                songsWithFavouritesAndCounts.find { it.id == id }?.let { song ->
                    song.copy(
                        isFavourite = favouriteIds.contains(song.id),
                        playCount = playCounts[song.id] ?: song.playCount,
                        lastPlayed = lastPlayedMap[song.id] ?: song.lastPlayed
                    )
                }
            }
            
            _uiState.value = _uiState.value.copy(
                songs = songsWithFavouritesAndCounts,
                current = restoredCurrentSong,
                currentPosition = savedPosition,
                isPlaying = savedIsPlaying,
                playlists = playlists
            )

            // Initialize player early if there's a restored song
            // Initialize player early if there's a restored song
            if (restoredCurrentSong != null && !savedIsReceiverMode && applicationContext != null) {
                preparePlayer(applicationContext!!)
                // Restore playback position but don't auto-play (user will need to tap play)
                player?.let { p ->
                    p.setMediaItem(MediaItem.fromUri(restoredCurrentSong.contentUri))
                    p.prepare()
                    // Wait for player to be ready before seeking and starting updates
                    p.addListener(object : androidx.media3.common.Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            if (playbackState == androidx.media3.common.Player.STATE_READY) {
                                p.seekTo(savedPosition)
                                // Update UI state with correct duration
                                _uiState.value = _uiState.value.copy(
                                    duration = p.duration.coerceAtLeast(0L),
                                    currentPosition = savedPosition
                                )
                                if (savedIsPlaying) {
                                    p.play()
                                }
                                // Start progress updates AFTER player is ready
                                startProgressUpdates()
                                // Remove this one-time listener
                                p.removeListener(this)
                            }
                        }
                    })
                }
            }
        }
    }

    
    fun loadPlaylists(context: Context) {
        viewModelScope.launch {
            if (playlistRepository == null) {
                playlistRepository = PlaylistRepository(context)
            }
            val playlists = playlistRepository?.getAllPlaylists() ?: emptyList()
            _uiState.value = _uiState.value.copy(playlists = playlists)
        }
    }
    
    fun createPlaylist(context: Context, name: String) {
        viewModelScope.launch {
            if (playlistRepository == null) {
                playlistRepository = PlaylistRepository(context)
            }
            playlistRepository?.addPlaylist(name)
            val playlists = playlistRepository?.getAllPlaylists() ?: emptyList()
            _uiState.value = _uiState.value.copy(playlists = playlists)
        }
    }
    
    fun deletePlaylist(context: Context, playlistId: String) {
        viewModelScope.launch {
            if (playlistRepository == null) {
                playlistRepository = PlaylistRepository(context)
            }
            playlistRepository?.deletePlaylist(playlistId)
            val playlists = playlistRepository?.getAllPlaylists() ?: emptyList()
            _uiState.value = _uiState.value.copy(playlists = playlists)
        }
    }
    
    private fun registerMediaStoreObserver() {
        val resolver = contentResolver ?: return
        val context = applicationContext ?: return
        
        contentObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                // Debounce: cancel previous reload job and start a new one after a short delay
                reloadJob?.cancel()
                reloadJob = viewModelScope.launch {
                    delay(500) // Wait 500ms to batch multiple rapid changes
                    loadSongs(context)
                }
            }
        }
        
        // Register observer for MediaStore audio changes
        resolver.registerContentObserver(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            true, // notifyForDescendants - also watch sub-URIs
            contentObserver!!
        )
    }

    fun preparePlayer(context: Context) {
        if (player != null) {
            // Player already exists - don't recreate it (this happens on screen rotation)
            // Just update applicationContext if needed
            if (applicationContext == null) {
                applicationContext = context.applicationContext
            }
            // Restart progress updates if they're not running
            if (progressUpdateJob?.isActive != true && _uiState.value.current != null) {
                startProgressUpdates()
            }
            return
        }

        applicationContext = context.applicationContext
        player = ExoPlayer.Builder(context).build().apply {
            val attrs = AudioAttributes.Builder()
                .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
                .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                .build()
            setAudioAttributes(attrs, true)
        }

        // Attach listener for auto-next on end (respects loop state)
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                // BLOCK auto-next if in receiver mode
                if (isReceiverMode) {
                    return  // Don't auto-play next song in receiver mode
                }

                if (playbackState == androidx.media3.common.Player.STATE_ENDED) {
                    applicationContext?.let { ctx ->
                        if (_uiState.value.isLooping) {
                            // Loop: restart current song
                            val currentSong = _uiState.value.current
                            if (currentSong != null) {
                                play(ctx, currentSong)
                            }
                        } else {
                            // Normal: play next song
                            playNextSong(ctx)
                        }
                    }
                }
            }
        }
        playerListener = listener
        player?.addListener(listener)
    }



    fun setMusicService(service: MusicService?) {
        musicService = service
        Log.d(TAG, "[setMusicService] Service set: ${service != null}")

        // Set callback so MusicService can forward actions to this ViewModel
        service?.setViewModelActionCallback(object : MusicService.ViewModelActionCallback {
            override fun onPlayPauseRequested() {
                Log.d(TAG, "[ViewModelActionCallback] Play/Pause requested from notification")
                togglePlayPause()
            }

            override fun onNextRequested() {
                Log.d(TAG, "[ViewModelActionCallback] Next requested from notification")
                applicationContext?.let { ctx ->
                    playNextSong(ctx)
                }
            }

            override fun onPreviousRequested() {
                Log.d(TAG, "[ViewModelActionCallback] Previous requested from notification")
                applicationContext?.let { ctx ->
                    playPreviousSong(ctx)
                }
            }
        })
    }


    fun play(context: Context, song: Song) {
        // BLOCK local playback if in receiver mode
        if (isReceiverMode) {
            Log.d(TAG, "[play] BLOCKED: Cannot play local song while in receiver mode")
            return
        }

        // If no queue is set, default to "all songs" order
        if (currentQueueSongIds.isEmpty()) {
            currentQueueSongIds = _uiState.value.songs.map { it.id }
            currentQueueSource = "all"
        }
        // ... rest of the function remains the same
        // Exit selection mode if active
        if (_uiState.value.isSelectionMode) {
            exitSelectionMode()
        }
        
        preparePlayer(context)
        val p = player ?: return
        
        // Initialize repositories if needed
//        if (playCountRepository == null) {
//            playCountRepository = PlayCountRepository(context)
//        }
//        if (lastPlayedRepository == null) {
//            lastPlayedRepository = LastPlayedRepository(context)
//        }
        
        // Increment play count
        PlayCountRepository?.incrementPlayCount(song.id)
        // Update last played timestamp
        val now = System.currentTimeMillis()
        LastPlayedRepository?.setLastPlayed(song.id, now)
        
        // Get the favourite status and updated play count from the song list or current song
        val existingSong = _uiState.value.songs.find { it.id == song.id }
        val favouriteStatus = existingSong?.isFavourite 
            ?: _uiState.value.current?.isFavourite 
            ?: song.isFavourite
        val updatedPlayCount = (existingSong?.playCount ?: song.playCount) + 1
        
        val songWithStatus = song.copy(
            isFavourite = favouriteStatus,
            playCount = updatedPlayCount,
            lastPlayed = now
        )
        
        // Update the song in the list with new play count
        val updatedSongs = _uiState.value.songs.map {
            if (it.id == song.id) it.copy(playCount = updatedPlayCount, lastPlayed = now) else it
        }
        
        p.stop()
        p.clearMediaItems()
        p.setMediaItem(MediaItem.fromUri(song.contentUri))
        p.prepare()
        p.play()
        _uiState.value = _uiState.value.copy(
            songs = updatedSongs,
            current = songWithStatus,
            isPlaying = true,
            currentPosition = 0L,
            duration = p.duration.coerceAtLeast(0L)
        )
        // Save current song state
        CurrentSongRepository.saveCurrentSong(
            songId = song.id,
            position = 0L,
            isPlaying = true,
            isReceiverMode = false
        )
        startProgressUpdates()

// Show notification via MusicService
        musicService?.showNotificationForViewModel(songWithStatus, true)
    }

    fun playFromQueue(
        context: Context,
        queueSongIds: List<Long>?,
        startSongId: Long,
        source: String?
    ) {
        // BLOCK local playback if in receiver mode
        if (isReceiverMode) {
            Log.d(TAG, "[playFromQueue] BLOCKED: Cannot play local song while in receiver mode")
            return
        }

        // Set queue context (if provided)
        if (queueSongIds != null) {
            currentQueueSongIds = queueSongIds
            currentQueueSource = source
        }
        // Resolve Song from current songs list
        val song = _uiState.value.songs.find { it.id == startSongId } ?: return
        play(context, song)
    }

    fun togglePlayPause() {
        // BLOCK local play/pause if in receiver mode
        if (isReceiverMode) {
            Log.d(TAG, "[togglePlayPause] BLOCKED: Cannot toggle local playback while in receiver mode")
            return
        }

        val p = player ?: return
        if (p.isPlaying) {
            p.pause()
            _uiState.value = _uiState.value.copy(isPlaying = false)
        } else {
            p.play()
            _uiState.value = _uiState.value.copy(isPlaying = true)
        }

        // Save play state immediately
        _uiState.value.current?.let { song ->
            CurrentSongRepository.saveCurrentSong(
                songId = song.id,
                position = p.currentPosition,
                isPlaying = p.isPlaying,
                isReceiverMode = false
            )
        }

        // Update notification with new play state IMMEDIATELY
        val currentPos = p.currentPosition
        val duration = p.duration.coerceAtLeast(0L)
        musicService?.updateNotificationWithPosition(p.isPlaying, currentPos, duration)
    }

    fun playNextSong(context: Context) {
        // BLOCK if in receiver mode
        if (isReceiverMode) {
            Log.d(TAG, "[playNextSong] BLOCKED: Cannot play next song while in receiver mode")
            return
        }

        val currentSong = _uiState.value.current ?: return
        val ids = currentQueueSongIds.ifEmpty { _uiState.value.songs.map { it.id } }
        val currentIndex = ids.indexOf(currentSong.id)
        
        // MODIFY THIS: Loop back to first song if at end of queue
        val nextIndex = if (currentIndex >= 0 && currentIndex < ids.size - 1) {
            // Normal case: play next song
            currentIndex + 1
        } else if (currentIndex >= 0 && currentIndex == ids.size - 1) {
            // At end of queue: loop back to first song (index 0)
            Log.d(TAG, "[playNextSong] Reached end of queue, looping back to first song")
            0
        } else {
            // Current song not found in queue: play first song
            Log.d(TAG, "[playNextSong] Current song not in queue, playing first song")
            0
        }
        
        val nextId = ids.getOrNull(nextIndex)
        if (nextId != null) {
            val nextSong = _uiState.value.songs.find { it.id == nextId }
            if (nextSong != null) {
                play(context, nextSong)
            } else {
                Log.w(TAG, "[playNextSong] Next song with id $nextId not found in songs list")
            }
        }
    }

    fun playPreviousSong(context: Context) {
        // BLOCK if in receiver mode
        if (isReceiverMode) {
            Log.d(TAG, "[playPreviousSong] BLOCKED: Cannot play previous song while in receiver mode")
            return
        }

        val currentSong = _uiState.value.current ?: return
        val ids = currentQueueSongIds.ifEmpty { _uiState.value.songs.map { it.id } }
        val currentIndex = ids.indexOf(currentSong.id)
        if (currentIndex > 0) {
            val prevId = ids[currentIndex - 1]
            val previousSong = _uiState.value.songs.find { it.id == prevId }
            if (previousSong != null) {
                play(context, previousSong)
            }
        }
    }

    fun seekTo(positionMs: Long) {
        val p = player ?: return
        p.seekTo(positionMs.coerceIn(0L, p.duration.coerceAtLeast(0L)))
        _uiState.value = _uiState.value.copy(currentPosition = positionMs)
    }

    fun toggleLoop() {
        _uiState.value = _uiState.value.copy(isLooping = !_uiState.value.isLooping)
    }

    fun toggleFavourite(context: Context, song: Song) {
        val repo = favouritesRepository ?: FavouritesRepository(context).also {
            favouritesRepository = it
        }
        
        viewModelScope.launch {
            val newFavouriteStatus = !song.isFavourite
            if (newFavouriteStatus) {
                repo.addFavourite(song.id)
            } else {
                repo.removeFavourite(song.id)
            }
            
            // Update the song in the list (preserve play count)
            val updatedSongs = _uiState.value.songs.map {
                if (it.id == song.id) it.copy(isFavourite = newFavouriteStatus) else it
            }
            
            // Update current song if it's the one being toggled (preserve play count)
            val updatedCurrent = if (_uiState.value.current?.id == song.id) {
                _uiState.value.current?.copy(isFavourite = newFavouriteStatus)
            } else {
                _uiState.value.current
            }
            
            _uiState.value = _uiState.value.copy(
                songs = updatedSongs,
                current = updatedCurrent
            )
        }
    }

    // Selection Mode Functions
    fun enterSelectionMode(songId: Long? = null) {
        val selected = if (songId != null) setOf(songId) else emptySet<Long>()
        _uiState.value = _uiState.value.copy(
            isSelectionMode = true,
            selectedSongs = selected
        )
    }
    
    fun exitSelectionMode() {
        _uiState.value = _uiState.value.copy(
            isSelectionMode = false,
            selectedSongs = emptySet()
        )
    }
    
    fun toggleSongSelection(songId: Long) {
        val currentSelected = _uiState.value.selectedSongs.toMutableSet()
        if (currentSelected.contains(songId)) {
            currentSelected.remove(songId)
        } else {
            currentSelected.add(songId)
        }
        
        _uiState.value = _uiState.value.copy(
            selectedSongs = currentSelected
        )
        
        // Exit selection mode if no songs are selected
        if (currentSelected.isEmpty()) {
            exitSelectionMode()
        }
    }
    
    fun clearSelection() {
        _uiState.value = _uiState.value.copy(
            selectedSongs = emptySet()
        )
    }
    
    // Action handlers for selected songs (placeholders for future implementation)
    fun shareSelectedSongs(context: Context) {
        // TODO: Implement share functionality
        val selectedIds = _uiState.value.selectedSongs
        // Placeholder: Exit selection mode after action
        exitSelectionMode()
    }

    fun deleteSelectedSongs(context: Context) {
        val selectedIds = _uiState.value.selectedSongs
        if (selectedIds.isEmpty()) return

        viewModelScope.launch {
            val songsToDelete = _uiState.value.songs.filter { selectedIds.contains(it.id) }
            if (songsToDelete.isEmpty()) return@launch

            // Lazy init of repositories
            if (playlistRepository == null) playlistRepository = PlaylistRepository(context)
            if (favouritesRepository == null) favouritesRepository = FavouritesRepository(context)
            //if (playCountRepository == null) playCountRepository = PlayCountRepository(context)
            //if (lastPlayedRepository == null) lastPlayedRepository = LastPlayedRepository(context)

            val resolver = context.contentResolver
            val urisToDelete = mutableListOf<Uri>()

            for (song in songsToDelete) {
                val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, song.id)
                try {
                    val deleted = resolver.delete(uri, null, null)
                    if (deleted > 0) {
                        Log.d("MusicViewModel", "✅ Deleted: ${song.title}")
                    } else {
                        // Add to pending list for manual user approval
                        urisToDelete.add(uri)
                        Log.w("MusicViewModel", "⚠️ Need user approval to delete: ${song.title}")
                    }
                } catch (e: SecurityException) {
                    urisToDelete.add(uri)
                    Log.w("MusicViewModel", "SecurityException for ${song.title}: ${e.message}")
                }
            }

            // 🔒 If Android R+ (API 30+) and some URIs require approval
            if (urisToDelete.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    val pendingIntent = MediaStore.createDeleteRequest(resolver, urisToDelete)
                    if (context is Activity) {
                        context.startIntentSenderForResult(
                            pendingIntent.intentSender,
                            999, // request code
                            null,
                            0,
                            0,
                            0
                        )
                    }
                } catch (e: Exception) {
                    Log.e("MusicViewModel", "Error launching delete request: ${e.message}", e)
                }
            }

            // 🗂️ Database cleanup
            playlistRepository?.removeSongsFromAllPlaylists(selectedIds)
            favouritesRepository?.removeFavourites(selectedIds)

            // 🎵 Stop current playback if song deleted
            val currentSong = _uiState.value.current
            if (currentSong != null && selectedIds.contains(currentSong.id)) {
                player?.stop()
                player?.clearMediaItems()
                progressUpdateJob?.cancel()
                _uiState.value = _uiState.value.copy(
                    current = null,
                    isPlaying = false,
                    currentPosition = 0L,
                    duration = 0L
                )
            }

            // 🧹 Update UI state
            val updatedSongs = _uiState.value.songs.filterNot { selectedIds.contains(it.id) }
            val playlists = playlistRepository?.getAllPlaylists() ?: emptyList()

            _uiState.value = _uiState.value.copy(
                songs = updatedSongs,
                playlists = playlists
            )

            exitSelectionMode()
        }
    }


    fun addSelectedSongs(context: Context) {
        // This is now handled by PlaylistFolderList
        // Keep this for backward compatibility but it shouldn't be called directly
        exitSelectionMode()
    }
    
    fun addSongsToPlaylist(context: Context, playlistId: String, songIds: Set<Long>) {
        viewModelScope.launch {
            if (playlistRepository == null) {
                playlistRepository = PlaylistRepository(context)
            }
            // Filter out songs that are already in the playlist to avoid duplicates
            val existingSongIds = playlistRepository?.getSongIdsInPlaylist(playlistId) ?: emptySet()
            val newSongIds = songIds - existingSongIds
            if (newSongIds.isNotEmpty()) {
                playlistRepository?.addSongsToPlaylist(playlistId, newSongIds)
                // Reload playlists to update UI
                val playlists = playlistRepository?.getAllPlaylists() ?: emptyList()
                _uiState.value = _uiState.value.copy(playlists = playlists)
            }
            // Exit selection mode after adding
            exitSelectionMode()
        }
    }
    
    fun removeSelectedSongsFromPlaylist(context: Context, playlistId: String) {
        val selectedIds = _uiState.value.selectedSongs
        if (selectedIds.isEmpty()) return

        viewModelScope.launch {
            if (playlistRepository == null) {
                playlistRepository = PlaylistRepository(context)
            }
            // Remove selected songs from the specific playlist
            playlistRepository?.removeSongsFromPlaylist(playlistId, selectedIds)
            // Reload playlists to update UI
            val playlists = playlistRepository?.getAllPlaylists() ?: emptyList()
            _uiState.value = _uiState.value.copy(playlists = playlists)
            // Exit selection mode after removing
            exitSelectionMode()
        }
    }
    
    fun addSongsToFavourites(context: Context, songIds: Set<Long>) {
        val repo = favouritesRepository ?: FavouritesRepository(context).also {
            favouritesRepository = it
        }
        
        viewModelScope.launch {
            // Get existing favourites to filter out duplicates
            val existingFavourites = repo.getFavouriteSongIds()
            val songsToAdd = songIds.filter { it !in existingFavourites }
            
            // Add each song to favourites (only if not already favourite)
            songsToAdd.forEach { songId ->
                repo.addFavourite(songId)
            }
            
            // Update the songs in the list to reflect favourite status
            val updatedSongs = _uiState.value.songs.map { song ->
                if (songIds.contains(song.id) && !song.isFavourite) {
                    song.copy(isFavourite = true)
                } else {
                    song
                }
            }
            
            // Update current song if it's in the selected songs
            val updatedCurrent = _uiState.value.current?.let { currentSong ->
                if (songIds.contains(currentSong.id) && !currentSong.isFavourite) {
                    currentSong.copy(isFavourite = true)
                } else {
                    currentSong
                }
            }
            
            _uiState.value = _uiState.value.copy(
                songs = updatedSongs,
                current = updatedCurrent
            )
            
            // Exit selection mode after adding
            exitSelectionMode()
        }
    }
    
    fun removeSelectedSongsFromFavourites(context: Context) {
        val repo = favouritesRepository ?: FavouritesRepository(context).also {
            favouritesRepository = it
        }
        val selectedIds = _uiState.value.selectedSongs
        if (selectedIds.isEmpty()) return
        
        viewModelScope.launch {
            // Remove IDs from favourites
            repo.removeFavourites(selectedIds)
            
            // Update songs and current to reflect removal
            val updatedSongs = _uiState.value.songs.map { song ->
                if (selectedIds.contains(song.id) && song.isFavourite) {
                    song.copy(isFavourite = false)
                } else song
            }
            val updatedCurrent = _uiState.value.current?.let { currentSong ->
                if (selectedIds.contains(currentSong.id) && currentSong.isFavourite) {
                    currentSong.copy(isFavourite = false)
                } else currentSong
            }
            
            _uiState.value = _uiState.value.copy(
                songs = updatedSongs,
                current = updatedCurrent
            )
            
            exitSelectionMode()
        }
    }
    
    fun getSongsInPlaylist(playlistId: String): List<Song> {
        val playlist = _uiState.value.playlists.find { it.id == playlistId }
        val songIds = playlist?.songIds ?: emptySet()
        return _uiState.value.songs.filter { songIds.contains(it.id) }
    }
    
    fun getFavouriteSongs(): List<Song> {
        return _uiState.value.songs.filter { it.isFavourite }
    }

    private fun startProgressUpdates() {
        progressUpdateJob?.cancel()
        progressUpdateJob = viewModelScope.launch {
            var lastSavedPosition = 0L
            var lastNotificationUpdate = 0L
            while (true) {
                delay(100) // Update every 100ms
                val p = player ?: break

                // Don't update if in receiver mode
                if (isReceiverMode) {
                    break
                }

                val currentPos = p.currentPosition
                val duration = p.duration

                if (duration > 0) {
                    _uiState.value = _uiState.value.copy(
                        currentPosition = currentPos,
                        duration = duration,
                        isPlaying = p.isPlaying
                    )

                    // Save position every 1 second (10 updates)
                    if (kotlin.math.abs(currentPos - lastSavedPosition) >= 1000) {
                        _uiState.value.current?.let { song ->
                            CurrentSongRepository.saveCurrentSong(
                                songId = song.id,
                                position = currentPos,
                                isPlaying = p.isPlaying,
                                isReceiverMode = false
                            )
                        }
                        lastSavedPosition = currentPos
                    }

                    // Update notification every 2 seconds (20 updates) to keep it in sync
                    if (kotlin.math.abs(currentPos - lastNotificationUpdate) >= 2000) {
                        _uiState.value.current?.let { song ->
                            musicService?.updateNotificationForViewModel(p.isPlaying)
                        }
                        lastNotificationUpdate = currentPos
                    }
                }
            }
        }
    }



    // Receiver mode playback (for connecting to broadcast)
   // private var receiverPlayer: ExoPlayer? = null
    private var isReceiverMode: Boolean = false
    private var receiverProgressJob: Job? = null
    private var receiverPlaybackMonitorJob: Job? = null
    private var receiverStreamUrl: String? = null
    private var broadcasterTargetPosition: Long? = null // ADD THIS: Store broadcaster's position for catch-up after buffering
    private var isBuffering: Boolean = false // ADD THIS: Track buffering state
    
    companion object {
        private const val RECEIVER_TAG = "ConnectViewModel" // Use ConnectViewModel tag for receiver mode
    }
    
    /**
     * Connect to broadcast stream
     */

    private var hasSetMediaItem = false

    fun connectToBroadcast(streamUrl: String) {
        Log.d(RECEIVER_TAG, ">>> [MusicViewModel.connectToBroadcast] Entry: streamUrl=$streamUrl")
        viewModelScope.launch {
            try {
                Log.d(RECEIVER_TAG, "[connectToBroadcast] Starting connection to: $streamUrl")
                val oldReceiverMode = isReceiverMode
                isReceiverMode = true
                receiverStreamUrl = streamUrl // ADD THIS: Store URL in class variable
                Log.d(RECEIVER_TAG, "[connectToBroadcast] Receiver mode enabled: $oldReceiverMode -> $isReceiverMode, streamUrl stored")

                // Create MediaItem with live streaming configuration
                val mediaItem = MediaItem.Builder()
                    .setUri(streamUrl)
                    .setLiveConfiguration(
                        androidx.media3.common.MediaItem.LiveConfiguration.Builder()
                            .setMaxPlaybackSpeed(1.0f)
                            .setMinPlaybackSpeed(1.0f)
                            .build()
                    )
                    .build()

                Log.d(RECEIVER_TAG, "[connectToBroadcast] MediaItem created from URI: $streamUrl")

                player?.apply {
                    if (!hasSetMediaItem) {
                        Log.d(RECEIVER_TAG, "[connectToBroadcast] Setting media item for first time")
                        setMediaItem(mediaItem)
                        hasSetMediaItem = true
                        Log.d(RECEIVER_TAG, "[connectToBroadcast] MediaItem set successfully")
                    } else {
                        Log.d(RECEIVER_TAG, "[connectToBroadcast] MediaItem already set, skipping")
                        // Update the URI if stream URL changed (for new songs)
                        setMediaItem(mediaItem)
                    }

                    // Configure for live streaming - disable end-of-stream behavior
                    repeatMode = androidx.media3.common.Player.REPEAT_MODE_OFF

                    // Add listener to monitor playback state for receiver mode
                    var endedStateListener: androidx.media3.common.Player.Listener? = null
                    endedStateListener = object : androidx.media3.common.Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            Log.d(RECEIVER_TAG, "[Receiver Player] Playback state changed: $playbackState (IDLE=1, BUFFERING=2, READY=3, ENDED=4)")
                            when (playbackState) {
                                androidx.media3.common.Player.STATE_BUFFERING -> {
                                    Log.w(RECEIVER_TAG, "[Receiver Player] ⏳ Buffering...")
                                }
                                androidx.media3.common.Player.STATE_READY -> {
                                    Log.d(RECEIVER_TAG, "[Receiver Player] ✅ Ready - isPlaying=${isPlaying}, playWhenReady=$playWhenReady")
                                    
                                    // MODIFY THIS: Catch up to broadcaster position after buffering/reconnection
                                    // Use actual broadcaster position (not predicted) to avoid overshooting
                                    if (isBuffering && broadcasterTargetPosition != null) {
                                        val broadcasterActualPos = broadcasterTargetPosition!!
                                        val currentPos = player?.currentPosition ?: 0L
                                        
                                        // Calculate how much time passed during buffering (approximate)
                                        // Add a small buffer (200ms) to catch up smoothly, but don't overshoot
                                        val catchUpPosition = broadcasterActualPos + 200 // Small buffer to catch up
                                        val drift = catchUpPosition - currentPos
                                        
                                        Log.w(RECEIVER_TAG, "[Receiver Player] 📍 Catching up after buffering: current=${currentPos}ms, broadcasterActual=${broadcasterActualPos}ms, catchUpTarget=${catchUpPosition}ms, drift=${drift}ms")
                                        
                                        // Only seek if we're significantly behind (don't seek if we're ahead or close)
                                        if (drift > 300) { // Only seek if behind by more than 300ms
                                            viewModelScope.launch {
                                                delay(50) // Small delay to ensure player is ready
                                                player?.seekTo(catchUpPosition.coerceAtLeast(0L))
                                                Log.d(RECEIVER_TAG, "[Receiver Player] ✅ Caught up: seeked to ${catchUpPosition}ms (actual broadcaster was at ${broadcasterActualPos}ms)")
                                            }
                                        } else if (drift < -200) {
                                            // We're ahead - log but don't seek back (let it catch up naturally or wait for sync)
                                            Log.w(RECEIVER_TAG, "[Receiver Player] ⚠️ Receiver is ahead by ${-drift}ms, will sync naturally")
                                        } else {
                                            Log.d(RECEIVER_TAG, "[Receiver Player] ✅ Position is close (drift=${drift}ms), no seek needed")
                                        }
                                        
                                        isBuffering = false
                                        broadcasterTargetPosition = null // Clear after use
                                    }
                                }
                                androidx.media3.common.Player.STATE_ENDED -> {
                                    Log.w(RECEIVER_TAG, "[Receiver Player] ⚠️ Playback ended")
                                    
                                    // MODIFY THIS: Only reconnect if we're not at the end of the song
                                    // Check if we're near the end (within last 5 seconds) - if so, wait for next song
                                    val duration = player?.duration ?: 0L
                                    val currentPos = player?.currentPosition ?: 0L
                                    val isNearEnd = duration > 0 && currentPos >= (duration - 5000) // Within last 5 seconds
                                    
                                    if (isReceiverMode && playWhenReady) {
                                        if (isNearEnd) {
                                            Log.d(RECEIVER_TAG, "[Receiver Player] Near end of song (pos=$currentPos, duration=$duration), waiting for next song...")
                                            // Don't reconnect - wait for next song from broadcaster
                                        } else {
                                            Log.w(RECEIVER_TAG, "[Receiver Player] Playback ended unexpectedly, reconnecting stream...")
                                            // Remove this listener temporarily to prevent loop
                                            player?.removeListener(this)
                                            
                                            // Use the reconnectReceiverStream function for consistent reconnection
                                            reconnectReceiverStream()
                                            
                                            // Re-add listener after a delay to allow reconnection to complete
                                            viewModelScope.launch {
                                                delay(500)
                                                player?.addListener(endedStateListener!!)
                                                Log.d(RECEIVER_TAG, "[Receiver Player] Listener re-added after reconnection")
                                            }
                                        }
                                    }
                                }
                                androidx.media3.common.Player.STATE_IDLE -> {
                                    Log.w(RECEIVER_TAG, "[Receiver Player] ⚠️ Player is IDLE - attempting recovery")
                                    if (isReceiverMode && playWhenReady) {
                                        viewModelScope.launch {
                                            delay(100)
                                            Log.w(RECEIVER_TAG, "[Receiver Player] Preparing player from IDLE state...")
                                            player?.prepare()
                                            delay(200)
                                            player?.playWhenReady = true
                                            player?.play()
                                            Log.d(RECEIVER_TAG, "[Receiver Player] Recovery attempt complete")
                                        }
                                    }
                                }
                            }
                        }

                        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                            Log.e(RECEIVER_TAG, "!!! [Receiver Player] ERROR: ${error.message}", error)
                            // Try to recover by preparing again
                            if (isReceiverMode) {
                                Log.w(RECEIVER_TAG, "[Receiver Player] Attempting recovery from error...")
                                player?.prepare()
                                player?.playWhenReady = true
                            }
                        }

                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            Log.d(RECEIVER_TAG, "[Receiver Player] isPlaying changed: $isPlaying")
                        }
                    }
                    player?.addListener(endedStateListener)

                    Log.d(RECEIVER_TAG, "[connectToBroadcast] Preparing ExoPlayer...")
                    prepare()
                    Log.d(RECEIVER_TAG, "[connectToBroadcast] ExoPlayer prepared, setting playWhenReady=true")
                    playWhenReady = true
                    play() // Also call play() to be sure
                    Log.d(RECEIVER_TAG, "[connectToBroadcast] ExoPlayer started, isPlaying=${isPlaying}, playWhenReady=$playWhenReady")
                } ?: run {
                    Log.e(RECEIVER_TAG, "!!! [connectToBroadcast] Error: player is null!")
                    return@launch
                }

                // Inside connectToBroadcast, after setting up the player:
                CurrentSongRepository.saveCurrentSong(
                    songId = null, // Receiver mode doesn't have a local song ID
                    position = 0L,
                    isPlaying = true,
                    isReceiverMode = true,
                    receiverStreamUrl = streamUrl
                )

                // Hide notification when entering receiver mode
                musicService?.hideNotification()

                Log.d(RECEIVER_TAG, "[connectToBroadcast] Starting progress updates...")
                startReceiverProgressUpdates()
                Log.d(RECEIVER_TAG, "[connectToBroadcast] Starting playback monitor...")
                startReceiverPlaybackMonitor()
                Log.i(RECEIVER_TAG, "<<< [MusicViewModel.connectToBroadcast] Success: CONNECTED & SYNC STARTED!")
            } catch (e: Exception) {
                Log.e(RECEIVER_TAG, "!!! [MusicViewModel.connectToBroadcast] Error connecting to broadcast", e)
                throw e
            }
        }
    }

    
    /**
     * Disconnect from broadcast
     */
    fun disconnectFromBroadcast() {
        Log.d(RECEIVER_TAG, ">>> [MusicViewModel.disconnectFromBroadcast] Entry")
        val oldReceiverMode = isReceiverMode
        isReceiverMode = false
        receiverStreamUrl = null // ADD THIS: Clear URL when disconnecting
        Log.d(RECEIVER_TAG, "[disconnectFromBroadcast] Receiver mode disabled: $oldReceiverMode -> $isReceiverMode, streamUrl cleared")


        receiverPlaybackMonitorJob?.cancel()
        receiverPlaybackMonitorJob = null
        Log.d(RECEIVER_TAG, "[disconnectFromBroadcast] Playback monitor cancelled")

        receiverProgressJob?.cancel()
        receiverProgressJob = null
        Log.d(RECEIVER_TAG, "[disconnectFromBroadcast] Progress update job cancelled and cleared")

        hasSetMediaItem = false
        Log.d(RECEIVER_TAG, "[disconnectFromBroadcast] hasSetMediaItem reset to false")

        player?.apply {
            val wasPlaying = isPlaying
            stop()
            clearMediaItems()
            Log.d(RECEIVER_TAG, "[disconnectFromBroadcast] ExoPlayer stopped and cleared (wasPlaying=$wasPlaying)")
        } ?: Log.d(RECEIVER_TAG, "[disconnectFromBroadcast] Player was null")

        // Inside disconnectFromBroadcast, at the end:
        CurrentSongRepository.saveCurrentSong(
            songId = null,
            position = 0L,
            isPlaying = false,
            isReceiverMode = false
        )

        // Show notification again if there's a local song playing
        _uiState.value.current?.let { song ->
            musicService?.showNotificationForViewModel(song, _uiState.value.isPlaying)
        }

        Log.i(RECEIVER_TAG, "<<< [MusicViewModel.disconnectFromBroadcast] Success: Disconnected from broadcast")
    }
    
    /**
     * Seek receiver player - for live streams, we need to reconnect with Range request
     */
    fun seekToReceiver(positionMs: Long) {
        val targetPosition = positionMs.coerceAtLeast(0L)
        val currentPos = player?.currentPosition ?: 0L
        Log.d(RECEIVER_TAG, "[seekToReceiver] Seeking: ${currentPos}ms -> ${targetPosition}ms")
        player?.seekTo(targetPosition)
        Log.d(RECEIVER_TAG, "[seekToReceiver] Seek command sent to ExoPlayer")
    }
    
    /**
     * Play receiver
     */
    fun playReceiver() {
        val p = player ?: run {
            Log.e(RECEIVER_TAG, "!!! [playReceiver] Player is null!")
            return
        }

        val playbackState = p.playbackState
        val isReady = playbackState == androidx.media3.common.Player.STATE_READY ||
                playbackState == androidx.media3.common.Player.STATE_BUFFERING
        val wasPlaying = p.isPlaying

        Log.d(RECEIVER_TAG, "[playReceiver] State: playbackState=$playbackState, isReady=$isReady, wasPlaying=$wasPlaying")

        // ADD THIS: Check if player is in ENDED state and reconnect if needed
        if (playbackState == androidx.media3.common.Player.STATE_ENDED) {
            Log.w(RECEIVER_TAG, "[playReceiver] Player is in ENDED state, reconnecting stream...")
            reconnectReceiverStream()
            return
        }

        if (!isReady && playbackState == androidx.media3.common.Player.STATE_IDLE) {
            Log.w(RECEIVER_TAG, "[playReceiver] Player is IDLE, preparing...")
            p.prepare()
        }

        // Use playWhenReady for more reliable playback, especially for streams
        p.playWhenReady = true

        // Also call play() to ensure it starts
        p.play()

        // Check if actually playing after a short delay
        Log.d(RECEIVER_TAG, "[playReceiver] Play command sent, playWhenReady=${p.playWhenReady}, isPlaying=${p.isPlaying}, playbackState=$playbackState")
    }
    
    /**
     * Pause receiver
     */
    fun pauseReceiver() {
        val p = player ?: run {
            Log.e(RECEIVER_TAG, "[pauseReceiver] Player is null!")
            return
        }
        val wasPlaying = p.isPlaying
        Log.d(RECEIVER_TAG, "[pauseReceiver] Pausing receiver (wasPlaying=$wasPlaying)")

        p.playWhenReady = false  // Set this first
        p.pause()  // Then pause

        Log.d(RECEIVER_TAG, "[pauseReceiver] Pause command sent, nowPlaying=${p.isPlaying}, playWhenReady=${p.playWhenReady}")
    }
    
    /**
     * Check if receiver is playing
     */
    fun isReceiverPlaying(): Boolean {
        val isPlaying = player?.isPlaying ?: false
        Log.d(RECEIVER_TAG, "[isReceiverPlaying] Result: $isPlaying")
        return isPlaying
    }
    
    /**
     * Get receiver current position
     */
    fun getReceiverPosition(): Long {
        val position = player?.currentPosition ?: 0L
        Log.d(RECEIVER_TAG, "[getReceiverPosition] Position: ${position}ms")
        return position
    }
    
    /**
     * Get receiver duration
     */
    fun getReceiverDuration(): Long {
        val duration = player?.duration?.coerceAtLeast(0L) ?: 0L
        Log.d(RECEIVER_TAG, "[getReceiverDuration] Duration: ${duration}ms")
        return duration
    }
    
    /**
     * Check if in receiver mode
     */
    fun isInReceiverMode(): Boolean {
        val result = isReceiverMode
        Log.d(RECEIVER_TAG, "[isInReceiverMode] Result: $result")
        return result
    }


    fun getReceiverPlayerState(): Int {
        return player?.playbackState ?: androidx.media3.common.Player.STATE_IDLE
    }
    
    /**
     * Start progress updates for receiver mode
     */

    private fun startReceiverProgressUpdates() {
        Log.d(RECEIVER_TAG, ">>> [startReceiverProgressUpdates] Entry")
        receiverProgressJob?.cancel()
        receiverProgressJob = viewModelScope.launch {
            Log.d(RECEIVER_TAG, "[startReceiverProgressUpdates] Progress update loop started")
            var updateCount = 0
            while (isReceiverMode && player != null) {   // ← FIXED: "layer" → "player"
                delay(100) // 100 ms = buttery smooth seek bar

                val p = player ?: break
                val currentPos = p.currentPosition
                val duration = p.duration.coerceAtLeast(0L)

                if (duration > 0L) {
                    updateCount++
                    // Log every 50 updates (5 seconds) to avoid spam
                    if (updateCount % 50 == 0) {
                        Log.d(RECEIVER_TAG, "[startReceiverProgressUpdates] Position update #$updateCount: ${currentPos}ms / ${duration}ms, isPlaying=${p.isPlaying}")
                    }
                    _uiState.value = _uiState.value.copy(
                        currentPosition = currentPos,
                        duration = duration,
                        isPlaying = p.isPlaying
                    )
                }
            }
            Log.d(RECEIVER_TAG, "<<< [startReceiverProgressUpdates] Progress update loop ended: isReceiverMode=$isReceiverMode")
        }
        Log.d(RECEIVER_TAG, "<<< [startReceiverProgressUpdates] Success: Progress job started")
    }





    // Add this function after startReceiverProgressUpdates()
    private fun startReceiverPlaybackMonitor() {
        Log.d(RECEIVER_TAG, ">>> [startReceiverPlaybackMonitor] Entry")
        receiverPlaybackMonitorJob?.cancel()
        receiverPlaybackMonitorJob = viewModelScope.launch {
            Log.d(RECEIVER_TAG, "[startReceiverPlaybackMonitor] Monitor loop started")
            var consecutiveStoppedChecks = 0
            while (isReceiverMode && player != null) {
                delay(500) // Check every 500ms

                val p = player ?: break
                val playbackState = p.playbackState
                val shouldBePlaying = p.playWhenReady
                val isActuallyPlaying = p.isPlaying

                // Detect if player should be playing but isn't
                if (shouldBePlaying && !isActuallyPlaying) {
                    consecutiveStoppedChecks++
                    Log.w(RECEIVER_TAG, "[startReceiverPlaybackMonitor] ⚠️ Player should be playing but isn't! (check #$consecutiveStoppedChecks, state=$playbackState)")

                    // If stopped for more than 1 second (2 checks), recover
                    if (consecutiveStoppedChecks >= 2) {
                        Log.e(RECEIVER_TAG, "!!! [startReceiverPlaybackMonitor] Playback stopped unexpectedly! Recovering...")

                        when (playbackState) {
                            androidx.media3.common.Player.STATE_IDLE -> {
                                Log.w(RECEIVER_TAG, "[startReceiverPlaybackMonitor] Player is IDLE, preparing...")
                                p.prepare()
                                delay(200)
                                p.playWhenReady = true
                                p.play()
                            }
                            androidx.media3.common.Player.STATE_ENDED -> {
                                Log.w(RECEIVER_TAG, "[startReceiverPlaybackMonitor] Player ended, reconnecting stream...")
                                // Reconnect stream (similar to STATE_ENDED handler)
                                reconnectReceiverStream()
                            }
                            androidx.media3.common.Player.STATE_BUFFERING -> {
                                Log.w(RECEIVER_TAG, "[startReceiverPlaybackMonitor] Stuck in buffering, trying to recover...")
                                // Try to recover from buffering
                                p.playWhenReady = true
                                p.play()
                            }
                            else -> {
                                Log.w(RECEIVER_TAG, "[startReceiverPlaybackMonitor] Unknown state, forcing play...")
                                p.playWhenReady = true
                                p.play()
                            }
                        }
                        consecutiveStoppedChecks = 0
                    }
                } else if (isActuallyPlaying) {
                    // Reset counter if playing normally
                    if (consecutiveStoppedChecks > 0) {
                        Log.d(RECEIVER_TAG, "[startReceiverPlaybackMonitor] ✅ Playback recovered!")
                        consecutiveStoppedChecks = 0
                    }
                }
            }
            Log.d(RECEIVER_TAG, "<<< [startReceiverPlaybackMonitor] Monitor loop ended")
        }
        Log.d(RECEIVER_TAG, "<<< [startReceiverPlaybackMonitor] Success: Monitor job started")
    }

    // Add this helper function to reconnect stream
    private fun reconnectReceiverStream() {
        // MODIFY THIS: Use class variable first, fallback to repository
        val streamUrl = receiverStreamUrl ?: CurrentSongRepository.getReceiverStreamUrl()
        if (streamUrl == null) {
            Log.e(RECEIVER_TAG, "!!! [reconnectReceiverStream] No stream URL available!")
            return
        }

        viewModelScope.launch {
            try {
                Log.w(RECEIVER_TAG, "[reconnectReceiverStream] Reconnecting to: $streamUrl")
                val p = player ?: return@launch

                // MODIFY THIS: Use broadcaster's actual position with small buffer (200ms) to catch up
                // Don't use predicted position to avoid overshooting
                val broadcasterActualPos = broadcasterTargetPosition ?: p.currentPosition.coerceAtLeast(0L)
                val catchUpPosition = broadcasterActualPos + 200 // Small buffer to catch up smoothly
                Log.d(RECEIVER_TAG, "[reconnectReceiverStream] Reconnect position: actual=${broadcasterActualPos}ms, catchUp=${catchUpPosition}ms, current=${p.currentPosition}ms")

                // Stop and clear
                p.stop()
                p.clearMediaItems()
                isBuffering = true // Mark as buffering

                // Create new MediaItem
                val newMediaItem = MediaItem.Builder()
                    .setUri(streamUrl)
                    .setLiveConfiguration(
                        androidx.media3.common.MediaItem.LiveConfiguration.Builder()
                            .setMaxPlaybackSpeed(1.0f)
                            .setMinPlaybackSpeed(1.0f)
                            .build()
                    )
                    .build()

                p.setMediaItem(newMediaItem)
                p.prepare()

                // Wait for prepare and seek to broadcaster's actual position with small buffer
                delay(200)
                p.seekTo(catchUpPosition.coerceAtLeast(0L))
                p.playWhenReady = true
                p.play()

                Log.d(RECEIVER_TAG, "[reconnectReceiverStream] ✅ Stream reconnected at position ${catchUpPosition}ms (broadcaster actual: ${broadcasterActualPos}ms)")
            } catch (e: Exception) {
                Log.e(RECEIVER_TAG, "!!! [reconnectReceiverStream] Error reconnecting", e)
                isBuffering = false
            }
        }
    }

    /**
     * Update broadcaster target position (called from ConnectViewModel)
     * Stores the broadcaster's actual reported position for catch-up after buffering
     */
    fun updateBroadcasterTargetPosition(positionMs: Long) {
        if (isReceiverMode) {
            broadcasterTargetPosition = positionMs
            Log.d(RECEIVER_TAG, "[updateBroadcasterTargetPosition] Updated target: ${positionMs}ms")
        }
    }

    override fun onCleared() {
        super.onCleared()

        progressUpdateJob?.cancel()
        receiverProgressJob?.cancel()
        receiverPlaybackMonitorJob?.cancel()
        reloadJob?.cancel()
        
        contentObserver?.let {
            contentResolver?.unregisterContentObserver(it)
        }
        
        playerListener?.let { player?.removeListener(it) }
        player?.release()
        player = null

        // No need to release twice
        applicationContext = null
        contentResolver = null
    }

}


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
import com.example.musicplayercursor.model.Song
import com.example.musicplayercursor.model.Playlist
import com.example.musicplayercursor.repository.CurrentSongRepository
import com.example.musicplayercursor.repository.FavouritesRepository
import com.example.musicplayercursor.repository.MusicRepository
import com.example.musicplayercursor.repository.PlayCountRepository
import com.example.musicplayercursor.repository.LastPlayedRepository
import com.example.musicplayercursor.repository.PlaylistRepository
import com.example.musicplayercursor.repository.QueueRepository
import com.example.musicplayercursor.repository.SongRepository
import com.example.musicplayercursor.service.MusicService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
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

    private var favouritesRepository: FavouritesRepository? = null
    private var playlistRepository: PlaylistRepository? = null
    private var contentObserver: ContentObserver? = null
    private var contentResolver: android.content.ContentResolver? = null

    private var applicationContext: Context? = null
    private var musicService: MusicService? = null
    private var serviceStateObserverJob: Job? = null
    
    // Command Queue to handle actions when service is not yet bound
    private sealed interface ServiceCommand {
        class Play(val song: Song, val queue: List<Long>, val source: String?, val startPosition: Long = 0L) : ServiceCommand
        object TogglePlayPause : ServiceCommand
        class SeekTo(val position: Long) : ServiceCommand
        class SetLooping(val isLooping: Boolean) : ServiceCommand
    }
    
    private val commandQueue = mutableListOf<ServiceCommand>()
    
//    init {
//        LastPlayedRepository.init(android.app.Application()) // Static initialization
//        PlayCountRepository.init(android.app.Application()) // Static initialization
//    }
    
    private val _uiState = MutableStateFlow(MusicUiState())
    val uiState: StateFlow<MusicUiState> = _uiState
    
    // Handler for ContentObserver (runs on main thread)
    private val handler = Handler(Looper.getMainLooper())
    
    // Debounce mechanism to avoid multiple rapid reloads
    private var reloadJob: Job? = null
    
    fun setMusicService(service: MusicService?) {
        musicService = service
        Log.d("FirstPlay", "[setMusicService] Service set: ${service != null}")

        if (service != null) {
            // Execute queued commands
            synchronized(commandQueue) {
                Log.d("FirstPlay", "[setMusicService] Checking queue. Size: ${commandQueue.size}")
                if (commandQueue.isNotEmpty()) {
                    Log.d("FirstPlay", "[setMusicService] Executing ${commandQueue.size} queued commands")
                    commandQueue.forEach { command ->
                        when (command) {
                            is ServiceCommand.Play -> {
                                Log.d("FirstPlay", "[setMusicService] Executing queued PLAY command for ${command.song.title}. Queue size: ${command.queue.size}, StartPos: ${command.startPosition}")
                                service.play(command.song, command.queue, command.source, command.startPosition)
                            }
                            is ServiceCommand.TogglePlayPause -> {
                                Log.d("FirstPlay", "[setMusicService] Executing queued TogglePlayPause")
                                service.togglePlayPause()
                            }
                            is ServiceCommand.SeekTo -> service.seekTo(command.position)
                            is ServiceCommand.SetLooping -> service.setLooping(command.isLooping)
                        }
                    }
                    commandQueue.clear()
                }
            }
        }

        // Observe MusicService playback state
        serviceStateObserverJob?.cancel()
        if (service != null) {
            serviceStateObserverJob = service.playbackState
                .onEach { playbackState ->
                    // Sync MusicService state to UI state
                    // IMPORTANT: Preserve UI-specific properties (like isFavourite, isLooping) from current state
                    val currentState = _uiState.value
                    val serviceSong = playbackState.currentSong
                    
                    // If we have a current song and the service song matches, preserve UI properties
                    val updatedCurrentSong = if (serviceSong != null && currentState.current?.id == serviceSong.id) {
                        // Preserve favourite status and other UI properties from current state
                        currentState.current!!.copy(
                            // Update any properties that might have changed, but preserve isFavourite
                            title = serviceSong.title,
                            artist = serviceSong.artist,
                            durationMs = serviceSong.durationMs,
                            contentUri = serviceSong.contentUri,
                            // Preserve UI-specific properties
                            isFavourite = currentState.current!!.isFavourite,
                            playCount = currentState.current!!.playCount,
                            lastPlayed = currentState.current!!.lastPlayed
                        )
                    } else {
                        // New song - find it in songs list to get UI properties, or use service song
                        serviceSong?.let { song ->
                            currentState.songs.find { it.id == song.id } ?: song
                        }
                    }
                    
                    // Sync isLooping from service (service is source of truth for playback state)
                    // Since we update the service when user toggles, this will reflect the correct state
                    _uiState.value = currentState.copy(
                        current = updatedCurrentSong,
                        isPlaying = playbackState.isPlaying,
                        currentPosition = playbackState.currentPosition,
                        duration = playbackState.duration,
                        isLooping = playbackState.isLooping  // Sync from service
                    )
                }
                .launchIn(viewModelScope)
        }
    }
    fun loadSongs(context: Context) {
        Log.d("FirstPlay", "loadSongs called")
        // Store content resolver reference for ContentObserver
        if (contentResolver == null) {
            applicationContext = context.applicationContext
            contentResolver = applicationContext?.contentResolver
            registerMediaStoreObserver()
        }

        // Initialize repositories
        val appContext = context
        LastPlayedRepository.init(appContext)
        PlayCountRepository.init(appContext)
        CurrentSongRepository.init(appContext)
        SongRepository.init(appContext)
        
        viewModelScope.launch {
            Log.d("FirstPlay", "loadSongs: Starting async loading")
            favouritesRepository = FavouritesRepository(appContext)
            playlistRepository = PlaylistRepository(appContext)
            
            val loadedSongs = SongRepository.getAllSongs()
            
            Log.d("FirstPlay", "loadSongs: Loaded ${loadedSongs.size} songs")
            val favouriteIds = favouritesRepository?.getFavouriteSongIds() ?: emptySet()
            val playCounts = PlayCountRepository.getAllPlayCounts()
            val lastPlayedMap = LastPlayedRepository.getAllLastPlayed()
            val playlists = playlistRepository?.getAllPlaylists() ?: emptyList()

            // Restore current song state from persistence
            val savedSongId = CurrentSongRepository.getCurrentSongId()
            val savedPosition = CurrentSongRepository.getCurrentPosition()
            val savedIsPlaying = CurrentSongRepository.getIsPlaying()
            val savedIsReceiverMode = CurrentSongRepository.getIsReceiverMode()
            val savedReceiverStreamUrl = CurrentSongRepository.getReceiverStreamUrl()
            
            Log.d("FirstPlay", "loadSongs: Saved state - songId=$savedSongId, pos=$savedPosition, playing=$savedIsPlaying, receiver=$savedIsReceiverMode")
            
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
            
            Log.d("FirstPlay", "loadSongs: Restored song: ${restoredCurrentSong?.title}")

            _uiState.value = _uiState.value.copy(
                songs = songsWithFavouritesAndCounts,
                current = restoredCurrentSong,
                currentPosition = savedPosition,
                isPlaying = savedIsPlaying,
                playlists = playlists
            )

            // Restore playback if there's a restored song
            if (restoredCurrentSong != null && !savedIsReceiverMode) {
                // Ensure queue is populated if empty, so we can play/navigate
                val currentQueue = QueueRepository.queue.value
                val queueToRestore = if (currentQueue.isEmpty()) {
                     val allSongsQueue = songsWithFavouritesAndCounts.map { it.id }
                     Log.d("FirstPlay", "loadSongs: Populated queue with ${allSongsQueue.size} songs")
                     allSongsQueue
                } else {
                    currentQueue
                }

//                if (musicService != null) {
//                    Log.d("FirstPlay", "loadSongs: Service ready, restoring immediately")
//                    // Restore song in service immediately
//                    musicService?.play(restoredCurrentSong, queueToRestore, "all", savedPosition)
//                    if (!savedIsPlaying) {
//                        musicService?.togglePlayPause()
//                    }
//                } else {
//                    // Queue commands for when service connects
//                    Log.d("FirstPlay", "[loadSongs] Service not ready, queuing restore commands for: ${restoredCurrentSong.title}")
//                    synchronized(commandQueue) {
//                        commandQueue.add(ServiceCommand.Play(restoredCurrentSong, queueToRestore, "all", savedPosition))
//                        if (!savedIsPlaying) {
//                            commandQueue.add(ServiceCommand.TogglePlayPause)
//                        }
//                        Log.d("FirstPlay", "loadSongs: Queued commands. Queue size: ${commandQueue.size}")
//                    }
//                }
            }
        }
    }

    
    fun loadPlaylists(context: Context) {
        viewModelScope.launch {
            if (playlistRepository == null) {
                playlistRepository = PlaylistRepository(context.applicationContext)
            }
            val playlists = playlistRepository?.getAllPlaylists() ?: emptyList()
            _uiState.value = _uiState.value.copy(playlists = playlists)
        }
    }
    
    fun createPlaylist(context: Context, name: String) {
        viewModelScope.launch {
            if (playlistRepository == null) {
                playlistRepository = PlaylistRepository(context.applicationContext)
            }
            playlistRepository?.addPlaylist(name)
            val playlists = playlistRepository?.getAllPlaylists() ?: emptyList()
            _uiState.value = _uiState.value.copy(playlists = playlists)
        }
    }
    
    fun deletePlaylist(context: Context, playlistId: String) {
        viewModelScope.launch {
            if (playlistRepository == null) {
                playlistRepository = PlaylistRepository(context.applicationContext)
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


    fun play(context: Context, song: Song) {
        Log.d("FirstPlay", "MusicViewModel: play() called for ${song.title}")
        
        // Use QueueRepository to get current queue
        val currentQueue = QueueRepository.queue.value
        // If queue is empty in Repo, populate it with all songs
        val queueToUse = if (currentQueue.isEmpty()) {
             val allIds = _uiState.value.songs.map { it.id }
             Log.d("FirstPlay", "MusicViewModel: play() Populated empty queue with ${allIds.size} songs")
             allIds
        } else {
             currentQueue
        }
        
        // Exit selection mode if active
        if (_uiState.value.isSelectionMode) {
            exitSelectionMode()
        }
        
        applicationContext = context.applicationContext
        
        // Get the favourite status and updated play count from the song list or current song
        val existingSong = _uiState.value.songs.find { it.id == song.id }
        val favouriteStatus = existingSong?.isFavourite 
            ?: _uiState.value.current?.isFavourite 
            ?: song.isFavourite
        val updatedPlayCount = (existingSong?.playCount ?: song.playCount) + 1
        val now = System.currentTimeMillis()
        
        val songWithStatus = song.copy(
            isFavourite = favouriteStatus,
            playCount = updatedPlayCount,
            lastPlayed = now
        )
        
        // Update the song in the list with new play count
        val updatedSongs = _uiState.value.songs.map {
            if (it.id == song.id) it.copy(playCount = updatedPlayCount, lastPlayed = now) else it
        }
        
        _uiState.value = _uiState.value.copy(
            songs = updatedSongs,
            current = songWithStatus
        )
        
        // Delegate to MusicService or queue if not ready
        if (musicService != null) {
            musicService?.play(songWithStatus, queueToUse, "all")
        } else {
            Log.d(TAG, "[play] Service not ready, queuing play command for: ${song.title}")
            synchronized(commandQueue) {
                commandQueue.add(ServiceCommand.Play(songWithStatus, queueToUse, "all"))
            }
        }
    }

    fun playFromQueue(
        context: Context,
        queueSongIds: List<Long>?,
        startSongId: Long,
        source: String?
    ) {
        // Set queue in repository if provided
        if (queueSongIds != null) {
            QueueRepository.setQueue(queueSongIds)
        }
        // Resolve Song from current songs list
        val song = _uiState.value.songs.find { it.id == startSongId } ?: return
        play(context, song)
    }

    fun togglePlayPause() {
        if (musicService != null) {
            musicService?.togglePlayPause()
        } else {
            Log.d(TAG, "[togglePlayPause] Service not ready, queuing toggle command")
            synchronized(commandQueue) {
                commandQueue.add(ServiceCommand.TogglePlayPause)
            }
        }
    }

    fun playNextSong(context: Context) {
        if (musicService != null) {
            musicService?.playNext()
        } else {
            Log.d(TAG, "[playNextSong] Service not ready")
        }
    }

    fun playPreviousSong(context: Context) {
        if (musicService != null) {
            musicService?.playPrevious()
        } else {
            Log.d(TAG, "[playPreviousSong] Service not ready")
        }
    }

    fun seekTo(positionMs: Long) {
        if (musicService != null) {
            musicService?.seekTo(positionMs)
        } else {
            Log.d(TAG, "[seekTo] Service not ready, queuing seek command: $positionMs")
            synchronized(commandQueue) {
                commandQueue.add(ServiceCommand.SeekTo(positionMs))
            }
        }
    }

    fun toggleLoop() {
        val newLoopingState = !_uiState.value.isLooping
        Log.d(TAG, "[toggleLoop] Toggling loop: ${_uiState.value.isLooping} -> $newLoopingState")
        
        // Update UI state
        _uiState.value = _uiState.value.copy(isLooping = newLoopingState)
        
        // Also update MusicService to keep them in sync
        if (musicService != null) {
            musicService?.setLooping(newLoopingState)
        } else {
            Log.d(TAG, "[toggleLoop] Service not ready, queuing looping command: $newLoopingState")
            synchronized(commandQueue) {
                commandQueue.add(ServiceCommand.SetLooping(newLoopingState))
            }
        }
        
        Log.d(TAG, "[toggleLoop] State updated, new looping: ${_uiState.value.isLooping}")
    }

    fun toggleFavourite(context: Context, song: Song) {
        val repo = favouritesRepository ?: FavouritesRepository(context).also {
            favouritesRepository = it
        }
        
        // Get the CURRENT state
        val currentState = _uiState.value
        val currentSong = currentState.current
        
        // Determine the current favourite status from the state, not the parameter
        val currentFavouriteStatus = when {
            currentSong?.id == song.id -> currentSong.isFavourite
            else -> {
                // If current song doesn't match, find it in the songs list
                currentState.songs.find { it.id == song.id }?.isFavourite ?: song.isFavourite
            }
        }
        
        val newFavouriteStatus = !currentFavouriteStatus
        Log.d(TAG, "[toggleFavourite] Toggling favourite for song ${song.id}: $currentFavouriteStatus -> $newFavouriteStatus")
        
        // Update the song in the list (preserve play count)
        val updatedSongs = currentState.songs.map {
            if (it.id == song.id) it.copy(isFavourite = newFavouriteStatus) else it
        }
        
        // Update current song if it's the one being toggled (preserve play count)
        // Create a completely new Song object to ensure StateFlow detects the change
        val updatedCurrent = if (currentSong?.id == song.id) {
            currentSong.copy(
                isFavourite = newFavouriteStatus,
                // Explicitly copy all fields to ensure new object reference
                id = currentSong.id,
                title = currentSong.title,
                artist = currentSong.artist,
                durationMs = currentSong.durationMs,
                contentUri = currentSong.contentUri,
                playCount = currentSong.playCount,
                dateAdded = currentSong.dateAdded,
                lastPlayed = currentSong.lastPlayed
            )
        } else {
            currentSong
        }
        
        Log.d(TAG, "[toggleFavourite] Updated current song favourite status: ${updatedCurrent?.isFavourite}")
        
        // Update UI state immediately for instant feedback
        // Create a new state object to ensure StateFlow detects the change
        _uiState.value = MusicUiState(
            songs = updatedSongs,
            current = updatedCurrent,
            isPlaying = currentState.isPlaying,
            currentPosition = currentState.currentPosition,
            duration = currentState.duration,
            selectedSongs = currentState.selectedSongs,
            isSelectionMode = currentState.isSelectionMode,
            playlists = currentState.playlists,
            isLooping = currentState.isLooping
        )
        
        Log.d(TAG, "[toggleFavourite] State updated, new current favourite: ${_uiState.value.current?.isFavourite}")
        
        // Then persist to repository in background
        viewModelScope.launch {
            if (newFavouriteStatus) {
                repo.addFavourite(song.id)
            } else {
                repo.removeFavourite(song.id)
            }
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
                // Service will handle stopping playback
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

    override fun onCleared() {
        super.onCleared()

        serviceStateObserverJob?.cancel()
        reloadJob?.cancel()
        
        contentObserver?.let {
            contentResolver?.unregisterContentObserver(it)
        }
        
        applicationContext = null
        contentResolver = null
    }

}

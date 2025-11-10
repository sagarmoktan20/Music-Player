package com.example.musicplayercursor.viewmodel

import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.example.musicplayercursor.model.Song
import com.example.musicplayercursor.model.Playlist
import com.example.musicplayercursor.repository.FavouritesRepository
import com.example.musicplayercursor.repository.MusicRepository
import com.example.musicplayercursor.repository.PlayCountRepository
import com.example.musicplayercursor.repository.LastPlayedRepository
import com.example.musicplayercursor.repository.PlaylistRepository
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
    private var playCountRepository: PlayCountRepository? = null
    private var lastPlayedRepository: LastPlayedRepository? = null
    private var playlistRepository: PlaylistRepository? = null
    private var contentObserver: ContentObserver? = null
    private var contentResolver: android.content.ContentResolver? = null
    private var applicationContext: Context? = null

    // Playback queue context (ids of songs in the active context order)
    private var currentQueueSongIds: List<Long> = emptyList()
    private var currentQueueSource: String? = null // e.g., "all", "favourites", or playlistId
    private val _uiState = MutableStateFlow(MusicUiState())
    val uiState: StateFlow<MusicUiState> = _uiState
    
    // Handler for ContentObserver (runs on main thread)
    private val handler = Handler(Looper.getMainLooper())
    
    // Debounce mechanism to avoid multiple rapid reloads
    private var reloadJob: Job? = null

    fun loadSongs(context: Context) {
        // Store content resolver reference for ContentObserver
        if (contentResolver == null) {
            applicationContext = context.applicationContext
            contentResolver = applicationContext?.contentResolver
            registerMediaStoreObserver()
        }
        
        viewModelScope.launch {
            favouritesRepository = FavouritesRepository(context)
            playCountRepository = PlayCountRepository(context)
            lastPlayedRepository = LastPlayedRepository(context)
            playlistRepository = PlaylistRepository(context)
            val repo = MusicRepository(context.contentResolver)
            val loadedSongs = repo.loadAudio()
            val favouriteIds = favouritesRepository?.getFavouriteSongIds() ?: emptySet()
            val playCounts = playCountRepository?.getAllPlayCounts() ?: emptyMap()
            val lastPlayedMap = lastPlayedRepository?.getAllLastPlayed() ?: emptyMap()
            val playlists = playlistRepository?.getAllPlaylists() ?: emptyList()
            
            // Mark songs as favourite and add play counts based on SharedPreferences
            val songsWithFavouritesAndCounts = loadedSongs.map { song ->
                song.copy(
                    isFavourite = favouriteIds.contains(song.id),
                    playCount = playCounts[song.id] ?: 0,
                    lastPlayed = lastPlayedMap[song.id] ?: 0L
                )
            }
            
            // Update current song's favourite status and play count if it exists
            val currentSong = _uiState.value.current
            val updatedCurrent = if (currentSong != null) {
                val currentPlayCount = playCounts[currentSong.id] ?: currentSong.playCount
                val currentLastPlayed = lastPlayedMap[currentSong.id] ?: currentSong.lastPlayed
                currentSong.copy(
                    isFavourite = favouriteIds.contains(currentSong.id),
                    playCount = currentPlayCount,
                    lastPlayed = currentLastPlayed
                )
            } else null
            
            _uiState.value = _uiState.value.copy(
                songs = songsWithFavouritesAndCounts,
                current = updatedCurrent,
                playlists = playlists
            )
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
        if (player != null) return
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
    fun play(context: Context, song: Song) {
        // If no queue is set, default to "all songs" order
        if (currentQueueSongIds.isEmpty()) {
            currentQueueSongIds = _uiState.value.songs.map { it.id }
            currentQueueSource = "all"
        }
        // Exit selection mode if active
        if (_uiState.value.isSelectionMode) {
            exitSelectionMode()
        }
        
        preparePlayer(context)
        val p = player ?: return
        
        // Initialize repositories if needed
        if (playCountRepository == null) {
            playCountRepository = PlayCountRepository(context)
        }
        if (lastPlayedRepository == null) {
            lastPlayedRepository = LastPlayedRepository(context)
        }
        
        // Increment play count
        playCountRepository?.incrementPlayCount(song.id)
        // Update last played timestamp
        val now = System.currentTimeMillis()
        lastPlayedRepository?.setLastPlayed(song.id, now)
        
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
        startProgressUpdates()
    }

    fun playFromQueue(
        context: Context,
        queueSongIds: List<Long>?,
        startSongId: Long,
        source: String?
    ) {
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
        val p = player ?: return
        if (p.isPlaying) {
            p.pause()
            _uiState.value = _uiState.value.copy(isPlaying = false)
        } else {
            p.play()
            _uiState.value = _uiState.value.copy(isPlaying = true)
        }
    }

    fun playNextSong(context: Context) {
        // Disable loop when next is clicked
//        if (_uiState.value.isLooping) {
//            _uiState.value = _uiState.value.copy(isLooping = false)
//        }
        val currentSong = _uiState.value.current ?: return
        val ids = if (currentQueueSongIds.isNotEmpty()) currentQueueSongIds else _uiState.value.songs.map { it.id }
        val currentIndex = ids.indexOf(currentSong.id)
        if (currentIndex >= 0 && currentIndex < ids.size - 1) {
            val nextId = ids[currentIndex + 1]
            val nextSong = _uiState.value.songs.find { it.id == nextId }
            if (nextSong != null) {
                play(context, nextSong)
            }
        }
    }

    fun playPreviousSong(context: Context) {
        val currentSong = _uiState.value.current ?: return
        val ids = if (currentQueueSongIds.isNotEmpty()) currentQueueSongIds else _uiState.value.songs.map { it.id }
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
            if (playCountRepository == null) playCountRepository = PlayCountRepository(context)
            if (lastPlayedRepository == null) lastPlayedRepository = LastPlayedRepository(context)

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
            while (true) {
                delay(100) // Update every 100ms
                val p = player ?: break
                val currentPos = p.currentPosition
                val duration = p.duration
                if (duration > 0) {
                    _uiState.value = _uiState.value.copy(
                        currentPosition = currentPos,
                        duration = duration
                    )
                }
            }
        }
    }

    

    override fun onCleared() {
        super.onCleared()
        progressUpdateJob?.cancel()
        reloadJob?.cancel()
        
        // Unregister ContentObserver
        contentObserver?.let { observer ->
            contentResolver?.unregisterContentObserver(observer)
        }
        contentObserver = null
        contentResolver = null
        applicationContext = null
        
        playerListener?.let { l ->
            player?.removeListener(l)
        }
        playerListener = null
        player?.release()
        player = null
    }

}
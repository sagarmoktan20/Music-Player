package com.example.musicplayercursor.viewmodel

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.example.musicplayercursor.model.Song
import com.example.musicplayercursor.repository.FavouritesRepository
import com.example.musicplayercursor.repository.MusicRepository
import com.example.musicplayercursor.repository.PlayCountRepository
import com.example.musicplayercursor.repository.LastPlayedRepository
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
    val duration: Long = 0L
)
class MusicViewModel: ViewModel() {

    private var player: ExoPlayer? = null
    private var progressUpdateJob: Job? = null
    private var favouritesRepository: FavouritesRepository? = null
    private var playCountRepository: PlayCountRepository? = null
    private var lastPlayedRepository: LastPlayedRepository? = null
    private var contentObserver: ContentObserver? = null
    private var contentResolver: android.content.ContentResolver? = null
    private var applicationContext: Context? = null
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
            val repo = MusicRepository(context.contentResolver)
            val loadedSongs = repo.loadAudio()
            val favouriteIds = favouritesRepository?.getFavouriteSongIds() ?: emptySet()
            val playCounts = playCountRepository?.getAllPlayCounts() ?: emptyMap()
            val lastPlayedMap = lastPlayedRepository?.getAllLastPlayed() ?: emptyMap()
            
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
                current = updatedCurrent
            )
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
        player = ExoPlayer.Builder(context).build().apply {
            val attrs = AudioAttributes.Builder()
                .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
                .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                .build()
            setAudioAttributes(attrs, true)
        }
    }
    fun play(context: Context, song: Song) {
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
        val currentSong = _uiState.value.current ?: return
        val songs = _uiState.value.songs
        val currentIndex = songs.indexOfFirst { it.id == currentSong.id }
        if (currentIndex >= 0 && currentIndex < songs.size - 1) {
            val nextSong = songs[currentIndex + 1]
            play(context, nextSong)
        }
    }

    fun playPreviousSong(context: Context) {
        val currentSong = _uiState.value.current ?: return
        val songs = _uiState.value.songs
        val currentIndex = songs.indexOfFirst { it.id == currentSong.id }
        if (currentIndex > 0) {
            val previousSong = songs[currentIndex - 1]
            play(context, previousSong)
        }
    }

    fun seekTo(positionMs: Long) {
        val p = player ?: return
        p.seekTo(positionMs.coerceIn(0L, p.duration.coerceAtLeast(0L)))
        _uiState.value = _uiState.value.copy(currentPosition = positionMs)
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
        
        player?.release()
        player = null
    }

}
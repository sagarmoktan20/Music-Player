package com.example.musicplayercursor.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.example.musicplayercursor.model.Song
import com.example.musicplayercursor.repository.FavouritesRepository
import com.example.musicplayercursor.repository.MusicRepository
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
    private val _uiState = MutableStateFlow(MusicUiState())
    val uiState: StateFlow<MusicUiState> = _uiState

    fun loadSongs(context: Context) {
        viewModelScope.launch {
            favouritesRepository = FavouritesRepository(context)
            val repo = MusicRepository(context.contentResolver)
            val loadedSongs = repo.loadAudio()
            val favouriteIds = favouritesRepository?.getFavouriteSongIds() ?: emptySet()
            
            // Mark songs as favourite based on SharedPreferences
            val songsWithFavourites = loadedSongs.map { song ->
                song.copy(isFavourite = favouriteIds.contains(song.id))
            }
            
            // Update current song's favourite status if it exists
            val currentSong = _uiState.value.current
            val updatedCurrent = if (currentSong != null) {
                currentSong.copy(isFavourite = favouriteIds.contains(currentSong.id))
            } else null
            
            _uiState.value = _uiState.value.copy(
                songs = songsWithFavourites,
                current = updatedCurrent
            )
        }
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
        
        // Get the favourite status from the song list or current song
        val favouriteStatus = _uiState.value.songs.find { it.id == song.id }?.isFavourite 
            ?: _uiState.value.current?.isFavourite 
            ?: song.isFavourite
        
        val songWithFavouriteStatus = song.copy(isFavourite = favouriteStatus)
        
        p.stop()
        p.clearMediaItems()
        p.setMediaItem(MediaItem.fromUri(song.contentUri))
        p.prepare()
        p.play()
        _uiState.value = _uiState.value.copy(
            current = songWithFavouriteStatus,
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
            
            // Update the song in the list
            val updatedSongs = _uiState.value.songs.map {
                if (it.id == song.id) it.copy(isFavourite = newFavouriteStatus) else it
            }
            
            // Update current song if it's the one being toggled
            val updatedCurrent = if (_uiState.value.current?.id == song.id) {
                song.copy(isFavourite = newFavouriteStatus)
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
        player?.release()
        player = null
    }

}
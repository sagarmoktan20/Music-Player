package com.example.musicplayercursor.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.example.musicplayercursor.model.Song
import com.example.musicplayercursor.repository.MusicRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch


data class MusicUiState(
    val songs: List<Song> = emptyList(),
    val current: Song? = null,
    val isPlaying: Boolean = false
)
class MusicViewModel: ViewModel() {


    private var player: ExoPlayer? = null
    private val _uiState = MutableStateFlow(MusicUiState())
    val uiState: StateFlow<MusicUiState> = _uiState

    fun loadSongs(context: Context) {
        viewModelScope.launch {
            val repo = MusicRepository(context.contentResolver)
            val songs = repo.loadAudio()
            _uiState.value = _uiState.value.copy(songs = songs)
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
        p.stop()
        p.clearMediaItems()
        p.setMediaItem(MediaItem.fromUri(song.contentUri))
        p.prepare()
        p.play()
        _uiState.value = _uiState.value.copy(current = song, isPlaying = true)
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

    override fun onCleared() {
        super.onCleared()
        player?.release()
        player = null
    }

}
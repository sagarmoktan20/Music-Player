package com.example.musicplayercursor.repository

import com.example.musicplayercursor.service.PlaybackState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object PlaybackRepository {
    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()
    fun set(state: PlaybackState) { _state.value = state }
}


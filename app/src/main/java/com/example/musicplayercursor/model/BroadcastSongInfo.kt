package com.example.musicplayercursor.model

import kotlinx.serialization.Serializable


@Serializable
data class BroadcastSongInfo(
    val songId: Long,
    val positionMs: Long,
    val isPlaying: Boolean,
    val durationMs: Long,
    val title: String,
    val artist: String,
    val serverTimestamp: Long  // ADD THIS
)


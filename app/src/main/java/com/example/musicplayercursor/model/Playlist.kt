package com.example.musicplayercursor.model

data class Playlist(
    val id: String,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),

    val songIds: Set<Long> = emptySet()
)


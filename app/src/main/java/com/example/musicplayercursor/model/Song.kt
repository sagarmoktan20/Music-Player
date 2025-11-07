package com.example.musicplayercursor.model

import android.net.Uri

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val contentUri: Uri,
    val isFavourite: Boolean = false,
    val playCount: Int = 0,
    val dateAdded: Long,
    val lastPlayed: Long = 0L
)

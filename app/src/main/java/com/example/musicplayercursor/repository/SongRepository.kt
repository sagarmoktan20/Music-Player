package com.example.musicplayercursor.repository

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.example.musicplayercursor.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object SongRepository {
    private var songs: List<Song> = emptyList()
    private val _songsFlow = MutableStateFlow<List<Song>>(emptyList())
    val songsFlow: StateFlow<List<Song>> = _songsFlow.asStateFlow()

    private var initialized = false

    init {
        android.util.Log.d("FirstPlay", "SongRepository object initialized")
    }

    fun init(context: Context) {
        if (!initialized) {
            loadSongs(context)
            initialized = true
        }
    }

    fun loadSongs(context: Context) {
        android.util.Log.d("FirstPlay", "SongRepository: loadSongs executing...")
        
        // Check permissions
//        val permission = if (android.os.Build.VERSION.SDK_INT >= 33)
//            android.Manifest.permission.READ_MEDIA_AUDIO
//        else
//            android.Manifest.permission.READ_EXTERNAL_STORAGE
//
//        val hasPermission = context.checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
//        android.util.Log.d("FirstPlay", "SongRepository: API Level ${android.os.Build.VERSION.SDK_INT}, Permission $permission granted? $hasPermission")

        val collection: Uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATE_ADDED
        )
        val selection = "${MediaStore.Audio.Media.DURATION} > 0"
        val sortOrder = "${MediaStore.Audio.Media.DATE_ADDED} DESC"
        val results = mutableListOf<Song>()

        try {
            context.contentResolver.query(collection, projection, selection, null, sortOrder)?.use { cursor ->
                android.util.Log.d("FirstPlay", "SongRepository: Cursor count: ${cursor.count}")
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val dateAddedCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val title = cursor.getString(titleCol) ?: "Unknown Title"
                    val artist = cursor.getString(artistCol) ?: "Unknown Artist"
                    val duration = cursor.getLong(durationCol)
                    val dateAdded = cursor.getLong(dateAddedCol)
                    val contentUri = ContentUris.withAppendedId(collection, id)
                    results.add(Song(id, title, artist, duration, contentUri, dateAdded = dateAdded))
                }
            } ?: run {
                android.util.Log.d("FirstPlay", "SongRepository: Cursor is null")
            }
        } catch (e: Exception) {
            android.util.Log.e("FirstPlay", "SongRepository: Error loading songs", e)
            e.printStackTrace()
        }
        if (results.isEmpty()) {
            android.util.Log.d("FirstPlay", "SongRepository: Fallback query without selection")
            context.contentResolver.query(collection, projection, null, null, sortOrder)?.use { cursor ->
                android.util.Log.d("FirstPlay", "SongRepository: Fallback cursor count: ${cursor.count}")
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val dateAddedCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val title = cursor.getString(titleCol) ?: "Unknown Title"
                    val artist = cursor.getString(artistCol) ?: "Unknown Artist"
                    val duration = cursor.getLong(durationCol)
                    val dateAdded = cursor.getLong(dateAddedCol)
                    val contentUri = ContentUris.withAppendedId(collection, id)
                    results.add(Song(id, title, artist, duration, contentUri, dateAdded = dateAdded))
                }
            } ?: run {
                android.util.Log.d("FirstPlay", "SongRepository: Fallback cursor is null")
            }
        }
        android.util.Log.d("FirstPlay", "SongRepository: Loaded ${results.size} songs into memory")
        songs = results
        _songsFlow.value = results
    }

    fun getAllSongs(): List<Song> = songs

    fun getSongById(id: Long): Song? {
        return songs.find { it.id == id }
    }
}

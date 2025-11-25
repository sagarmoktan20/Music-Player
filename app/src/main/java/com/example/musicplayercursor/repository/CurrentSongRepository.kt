package com.example.musicplayercursor.repository

import android.content.Context
import android.content.SharedPreferences

object CurrentSongRepository {

    private const val PREF_NAME = "current_song_prefs"
    private const val KEY_CURRENT_SONG_ID = "current_song_id"
    private const val KEY_CURRENT_POSITION = "current_position"
    private const val KEY_IS_PLAYING = "is_playing"
    private const val KEY_IS_RECEIVER_MODE = "is_receiver_mode"
    private const val KEY_RECEIVER_STREAM_URL = "receiver_stream_url"

    private lateinit var prefs: SharedPreferences
    private var initialized = false

    fun init(context: Context) {
        if (!initialized) {
            prefs = context.applicationContext.getSharedPreferences(
                PREF_NAME,
                Context.MODE_PRIVATE
            )
            initialized = true
        }
    }

    fun saveCurrentSong(
        songId: Long?,
        position: Long = 0L,
        isPlaying: Boolean = false,
        isReceiverMode: Boolean = false,
        receiverStreamUrl: String? = null
    ) {
        checkInitialized()
        prefs.edit().apply {
            if (songId != null) {
                putLong(KEY_CURRENT_SONG_ID, songId)
                putLong(KEY_CURRENT_POSITION, position)
                putBoolean(KEY_IS_PLAYING, isPlaying)
                putBoolean(KEY_IS_RECEIVER_MODE, isReceiverMode)
                putString(KEY_RECEIVER_STREAM_URL, receiverStreamUrl)
            } else {
                // Clear state
                remove(KEY_CURRENT_SONG_ID)
                remove(KEY_CURRENT_POSITION)
                remove(KEY_IS_PLAYING)
                remove(KEY_IS_RECEIVER_MODE)
                remove(KEY_RECEIVER_STREAM_URL)
            }
            apply()
        }
    }

    fun getCurrentSongId(): Long? {
        checkInitialized()
        return if (prefs.contains(KEY_CURRENT_SONG_ID)) {
            prefs.getLong(KEY_CURRENT_SONG_ID, -1L).takeIf { it != -1L }
        } else null
    }

    fun getCurrentPosition(): Long {
        checkInitialized()
        return prefs.getLong(KEY_CURRENT_POSITION, 0L)
    }

    fun getIsPlaying(): Boolean {
        checkInitialized()
        return prefs.getBoolean(KEY_IS_PLAYING, false)
    }

    fun getIsReceiverMode(): Boolean {
        checkInitialized()
        return prefs.getBoolean(KEY_IS_RECEIVER_MODE, false)
    }

    fun getReceiverStreamUrl(): String? {
        checkInitialized()
        return prefs.getString(KEY_RECEIVER_STREAM_URL, null)
    }

    fun clearCurrentSong() {
        checkInitialized()
        prefs.edit().clear().apply()
    }

    private fun checkInitialized() {
        if (!initialized) throw IllegalStateException("CurrentSongRepository not initialized!")
    }
}
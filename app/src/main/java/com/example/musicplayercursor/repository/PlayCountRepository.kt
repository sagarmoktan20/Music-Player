package com.example.musicplayercursor.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

object PlayCountRepository {
    private const val PREF_NAME = "playcount_prefs"
    private const val PLAY_COUNT_PREFIX = "playcount_"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        if (!::prefs.isInitialized) {
            prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        }
    }

    fun incrementPlayCount(songId: Long) {
        initIfNeeded()
        Log.d("PlayCountRepository", "incrementPlayCount: $songId")
        val current = prefs.getInt("$PLAY_COUNT_PREFIX$songId", 0)
        prefs.edit().putInt("$PLAY_COUNT_PREFIX$songId", current + 1).apply()
    }

    fun getPlayCount(songId: Long): Int {
        initIfNeeded()
        return prefs.getInt("$PLAY_COUNT_PREFIX$songId", 0)
    }

    fun getAllPlayCounts(): Map<Long, Int> {
        initIfNeeded()
        return prefs.all
            .filter { (k, _) -> k.startsWith(PLAY_COUNT_PREFIX) }
            .mapNotNull { (k, v) ->
                k.removePrefix(PLAY_COUNT_PREFIX).toLongOrNull()?.let { it to v as Int }
            }
            .toMap()
    }

    private fun initIfNeeded() {
        if (!::prefs.isInitialized) {
            throw IllegalStateException("PlayCountRepository not initialized! Call init() in Application")
        }
    }
}
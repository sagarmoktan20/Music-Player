package com.example.musicplayercursor.repository

import android.content.Context
import android.content.SharedPreferences

class PlayCountRepository(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(
        "playcount_prefs",
        Context.MODE_PRIVATE
    )
    
    private val PLAY_COUNT_PREFIX = "playcount_"
    
    fun getPlayCount(songId: Long): Int {
        return prefs.getInt("$PLAY_COUNT_PREFIX$songId", 0)
    }
    
    fun incrementPlayCount(songId: Long) {
        val currentCount = getPlayCount(songId)
        prefs.edit()
            .putInt("$PLAY_COUNT_PREFIX$songId", currentCount + 1)
            .apply()
    }
    
    fun getAllPlayCounts(): Map<Long, Int> {
        val playCounts = mutableMapOf<Long, Int>()
        val allEntries = prefs.all
        for ((key, value) in allEntries) {
            if (key.startsWith(PLAY_COUNT_PREFIX) && value is Int) {
                val songId = key.removePrefix(PLAY_COUNT_PREFIX).toLongOrNull()
                if (songId != null) {
                    playCounts[songId] = value
                }
            }
        }
        return playCounts
    }
}


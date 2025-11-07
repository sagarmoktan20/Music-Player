package com.example.musicplayercursor.repository

import android.content.Context
import android.content.SharedPreferences

class LastPlayedRepository(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(
        "lastplayed_prefs",
        Context.MODE_PRIVATE
    )

    private val LAST_PLAYED_PREFIX = "lastplayed_"

    fun getLastPlayed(songId: Long): Long {
        return prefs.getLong("$LAST_PLAYED_PREFIX$songId", 0L)
    }

    fun setLastPlayed(songId: Long, timestamp: Long) {
        prefs.edit()
            .putLong("$LAST_PLAYED_PREFIX$songId", timestamp)
            .apply()
    }

    fun getAllLastPlayed(): Map<Long, Long> {
        val map = mutableMapOf<Long, Long>()
        for ((key, value) in prefs.all) {
            if (key.startsWith(LAST_PLAYED_PREFIX) && value is Long) {
                val id = key.removePrefix(LAST_PLAYED_PREFIX).toLongOrNull()
                if (id != null) map[id] = value
            }
        }
        return map
    }
}



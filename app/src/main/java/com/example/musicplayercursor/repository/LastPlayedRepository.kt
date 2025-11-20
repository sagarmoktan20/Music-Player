package com.example.musicplayercursor.repository

import android.content.Context
import android.content.SharedPreferences

object LastPlayedRepository {

    private const val PREF_NAME = "lastplayed_prefs"
    private const val LAST_PLAYED_PREFIX = "lastplayed_"

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

    fun getLastPlayed(songId: Long): Long {
        checkInitialized()
        return prefs.getLong("$LAST_PLAYED_PREFIX$songId", 0L)
    }

    fun setLastPlayed(songId: Long, timestamp: Long) {
        checkInitialized()
        prefs.edit()
            .putLong("$LAST_PLAYED_PREFIX$songId", timestamp)
            .apply()
    }

    fun getAllLastPlayed(): Map<Long, Long> {
        checkInitialized()
        val map = mutableMapOf<Long, Long>()
        for ((key, value) in prefs.all) {
            if (key.startsWith(LAST_PLAYED_PREFIX) && value is Long) {
                val id = key.removePrefix(LAST_PLAYED_PREFIX).toLongOrNull()
                if (id != null) map[id] = value
            }
        }
        return map
    }

    private fun checkInitialized() {
        if (!initialized) throw IllegalStateException("LastPlayedRepository not initialized!")
    }
}

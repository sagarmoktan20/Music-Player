package com.example.musicplayercursor.repository

import android.content.Context
import android.content.SharedPreferences

class FavouritesRepository(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(
        "favourites_prefs",
        Context.MODE_PRIVATE
    )
    
    private val FAVOURITES_KEY = "favourite_song_ids"
    
    fun getFavouriteSongIds(): Set<Long> {
        val idsString = prefs.getStringSet(FAVOURITES_KEY, emptySet()) ?: emptySet()
        return idsString.mapNotNull { it.toLongOrNull() }.toSet()
    }
    
    fun addFavourite(songId: Long) {
        val favourites = getFavouriteSongIds().toMutableSet()
        favourites.add(songId)
        saveFavourites(favourites)
    }
    
    fun removeFavourite(songId: Long) {
        val favourites = getFavouriteSongIds().toMutableSet()
        favourites.remove(songId)
        saveFavourites(favourites)
    }
    
    fun isFavourite(songId: Long): Boolean {
        return getFavouriteSongIds().contains(songId)
    }
    
    private fun saveFavourites(favourites: Set<Long>) {
        prefs.edit()
            .putStringSet(FAVOURITES_KEY, favourites.map { it.toString() }.toSet())
            .apply()
    }
}


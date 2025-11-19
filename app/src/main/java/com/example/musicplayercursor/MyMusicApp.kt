package com.example.musicplayercursor

import android.app.Application
import com.example.musicplayercursor.repository.PlayCountRepository

class MyMusicApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PlayCountRepository.init(this)
        // Do the same for FavouritesRepository, LastPlayedRepository if needed
    }
}
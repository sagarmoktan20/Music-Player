package com.example.musicplayercursor.repository

import android.content.Context
import android.content.SharedPreferences
import com.example.musicplayercursor.model.Playlist
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class PlaylistRepository(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(
        "playlists_prefs",
        Context.MODE_PRIVATE
    )
    
    private val PLAYLISTS_KEY = "playlists"
    
    fun getAllPlaylists(): List<Playlist> {
        val playlistsJson = prefs.getString(PLAYLISTS_KEY, null) ?: return emptyList()
        return try {
            val jsonArray = JSONArray(playlistsJson)
            val playlists = mutableListOf<Playlist>()
            for (i in 0 until jsonArray.length()) {
                val jsonObject = jsonArray.getJSONObject(i)
                val songIdsArray = jsonObject.optJSONArray("songIds")
                val songIds = if (songIdsArray != null) {
                    (0 until songIdsArray.length()).mapNotNull { 
                        songIdsArray.optLong(it).takeIf { it != 0L }
                    }.toSet()
                } else {
                    emptySet()
                }
                playlists.add(
                    Playlist(
                        id = jsonObject.getString("id"),
                        name = jsonObject.getString("name"),
                        createdAt = jsonObject.getLong("createdAt"),
                        songIds = songIds
                    )
                )
            }
            playlists.sortedByDescending { it.createdAt }
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    fun getPlaylistById(playlistId: String): Playlist? {
        return getAllPlaylists().find { it.id == playlistId }
    }
    
    fun getSongIdsInPlaylist(playlistId: String): Set<Long> {
        return getPlaylistById(playlistId)?.songIds ?: emptySet()
    }
    
    fun addPlaylist(name: String): Playlist {
        val playlist = Playlist(
            id = UUID.randomUUID().toString(),
            name = name,
            createdAt = System.currentTimeMillis()
        )
        
        val existingPlaylists = getAllPlaylists().toMutableList()
        existingPlaylists.add(playlist)
        savePlaylists(existingPlaylists)
        
        return playlist
    }
    
    fun deletePlaylist(playlistId: String) {
        val existingPlaylists = getAllPlaylists().toMutableList()
        existingPlaylists.removeAll { it.id == playlistId }
        savePlaylists(existingPlaylists)
    }
    
    fun addSongsToPlaylist(playlistId: String, songIds: Set<Long>) {
        val existingPlaylists = getAllPlaylists().toMutableList()
        val playlistIndex = existingPlaylists.indexOfFirst { it.id == playlistId }
        if (playlistIndex >= 0) {
            val playlist = existingPlaylists[playlistIndex]
            // Filter out duplicates - only add songs that aren't already in the playlist
            val newSongIds = (playlist.songIds + songIds).toSet()
            existingPlaylists[playlistIndex] = playlist.copy(songIds = newSongIds)
            savePlaylists(existingPlaylists)
        }
    }
    
    fun removeSongsFromPlaylist(playlistId: String, songIds: Set<Long>) {
        val existingPlaylists = getAllPlaylists().toMutableList()
        val playlistIndex = existingPlaylists.indexOfFirst { it.id == playlistId }
        if (playlistIndex >= 0) {
            val playlist = existingPlaylists[playlistIndex]
            val updatedSongIds = playlist.songIds - songIds
            existingPlaylists[playlistIndex] = playlist.copy(songIds = updatedSongIds)
            savePlaylists(existingPlaylists)
        }
    }
    
    fun removeSongsFromAllPlaylists(songIds: Set<Long>) {
        val existingPlaylists = getAllPlaylists().toMutableList()
        val updatedPlaylists = existingPlaylists.map { playlist ->
            playlist.copy(songIds = playlist.songIds - songIds)
        }
        savePlaylists(updatedPlaylists)
    }
    
    private fun savePlaylists(playlists: List<Playlist>) {
        val jsonArray = JSONArray()
        playlists.forEach { playlist ->
            val jsonObject = JSONObject().apply {
                put("id", playlist.id)
                put("name", playlist.name)
                put("createdAt", playlist.createdAt)
                val songIdsArray = JSONArray()
                playlist.songIds.forEach { songIdsArray.put(it) }
                put("songIds", songIdsArray)
            }
            jsonArray.put(jsonObject)
        }
        prefs.edit()
            .putString(PLAYLISTS_KEY, jsonArray.toString())
            .apply()
    }
}


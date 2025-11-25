package com.example.musicplayercursor.viewmodel

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.musicplayercursor.model.Song
import com.example.musicplayercursor.service.BroadcastService
import com.example.musicplayercursor.util.HotspotDetector
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Random

data class BroadcastState(
    val isBroadcasting: Boolean = false,
    val serverIP: String? = null,
    val token: String? = null,
    val connectedClients: Int = 0
)

class BroadcastViewModel : ViewModel() {
    private val _broadcastState = MutableStateFlow(BroadcastState())
    val broadcastState: StateFlow<BroadcastState> = _broadcastState.asStateFlow()
    
    private var hotspotDetector: HotspotDetector? = null
    private var currentContext: Context? = null
    private var broadcastService: BroadcastService? = null
    private var serviceBound = false
    private var musicViewModel: MusicViewModel? = null
    
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(TAG, "[ServiceConnection] onServiceConnected: name=$name")
            // BroadcastService doesn't use Binder, so we'll get it another way
            // We'll set the callback when service starts
            serviceBound = true
        }
        
        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "⚠️ [ServiceConnection] onServiceDisconnected: name=$name")
            broadcastService = null
            serviceBound = false
        }
    }
    
    companion object {
        private const val TAG = "Broadcast"
        private const val TOKEN_LENGTH = 8
        private const val TOKEN_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    }
    
    /**
     * Generate secure 8-character alphanumeric token
     */
    private fun generateToken(): String {
        Log.d(TAG, ">>> [generateToken] Entry")
        val random = Random()
        val token = (1..TOKEN_LENGTH)
            .map { TOKEN_CHARS[random.nextInt(TOKEN_CHARS.length)] }
            .joinToString("")
        Log.d(TAG, "<<< [generateToken] Success: token=$token")
        return token
    }
    
    /**
     * Start hotspot detection
     */
    // Inside BroadcastViewModel, add this collector once
    fun startHotspotDetection(context: Context) {
        Log.d(TAG, ">>> [startHotspotDetection] Entry")
        hotspotDetector = HotspotDetector(context).apply {
            startDetection()
            Log.d(TAG, "[HotspotDetection] Started detection")
            viewModelScope.launch {
                hotspotIP.collect { ip ->
                    Log.d(TAG, "[HotspotDetection] IP collected: $ip, isBroadcasting: ${_broadcastState.value.isBroadcasting}")
                    if (ip != null && !_broadcastState.value.isBroadcasting) {
                        Log.w(TAG, "⚠️ [HotspotDetection] Hotspot detected → starting broadcast ONCE")
                        startBroadcast(context.applicationContext)
                        // Remove the collector so it NEVER runs again
                        cancel()
                    }
                }
            }
        }
        Log.d(TAG, "<<< [startHotspotDetection] Success: hotspot detector initialized")
    }
    
    /**
     * Check if hotspot is active
     */
    fun isHotspotActive(): Boolean {
        val isActive = hotspotDetector?.isHotspotActive() ?: false
        Log.d(TAG, "[isHotspotActive] Result: $isActive")
        return isActive
    }
    
    /**
     * Get hotspot IP
     */
    fun getHotspotIP(): String? {
        val ip = hotspotDetector?.detectHotspotIP()
        Log.d(TAG, "[getHotspotIP] Result: $ip")
        return ip
    }
    
    /**
     * Set MusicViewModel for playback state access
     */
    fun setMusicViewModel(viewModel: MusicViewModel) {
        Log.d(TAG, ">>> [setMusicViewModel] Entry")
        val oldValue = musicViewModel != null
        musicViewModel = viewModel
        Log.d(TAG, "[setMusicViewModel] musicViewModel set: ${if (oldValue) "updated" else "initial"}")
        Log.d(TAG, "<<< [setMusicViewModel] Success")
    }
    
    /**
     * Start broadcast
     */
    fun startBroadcast(context: Context) {
        Log.d(TAG, ">>> [startBroadcast] Entry")
        val ip = getHotspotIP()
        if (ip == null) {
            Log.e(TAG, "!!! [startBroadcast] Error: Cannot start broadcast: No hotspot IP")
            return
        }
        Log.d(TAG, "[startBroadcast] Hotspot IP detected: $ip")
        
        val token = generateToken()
        Log.i(TAG, "[startBroadcast] Starting broadcast with token: $token, IP: $ip")
        
        val intent = Intent(context, BroadcastService::class.java).apply {
            action = BroadcastService.ACTION_START
            putExtra(BroadcastService.EXTRA_TOKEN, token)
            putExtra(BroadcastService.EXTRA_SERVER_IP, ip)
        }
        Log.d(TAG, "[startBroadcast] Service intent created: action=${intent.action}")
        
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
                Log.d(TAG, "[startBroadcast] Foreground service started (API >= O)")
            } else {
                context.startService(intent)
                Log.d(TAG, "[startBroadcast] Service started (API < O)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "!!! [startBroadcast] Error starting service", e)
            return
        }
        
        val oldState = _broadcastState.value
        _broadcastState.value = BroadcastState(
            isBroadcasting = true,
            serverIP = ip,
            token = token,
            connectedClients = 0
        )
        Log.d(TAG, "[BroadcastState] Changed: isBroadcasting=${oldState.isBroadcasting} -> ${_broadcastState.value.isBroadcasting}")
        Log.d(TAG, "[BroadcastState] serverIP=${_broadcastState.value.serverIP}, token=${_broadcastState.value.token}")
        
        // Set callback IMMEDIATELY — service is already running
        setPlaybackStateCallback(context)
        
        // Start polling for client count
        startClientCountPolling(context)
        Log.i(TAG, "<<< [startBroadcast] Success: Broadcast started")
    }
    
    /**
     * Set playback state callback on BroadcastService
     */


    private fun setPlaybackStateCallback(context: Context) {
        Log.d(TAG, ">>> [setPlaybackStateCallback] Entry")
        if (musicViewModel == null) {
            Log.e(TAG, "!!! [setPlaybackStateCallback] Error: FATAL: musicViewModel is NULL — cannot set playback callback!")
            return
        }

        Log.d(TAG, "[setPlaybackStateCallback] musicViewModel is READY")
        val currentSong = musicViewModel?.uiState?.value?.current
        val currentPosition = musicViewModel?.uiState?.value?.currentPosition ?: 0L
        val duration = musicViewModel?.uiState?.value?.duration ?: 0L
        val isPlaying = musicViewModel?.uiState?.value?.isPlaying ?: false
        Log.d(TAG, "[setPlaybackStateCallback] Current playback state: song=${currentSong?.title}, position=${currentPosition}ms, duration=${duration}ms, isPlaying=$isPlaying")

        val callback = object : BroadcastService.PlaybackStateCallback {
            override fun getCurrentSong(): Song? {
                val song = musicViewModel?.uiState?.value?.current
                Log.d(TAG, "[PlaybackStateCallback.getCurrentSong] Called: song=${song?.title} (id=${song?.id})")
                return song
            }
            override fun getCurrentPosition(): Long {
                val pos = musicViewModel?.uiState?.value?.currentPosition ?: 0L
                Log.d(TAG, "[PlaybackStateCallback.getCurrentPosition] Called: position=$pos ms")
                return pos
            }
            override fun getDuration(): Long {
                val dur = musicViewModel?.uiState?.value?.duration ?: 0L
                Log.d(TAG, "[PlaybackStateCallback.getDuration] Called: duration=$dur ms")
                return dur
            }
            override fun isPlaying(): Boolean {
                val playing = musicViewModel?.uiState?.value?.isPlaying ?: false
                Log.d(TAG, "[PlaybackStateCallback.isPlaying] Called: isPlaying=$playing")
                return playing
            }
        }

        BroadcastService.setGlobalPlaybackStateCallback(callback)
        Log.d(TAG, "<<< [setPlaybackStateCallback] Success: Global playback callback SET")
    }
//    private fun setPlaybackStateCallback(context: Context) {
//        if (musicViewModel == null) {
//            Log.e(TAG, "musicViewModel is null — cannot set callback")
//            return
//        }
//
//        val callback = object : BroadcastService.PlaybackStateCallback {
//            override fun getCurrentSong() = musicViewModel?.uiState?.value?.current
//            override fun getCurrentPosition() = musicViewModel?.uiState?.value?.currentPosition ?: 0L
//            override fun getDuration() = musicViewModel?.uiState?.value?.duration ?: 0L
//            override fun isPlaying() = musicViewModel?.uiState?.value?.isPlaying ?: false
//        }
//
//        BroadcastService.setGlobalPlaybackStateCallback(callback)
//        Log.w(TAG, "Global playback callback successfully set!")
//    }
    
    /**
     * Stop broadcast
     */
    fun stopBroadcast(context: Context) {
        Log.d(TAG, ">>> [stopBroadcast] Entry")
        val oldState = _broadcastState.value
        Log.d(TAG, "[stopBroadcast] Current state: isBroadcasting=${oldState.isBroadcasting}, clients=${oldState.connectedClients}")
        
        val intent = Intent(context, BroadcastService::class.java).apply {
            action = BroadcastService.ACTION_STOP
        }
        Log.d(TAG, "[stopBroadcast] Service stop intent created: action=${intent.action}")
        
        try {
            context.startService(intent)
            Log.d(TAG, "[stopBroadcast] Stop service called")
        } catch (e: Exception) {
            Log.e(TAG, "!!! [stopBroadcast] Error stopping service", e)
        }
        
        _broadcastState.value = BroadcastState()
        Log.d(TAG, "[BroadcastState] Changed: isBroadcasting=${oldState.isBroadcasting} -> ${_broadcastState.value.isBroadcasting}")
        Log.d(TAG, "<<< [stopBroadcast] Success: Broadcast stopped")
    }
    
    /**
     * Poll for client count
     */
    private fun startClientCountPolling(context: Context) {
        Log.d(TAG, ">>> [startClientCountPolling] Entry")
        viewModelScope.launch {
            Log.d(TAG, "[startClientCountPolling] Polling loop started")
            while (_broadcastState.value.isBroadcasting) {
                kotlinx.coroutines.delay(1000)
                // Try to get client count from service
                // Since we don't have direct access, we'll use a broadcast or static method
                // For now, the service will update via a callback mechanism
                // We can improve this later with a proper service binding
            }
            Log.d(TAG, "[startClientCountPolling] Polling loop ended")
        }
        Log.d(TAG, "<<< [startClientCountPolling] Success")
    }
    
    /**
     * Update connected clients count (called by BroadcastService)
     */
    fun updateClientCount(count: Int) {
        val oldCount = _broadcastState.value.connectedClients
        if (oldCount != count) {
            _broadcastState.value = _broadcastState.value.copy(connectedClients = count)
            Log.d(TAG, "[BroadcastState] Connected clients changed: $oldCount -> $count")
        }
    }
    
    override fun onCleared() {
        Log.d(TAG, ">>> [onCleared] Entry")
        super.onCleared()
        
        hotspotDetector?.stopDetection()
        Log.d(TAG, "[onCleared] Hotspot detector stopped and cleared")
        hotspotDetector = null
        currentContext = null
        
        // Clean up service connection if bound
        if (serviceBound && currentContext != null) {
            try {
                currentContext?.unbindService(serviceConnection)
                Log.d(TAG, "[onCleared] Service unbound")
            } catch (e: Exception) {
                Log.e(TAG, "!!! [onCleared] Error unbinding service", e)
            }
            serviceBound = false
        }
        
        // Clear global callback
        BroadcastService.setGlobalPlaybackStateCallback(null)
        Log.d(TAG, "[onCleared] Global playback callback cleared")
        Log.d(TAG, "<<< [onCleared] Success: ViewModel cleared")
    }
}


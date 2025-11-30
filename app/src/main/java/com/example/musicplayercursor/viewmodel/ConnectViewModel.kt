package com.example.musicplayercursor.viewmodel

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.musicplayercursor.model.BroadcastSongInfo
import com.example.musicplayercursor.util.NetworkUtils
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json

data class ConnectState(
    val isConnected: Boolean = false,
    val serverIP: String? = null,
    val token: String? = null,
    val isConnecting: Boolean = false,
    val connectionError: String? = null,
    val broadcastSongInfo: BroadcastSongInfo? = null
)

class ConnectViewModel : ViewModel() {
    private val _connectState = MutableStateFlow(ConnectState())
    val connectState: StateFlow<ConnectState> = _connectState.asStateFlow()
    
    private var webSocketJob: Job? = null
    private var webSocketSession: WebSocketSession? = null
    private var httpClient: HttpClient? = null
    private var musicService: com.example.musicplayercursor.service.MusicService? = null
    private var lastSongId: Long? = null
    private var lastPosition: Long = 0L
    private var lastSeekTime: Long = 0L
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    
    fun setMusicService(service: com.example.musicplayercursor.service.MusicService?) {
        musicService = service
        Log.d(TAG, "[setMusicService] Service set: ${service != null}")
    }

    init {
        Log.d(TAG, ">>> [ConnectViewModel] init Entry")
        httpClient = HttpClient(CIO) {
            install(ContentNegotiation) {
                json(json)
            }
            install(WebSockets)
        }
        Log.d(TAG, "[ConnectViewModel] HTTP client initialized with WebSocket support (CIO engine)")
        Log.d(TAG, "<<< [ConnectViewModel] init Success")
    }

    companion object {
        private const val TAG = "ConnectViewModel"
        private const val DRIFT_THRESHOLD_MS = 500L // Increased to reduce seek frequency
        private const val MIN_SEEK_INTERVAL_MS = 800L // Increased to prevent rapid seeks
        private const val MAX_RECONNECT_ATTEMPTS = 3
        private const val INITIAL_RECONNECT_DELAY_MS = 1000L
        private const val MAX_RECONNECT_DELAY_MS = 10000L
    }
    
    /**
     * Parse deep link: musicplayer://broadcast?ip=192.168.43.1&token=k9m3p2x8
     */
    fun parseDeepLink(uriString: String): Pair<String?, String?> {
        Log.d(TAG, ">>> [parseDeepLink] Entry: uriString=$uriString")
        return try {
            val uri = Uri.parse(uriString)
            Log.d(TAG, "[parseDeepLink] Parsed URI: scheme=${uri.scheme}, host=${uri.host}, query=${uri.query}")
            if (uri.scheme == "musicplayer" && uri.host == "broadcast") {
                val ip = uri.getQueryParameter("ip")
                val token = uri.getQueryParameter("token")
                Log.d(TAG, "[parseDeepLink] Extracted parameters: ip=$ip, token=$token")
                
                if (ip == null || token == null) {
                    Log.e(TAG, "!!! [parseDeepLink] Missing required parameters: ip=$ip, token=$token")
                    return Pair(null, null)
                }
                
                val result = Pair(ip, token)
                Log.i(TAG, "<<< [parseDeepLink] Success: ip=$ip, token=$token")
                result
            } else {
                Log.e(TAG, "!!! [parseDeepLink] Invalid URI scheme/host: scheme=${uri.scheme}, host=${uri.host}")
                Pair(null, null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "!!! [parseDeepLink] Error parsing deep link: ${e.message}", e)
            Log.e(TAG, "!!! [parseDeepLink] Error details: ${e.javaClass.simpleName}")
            e.printStackTrace()
            Pair(null, null)
        }
    }
    
    /**
     * Check if current network is in hotspot range
     */
    fun isOnHotspotNetwork(context: Context): Boolean {
        Log.d(TAG, ">>> [isOnHotspotNetwork] Entry")
        val localIP = NetworkUtils.getLocalIPAddress()
        Log.d(TAG, "[isOnHotspotNetwork] Local IP detected: $localIP")
        
        if (localIP == null) {
            Log.w(TAG, "[isOnHotspotNetwork] No local IP found - not on hotspot network")
            Log.d(TAG, "<<< [isOnHotspotNetwork] Result: false (no IP)")
            return false
        }
        
        val isHotspot = localIP.startsWith("192.168.") ||
            localIP.startsWith("172.16.") || localIP.startsWith("172.17.") ||
            localIP.startsWith("172.18.") || localIP.startsWith("172.19.") ||
            localIP.startsWith("172.20.") || localIP.startsWith("172.21.") ||
            localIP.startsWith("172.22.") || localIP.startsWith("172.23.") ||
            localIP.startsWith("172.24.") || localIP.startsWith("172.25.") ||
            localIP.startsWith("172.26.") || localIP.startsWith("172.27.") ||
            localIP.startsWith("172.28.") || localIP.startsWith("172.29.") ||
            localIP.startsWith("172.30.") || localIP.startsWith("172.31.") ||
            localIP.startsWith("10.")
        
        Log.d(TAG, "[isOnHotspotNetwork] IP range check: $localIP matches hotspot pattern=$isHotspot")
        Log.d(TAG, "<<< [isOnHotspotNetwork] Success: result=$isHotspot (IP: $localIP)")
        return isHotspot
    }
    
    /**
     * Connect to broadcast server
     */
    fun connectToBroadcast(ip: String, token: String, context: Context) {
        Log.d(TAG, ">>> [connectToBroadcast] Entry: ip=$ip, token=$token")
        val currentState = _connectState.value
        if (currentState.isConnecting || currentState.isConnected) {
            Log.w(TAG, "⚠️ [connectToBroadcast] Already connecting or connected: isConnecting=${currentState.isConnecting}, isConnected=${currentState.isConnected}")
            return
        }
        
        if (musicService == null) {
            Log.e(TAG, "!!! [connectToBroadcast] MusicService is null!")
            _connectState.value = _connectState.value.copy(
                connectionError = "MusicService not available"
            )
            return
        }
        
        Log.d(TAG, "[connectToBroadcast] Starting connection to: http://$ip:8080")
        val oldState = _connectState.value
        _connectState.value = _connectState.value.copy(
            isConnecting = true,
            connectionError = null,
            serverIP = ip,
            token = token
        )
        Log.d(TAG, "[ConnectState] Changed: isConnecting=${oldState.isConnecting} -> ${_connectState.value.isConnecting}")
        Log.d(TAG, "[ConnectState] serverIP=${_connectState.value.serverIP}, token=${_connectState.value.token}")
        
        viewModelScope.launch {
            try {
                // Build URL for streaming
                val streamUrl = "http://$ip:8080/song?token=$token"
                Log.d(TAG, "[connectToBroadcast] Stream URL built: $streamUrl")
                
                // Connect via MusicService
                Log.d(TAG, "[connectToBroadcast] Connecting via MusicService...")
                musicService?.connectToBroadcast(streamUrl)
                Log.d(TAG, "[connectToBroadcast] MusicService connection initiated")

                // Set isConnected = true FIRST, before starting polling
                val oldStateBeforeConnect = _connectState.value
                _connectState.value = _connectState.value.copy(
                    isConnected = true,
                    isConnecting = false
                )
                Log.d(TAG, "[ConnectState] Changed: isConnected=${oldStateBeforeConnect.isConnected} -> ${_connectState.value.isConnected}")
                Log.d(TAG, "[ConnectState] Changed: isConnecting=${oldStateBeforeConnect.isConnecting} -> ${_connectState.value.isConnecting}")

                // NOW start WebSocket sync
                Log.d(TAG, "[connectToBroadcast] Starting WebSocket sync...")
                startWebSocketSync(ip, token)
                
                Log.i(TAG, "<<< [connectToBroadcast] Success: Connected to broadcast successfully")
            } catch (e: Exception) {
                Log.e(TAG, "!!! [connectToBroadcast] Error connecting to broadcast: ${e.message}", e)
                Log.e(TAG, "!!! [connectToBroadcast] Error details: ${e.javaClass.simpleName}")
                e.printStackTrace()
                
                val oldErrorState = _connectState.value
                _connectState.value = _connectState.value.copy(
                    isConnecting = false,
                    connectionError = e.message ?: "Connection failed"
                )
                Log.d(TAG, "[ConnectState] Error state set: connectionError=${_connectState.value.connectionError}")
                Log.d(TAG, "[ConnectState] Changed: isConnecting=${oldErrorState.isConnecting} -> ${_connectState.value.isConnecting}")
            }
        }
    }
    
    /**
     * Start WebSocket sync for real-time state synchronization
     */
    private fun startWebSocketSync(ip: String, token: String) {
        Log.d(TAG, ">>> [startWebSocketSync] Entry: ip=$ip, token=$token")
        webSocketJob?.cancel()
        // Close WebSocket session in coroutine
        viewModelScope.launch {
            try {
                webSocketSession?.close()
            } catch (e: Exception) {
                // Ignore close errors
            }
        }
        webSocketSession = null
        lastSongId = null
        lastPosition = 0L
        clockOffset = null
        Log.d(TAG, "[startWebSocketSync] Previous WebSocket job cancelled, state reset")
        
        webSocketJob = viewModelScope.launch {
            var reconnectAttempts = 0
            var reconnectDelay = INITIAL_RECONNECT_DELAY_MS
            
            while (isActive && _connectState.value.isConnected) {
                try {
                    val client = httpClient
                    if (client == null) {
                        Log.e(TAG, "!!! [startWebSocketSync] HTTP client is null, breaking loop")
                        return@launch
                    }
                    
                    val wsUrl = "ws://$ip:8080/sync?token=$token"
                    Log.d(TAG, "🔌 [startWebSocketSync] Connecting to $wsUrl (attempt ${reconnectAttempts + 1})")
                    
                    webSocketSession = client.webSocketSession(wsUrl)
                    Log.i(TAG, "✅ [startWebSocketSync] WebSocket connected successfully")
                    
                    // Reset reconnect state on successful connection
                    reconnectAttempts = 0
                    reconnectDelay = INITIAL_RECONNECT_DELAY_MS
                    
                    // Send initial clock sync message
                    val clientTime = System.currentTimeMillis()
                    val clockSyncMessage = """{"clientTime": $clientTime}"""
                    Log.d(TAG, "[startWebSocketSync] Sending clock sync message: clientTime=$clientTime")
                    webSocketSession?.send(Frame.Text(clockSyncMessage))
                    Log.d(TAG, "[startWebSocketSync] Clock sync message sent successfully")
                    
                    // Receive clock sync response
                    try {
                        Log.d(TAG, "[startWebSocketSync] Waiting for clock sync response...")
                        val clockSyncFrame = webSocketSession?.incoming?.receive() as? Frame.Text
                        if (clockSyncFrame != null) {
                            val responseText = clockSyncFrame.readText()
                            Log.d(TAG, "[startWebSocketSync] Received clock sync response: $responseText")
                            // Parse server time from response: {"serverTime": 1234567890}
                            val serverTimeMatch = Regex(""""serverTime"\s*:\s*(\d+)""").find(responseText)
                            if (serverTimeMatch != null) {
                                val serverTime = serverTimeMatch.groupValues[1].toLong()
                                clockOffset = serverTime - clientTime
                                Log.w(TAG, "[startWebSocketSync] CLOCK OFFSET CALCULATED: $clockOffset ms (serverTime=$serverTime, clientTime=$clientTime)")
                            } else {
                                Log.w(TAG, "⚠️ [startWebSocketSync] Could not parse serverTime from response: $responseText")
                            }
                        } else {
                            Log.w(TAG, "⚠️ [startWebSocketSync] Received null or non-text frame for clock sync")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "!!! [startWebSocketSync] Error receiving clock sync response: ${e.message}", e)
                        Log.e(TAG, "!!! [startWebSocketSync] Error details: ${e.javaClass.simpleName}")
                    }
                    
                    // Listen for incoming messages
                    Log.d(TAG, "[startWebSocketSync] Starting message receive loop...")
                    var messageCount = 0L
                    for (frame in webSocketSession!!.incoming) {
                        Log.d(TAG, "[startWebSocketSync] Received frame: ${frame::class.simpleName}, isActive=$isActive, isConnected=${_connectState.value.isConnected}")
                        
                        if (!isActive || !_connectState.value.isConnected) {
                            Log.w(TAG, "[startWebSocketSync] Connection state changed, breaking message loop: isActive=$isActive, isConnected=${_connectState.value.isConnected}")
                            break
                        }
                        
                        if (frame is Frame.Text) {
                            try {
                                val message = frame.readText()
                                messageCount++
                                Log.d(TAG, "[startWebSocketSync] Received message #$messageCount, length: ${message.length} bytes")
                                
                                // Parse BroadcastSongInfo JSON
                                val songInfo: BroadcastSongInfo = json.decodeFromString(BroadcastSongInfo.serializer(), message)
                                Log.d(TAG, "[startWebSocketSync] Parsed message: songId=${songInfo.songId}, position=${songInfo.positionMs}ms, isPlaying=${songInfo.isPlaying}, serverTime=${songInfo.serverTimestamp}")
                                
                                if (messageCount % 200 == 0L) {
                                    Log.i(TAG, "[startWebSocketSync] Received $messageCount messages total, latest: songId=${songInfo.songId}, position=${songInfo.positionMs}ms")
                                }
                                
                                // Update state
                                val oldSongInfo = _connectState.value.broadcastSongInfo
                                _connectState.value = _connectState.value.copy(
                                    broadcastSongInfo = songInfo
                                )
                                if (oldSongInfo?.songId != songInfo.songId) {
                                    Log.w(TAG, "[startWebSocketSync] Broadcast song changed: ${oldSongInfo?.songId} -> ${songInfo.songId}")
                                }
                                
                                // Sync with MusicService
                                Log.d(TAG, "[startWebSocketSync] Calling syncWithPlayer...")
                                syncWithPlayer(songInfo)
                                Log.d(TAG, "[startWebSocketSync] syncWithPlayer completed")
                                
                            } catch (e: Exception) {
                                Log.e(TAG, "!!! [startWebSocketSync] Error parsing message #$messageCount", e)
                                Log.e(TAG, "!!! [startWebSocketSync] Error details: ${e.javaClass.simpleName}: ${e.message}")
                                e.printStackTrace()
                            }
                        } else if (frame is Frame.Close) {
                            Log.w(TAG, "⚠️ [startWebSocketSync] Received close frame from server")
                            break
                        } else {
                            Log.d(TAG, "[startWebSocketSync] Received non-text frame: ${frame::class.simpleName}")
                        }
                    }
                    
                    Log.d(TAG, "[startWebSocketSync] Message loop ended, connection closed")
                    
                } catch (e: Exception) {
                    Log.e(TAG, "!!! [startWebSocketSync] WebSocket error (attempt ${reconnectAttempts + 1}): ${e.message}", e)
                    Log.e(TAG, "!!! [startWebSocketSync] Error details: ${e.javaClass.simpleName}")
                    e.printStackTrace()
                    
                    // Close session on error
                    Log.d(TAG, "[startWebSocketSync] Closing WebSocket session due to error...")
                    try {
                        webSocketSession?.close()
                        Log.d(TAG, "[startWebSocketSync] WebSocket session closed successfully")
                    } catch (closeError: Exception) {
                        Log.w(TAG, "⚠️ [startWebSocketSync] Error closing WebSocket session: ${closeError.message}")
                    }
                    webSocketSession = null
                    Log.d(TAG, "[startWebSocketSync] WebSocket session cleared")
                    
                    reconnectAttempts++
                    Log.d(TAG, "[startWebSocketSync] Reconnect attempt: $reconnectAttempts/$MAX_RECONNECT_ATTEMPTS")
                    
                    if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
                        Log.e(TAG, "!!! [startWebSocketSync] Max reconnect attempts reached ($MAX_RECONNECT_ATTEMPTS), disconnecting")
                        _connectState.value = _connectState.value.copy(
                            connectionError = "Connection lost: ${e.message}"
                        )
                        Log.d(TAG, "[ConnectState] Connection error set: ${_connectState.value.connectionError}")
                        disconnectFromBroadcast()
                        break
                    }
                    
                    // Exponential backoff
                    Log.d(TAG, "[startWebSocketSync] Reconnecting in ${reconnectDelay}ms (attempt $reconnectAttempts/$MAX_RECONNECT_ATTEMPTS)")
                    delay(reconnectDelay)
                    reconnectDelay = (reconnectDelay * 2).coerceAtMost(MAX_RECONNECT_DELAY_MS)
                    Log.d(TAG, "[startWebSocketSync] Next reconnect delay will be: ${reconnectDelay}ms")
                }
            }
            
            Log.d(TAG, "<<< [startWebSocketSync] WebSocket sync loop ended: isActive=$isActive, isConnected=${_connectState.value.isConnected}")
        }
        Log.d(TAG, "<<< [startWebSocketSync] Success: WebSocket sync job started")
    }
    
    /**
     * Sync MusicService state with broadcaster state
     */
    private var clockOffset: Long? = null // server time - local time

    private suspend fun syncWithPlayer(songInfo: BroadcastSongInfo) {
        Log.d(TAG, ">>> [syncWithPlayer] Entry: songId=${songInfo.songId}, position=${songInfo.positionMs}ms, isPlaying=${songInfo.isPlaying}, serverTime=${songInfo.serverTimestamp}")
        
        // Step 1: Calculate clock offset (only once)
        if (clockOffset == null) {
            clockOffset = songInfo.serverTimestamp - System.currentTimeMillis()
            Log.w(TAG, "[syncWithPlayer] CLOCK OFFSET CALCULATED: $clockOffset ms (serverTime=${songInfo.serverTimestamp}, localTime=${System.currentTimeMillis()})")
        } else {
            Log.d(TAG, "[syncWithPlayer] Using existing clock offset: $clockOffset ms")
        }

        // Step 2: Detect song change
        if (lastSongId != null && lastSongId != songInfo.songId) {
            Log.w(TAG, "[syncWithPlayer] SONG CHANGED! Old: $lastSongId → New: ${songInfo.songId}")
            
            // Reconnect stream for new song
            val serverIP = _connectState.value.serverIP
            val token = _connectState.value.token
            if (serverIP != null && token != null && musicService != null) {
                val newStreamUrl = "http://$serverIP:8080/song?token=$token"
                Log.d(TAG, "[syncWithPlayer] Reconnecting stream for new song: $newStreamUrl")
                musicService?.disconnectFromBroadcast()
                delay(100) // Small delay to ensure cleanup
                musicService?.connectToBroadcast(newStreamUrl)
                delay(300) // Wait for connection to establish
            } else {
                Log.w(TAG, "[syncWithPlayer] No server IP or token available, just seeking to 0")
                musicService?.seekToReceiver(0L)
            }
            
            lastSeekTime = System.currentTimeMillis() // Reset debounce timer on song change
            lastPosition = 0L // Reset position tracking
            Log.d(TAG, "[syncWithPlayer] Song change handled: stream reconnected, timers reset")
        }
        lastSongId = songInfo.songId

        // Step 3: Get current receiver position
        val currentPos = musicService?.getReceiverPosition() ?: 0L
        Log.d(TAG, "[syncWithPlayer] Current receiver position: ${currentPos}ms")

        // Step 4: Calculate predicted position
        val timeSinceServerReport = System.currentTimeMillis() - songInfo.serverTimestamp
        Log.d(TAG, "[syncWithPlayer] Time since server report: ${timeSinceServerReport}ms")
        
        val predictedPosition = if (songInfo.isPlaying && timeSinceServerReport > 0) {
            val predicted = (songInfo.positionMs + timeSinceServerReport).coerceIn(0L, songInfo.durationMs)
            Log.d(TAG, "[syncWithPlayer] Predicted position (playing): ${predicted}ms (serverPos=${songInfo.positionMs}ms + elapsed=${timeSinceServerReport}ms)")
            predicted
        } else {
            Log.d(TAG, "[syncWithPlayer] Predicted position (paused): ${songInfo.positionMs}ms (no advancement)")
            songInfo.positionMs // If paused, position stays same
        }

        // Step 5: Calculate corrected position with clock offset
        val correctedServerPosition = songInfo.positionMs + (System.currentTimeMillis() + (clockOffset ?: 0L) - songInfo.serverTimestamp)
        Log.d(TAG, "[syncWithPlayer] Corrected server position: ${correctedServerPosition}ms")

        // Step 6: Choose target position
        val targetPosition = predictedPosition.coerceIn(0L, songInfo.durationMs)
        Log.d(TAG, "[syncWithPlayer] Target position: ${targetPosition}ms (using predicted)")

        // Store the broadcaster's ACTUAL reported position (not predicted) for catch-up after buffering
        musicService?.updateBroadcasterTargetPosition(songInfo.positionMs)
        Log.d(TAG, "[syncWithPlayer] Stored broadcaster actual position: ${songInfo.positionMs}ms (for buffering catch-up)")

        // Step 7: Check drift and seek if needed
        val drift = kotlin.math.abs(targetPosition - currentPos)
        val timeSinceLastSeek = System.currentTimeMillis() - lastSeekTime
        Log.d(TAG, "[syncWithPlayer] Drift calculation: drift=${drift}ms, timeSinceLastSeek=${timeSinceLastSeek}ms, threshold=${DRIFT_THRESHOLD_MS}ms, minInterval=${MIN_SEEK_INTERVAL_MS}ms")

        val significantDrift = drift > DRIFT_THRESHOLD_MS
        val canSeek = timeSinceLastSeek >= MIN_SEEK_INTERVAL_MS
        val largeEnoughDrift = drift > 400L // Additional threshold to prevent micro-seeks
        Log.d(TAG, "[syncWithPlayer] Seek conditions: significantDrift=$significantDrift, canSeek=$canSeek, largeEnoughDrift=$largeEnoughDrift")

        if (significantDrift && canSeek && largeEnoughDrift) {
            Log.w(TAG, "[syncWithPlayer] LARGE DRIFT DETECTED: $drift ms → SEEKING TO $targetPosition ms (current: ${currentPos}ms)")
            musicService?.seekToReceiver(targetPosition)
            lastSeekTime = System.currentTimeMillis()
            lastPosition = targetPosition
            Log.d(TAG, "[syncWithPlayer] Seek completed: new position=$targetPosition, lastSeekTime updated")
        } else {
            if (drift > DRIFT_THRESHOLD_MS) {
                Log.d(TAG, "[syncWithPlayer] Drift detected ($drift ms) but not seeking: canSeek=$canSeek, largeEnough=$largeEnoughDrift")
            } else {
                Log.d(TAG, "[syncWithPlayer] Sync good: drift=$drift ms (within ±${DRIFT_THRESHOLD_MS}ms tolerance)")
            }
        }

        // Step 8: Sync play/pause state
        val isCurrentlyPlaying = musicService?.isReceiverPlaying() ?: false
        Log.d(TAG, "[syncWithPlayer] Play state: broadcaster=${songInfo.isPlaying}, receiver=$isCurrentlyPlaying")
        
        if (isCurrentlyPlaying != songInfo.isPlaying) {
            if (songInfo.isPlaying) {
                Log.w(TAG, "[syncWithPlayer] ⏯️ PLAY command → receiver was paused, starting playback NOW")
                musicService?.playReceiver()
                Log.d(TAG, "[syncWithPlayer] Play command sent to MusicService")
            } else {
                Log.w(TAG, "[syncWithPlayer] ⏸️ PAUSE command → receiver was playing, pausing NOW")
                musicService?.pauseReceiver()
                Log.d(TAG, "[syncWithPlayer] Pause command sent to MusicService")
            }
        } else {
            Log.d(TAG, "[syncWithPlayer] Play state already in sync: ${songInfo.isPlaying}")
        }

        // Update tracking variables
        lastPosition = if (songInfo.isPlaying) {
            (songInfo.positionMs + timeSinceServerReport).coerceIn(0L, songInfo.durationMs)
        } else {
            songInfo.positionMs
        }
        Log.d(TAG, "[syncWithPlayer] Updated lastPosition: ${lastPosition}ms")
        
        Log.d(TAG, "<<< [syncWithPlayer] Success: Sync completed")
    }

    
    /**
     * Disconnect from broadcast
     */
    fun disconnectFromBroadcast() {
        Log.d(TAG, ">>> [disconnectFromBroadcast] Entry")
        val oldState = _connectState.value
        Log.d(TAG, "[disconnectFromBroadcast] Current state: isConnected=${oldState.isConnected}, isConnecting=${oldState.isConnecting}, serverIP=${oldState.serverIP}, token=${oldState.token}")
        
        // Cancel WebSocket job
        val wasJobActive = webSocketJob?.isActive ?: false
        Log.d(TAG, "[disconnectFromBroadcast] WebSocket job state: isActive=$wasJobActive")
        webSocketJob?.cancel()
        webSocketJob = null
        Log.d(TAG, "[disconnectFromBroadcast] WebSocket job cancelled and cleared")
        
        // Close WebSocket session in coroutine
        val hasSession = webSocketSession != null
        Log.d(TAG, "[disconnectFromBroadcast] WebSocket session exists: $hasSession")
        viewModelScope.launch {
            try {
                if (webSocketSession != null) {
                    Log.d(TAG, "[disconnectFromBroadcast] Closing WebSocket session...")
                    webSocketSession?.close()
                    Log.d(TAG, "[disconnectFromBroadcast] WebSocket session closed successfully")
                } else {
                    Log.d(TAG, "[disconnectFromBroadcast] No WebSocket session to close")
                }
            } catch (e: Exception) {
                Log.e(TAG, "!!! [disconnectFromBroadcast] Error closing WebSocket session: ${e.message}", e)
            }
        }
        webSocketSession = null
        Log.d(TAG, "[disconnectFromBroadcast] WebSocket session cleared")
        
        // Stop MusicService receiver mode
        Log.d(TAG, "[disconnectFromBroadcast] Stopping MusicService receiver mode...")
        musicService?.disconnectFromBroadcast()
        Log.d(TAG, "[disconnectFromBroadcast] MusicService disconnected")
        
        _connectState.value = ConnectState()
        Log.d(TAG, "[ConnectState] Reset to initial state: isConnected=false, serverIP=null, token=null")
        
        lastSongId = null
        lastPosition = 0L
        clockOffset = null
        Log.d(TAG, "[disconnectFromBroadcast] State cleared: lastSongId=null, lastPosition=0, clockOffset=null")
        Log.i(TAG, "<<< [disconnectFromBroadcast] Success: Disconnected from broadcast")
    }
    
    override fun onCleared() {
        Log.d(TAG, ">>> [onCleared] Entry")
        super.onCleared()
        
        Log.d(TAG, "[onCleared] Cleaning up WebSocket job...")
        val wasJobActive = webSocketJob?.isActive ?: false
        webSocketJob?.cancel()
        webSocketJob = null
        Log.d(TAG, "[onCleared] WebSocket job cancelled and cleared (wasActive=$wasJobActive)")
        
        // Close WebSocket session in coroutine
        val hasSession = webSocketSession != null
        Log.d(TAG, "[onCleared] WebSocket session exists: $hasSession")
        viewModelScope.launch {
            try {
                if (webSocketSession != null) {
                    webSocketSession?.close()
                    Log.d(TAG, "[onCleared] WebSocket session closed")
                }
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ [onCleared] Error closing WebSocket session: ${e.message}")
            }
        }
        webSocketSession = null
        
        // Close HTTP client in coroutine
        val hasClient = httpClient != null
        Log.d(TAG, "[onCleared] HTTP client exists: $hasClient")
        viewModelScope.launch {
            try {
                if (httpClient != null) {
                    httpClient?.close()
                    Log.d(TAG, "[onCleared] HTTP client closed")
                }
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ [onCleared] Error closing HTTP client: ${e.message}")
            }
        }
        httpClient = null
        Log.d(TAG, "[onCleared] HTTP client cleared")
        
        Log.d(TAG, "<<< [onCleared] Success: ConnectViewModel cleared")
    }
}
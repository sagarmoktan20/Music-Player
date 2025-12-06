package com.example.musicplayercursor.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.musicplayercursor.MainActivity
import com.example.musicplayercursor.R
import com.example.musicplayercursor.model.BroadcastSongInfo
import com.example.musicplayercursor.model.Song
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.stop
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.origin
import io.ktor.server.request.header
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondFile
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
//import io.ktor.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileDescriptor
import java.io.InputStream
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.util.cio.ChannelWriteException
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.close
import io.ktor.utils.io.jvm.javaio.copyTo
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.io.FileInputStream
import java.io.OutputStream
import java.nio.channels.ClosedChannelException
import java.util.concurrent.TimeUnit


class BroadcastService : Service() {
    companion object {
        private const val TAG = "Broadcast"
        private const val NOTIFICATION_ID = 2
        private const val CHANNEL_ID = "broadcast_channel"
        private const val PORT = 8080

        const val ACTION_START = "com.example.musicplayercursor.BROADCAST_START"
        const val ACTION_STOP = "com.example.musicplayercursor.BROADCAST_STOP"
        const val EXTRA_TOKEN = "token"
        const val EXTRA_SERVER_IP = "server_ip"

    }

    private var server: NettyApplicationEngine? = null
    private val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentToken: String = ""
    private val connectedClients = mutableSetOf<DefaultWebSocketServerSession>()
    private var syncJob: Job? = null

    // Add this as a class-level variable to track persistent stream state
    private val persistentStreamClients = mutableMapOf<String, Pair<Long, Long>>() // clientId -> (currentSongId, lastPosition)

    private var latestPlaybackState: com.example.musicplayercursor.service.PlaybackState = com.example.musicplayercursor.service.PlaybackState()
    private var playbackObserverJob: Job? = null

    override fun onCreate() {
        Log.d(TAG, ">>> [onCreate] Entry")
        super.onCreate()
        createNotificationChannel()
        Log.d(TAG, "[onCreate] Notification channel created")
        playbackObserverJob = com.example.musicplayercursor.repository.PlaybackRepository.state
            .onEach { latestPlaybackState = it }
            .launchIn(serverScope)
        Log.d(TAG, "<<< [onCreate] Success: BroadcastService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, ">>> [onStartCommand] Entry: action=${intent?.action}, startId=$startId")
        when (intent?.action) {
            ACTION_START -> {
                val token = intent.getStringExtra(EXTRA_TOKEN)!!
                val ip = intent.getStringExtra(EXTRA_SERVER_IP)!!
                Log.d(TAG, "[onStartCommand] ACTION_START received: token=$token, ip=$ip, server=${server != null}")
                if (server == null) {
                    startBroadcast(token, ip)
                } else {
                    Log.w(TAG, "⚠️ [onStartCommand] Server already running, ignoring start request")
                }
            }
            ACTION_STOP -> {
                Log.d(TAG, "[onStartCommand] ACTION_STOP received")
                stopBroadcast()
            }
            null -> {
                Log.w(TAG, "⚠️ [onStartCommand] Intent action is null")
            }
            else -> {
                Log.w(TAG, "⚠️ [onStartCommand] Unknown action: ${intent.action}")
            }
        }
        Log.d(TAG, "<<< [onStartCommand] Success: Returning START_STICKY")
        return START_STICKY
    }

    private fun startBroadcast(token: String, serverIP: String) {
        Log.d(TAG, ">>> [startBroadcast] Entry: token=$token, serverIP=$serverIP")
        if (server != null) {
            Log.w(TAG, "⚠️ [startBroadcast] Server already running, aborting")
            return
        }

        currentToken = token
        Log.i(TAG, "[startBroadcast] Starting broadcast server → http://$serverIP:8080/song?token=$token")
        Log.d(TAG, "[startBroadcast] Creating embedded server on port $PORT, host 0.0.0.0")

        server = embeddedServer(Netty, port = PORT, host = "0.0.0.0") {
            // Only install what you need
            install(ContentNegotiation) {
                json()
            }
            install(WebSockets)

            routing {
                // Root endpoint
                get("/") {
                    Log.d(TAG, "🌐 [HTTP GET /] Request from ${call.request.origin.remoteHost}")
                    call.respondText("Music Broadcast Server\nUse: /song?token=$currentToken")
                }

                get("/song") {
                    val remoteHost = call.request.origin.remoteHost
                    val requestTime = System.currentTimeMillis()
                    Log.d(TAG, "🌐 [HTTP GET /song] Request from $remoteHost at $requestTime")
                    
                    val tokenParam = call.request.queryParameters["token"]
                    if (tokenParam == null) {
                        Log.e(TAG, "!!! [HTTP GET /song] Missing token from $remoteHost")
                        return@get call.respond(HttpStatusCode.Unauthorized, "Missing token")
                    }
                    Log.d(TAG, "[HTTP GET /song] Token received: $tokenParam")

                    if (tokenParam != currentToken) {
                        Log.w(TAG, "⚠️ [HTTP GET /song] Invalid token from $remoteHost: expected=$currentToken, got=$tokenParam")
                        return@get call.respond(HttpStatusCode.Forbidden, "Wrong token")
                    }
                    Log.d(TAG, "[HTTP GET /song] Token validated successfully")

                    val song = latestPlaybackState.currentSong
                    if (song == null) {
                        Log.w(TAG, "⚠️ [HTTP GET /song] No song playing, callback=false")
                        return@get call.respond(HttpStatusCode.NotFound, "No song playing")
                    }
                    Log.d(TAG, "[HTTP GET /song] Streaming song: ${song.title} by ${song.artist}")

                    try {
                        contentResolver.openFileDescriptor(song.contentUri, "r")?.use { pfd ->
                            val fd = pfd.fileDescriptor
                            val fileSize = pfd.statSize
                            Log.d(TAG, "[HTTP GET /song] File opened: size=$fileSize bytes")

                            // OPTIMIZED: Only parse Range header if it's actually needed (seeking, not new song)
                            // New songs always start from 0, so skip parsing overhead
                            val rangeHeader = call.request.headers["Range"]
                            val startByte = when {
                                // No Range header = new song, start from 0
                                rangeHeader == null -> {
                                    Log.d(TAG, "[HTTP GET /song] No Range header - new song, starting from 0")
                                    0L
                                }
                                // Range header starts with "bytes=0" = new song, start from 0 (skip parsing)
                                rangeHeader.startsWith("bytes=0") -> {
                                    Log.d(TAG, "[HTTP GET /song] Range header starts from 0 - new song, starting from 0")
                                    0L
                                }
                                // Range header with non-zero start = seek operation, parse it
                                else -> {
                                    val parsedStart = rangeHeader.substringAfter("bytes=").substringBefore("-").toLongOrNull() ?: 0L
                                    Log.d(TAG, "[HTTP GET /song] Range header parsed: $rangeHeader -> startByte: $parsedStart (seek operation)")
                                    parsedStart
                                }
                            }

                            // Set correct headers
                            if (startByte > 0 && startByte < fileSize) {
                                call.response.status(HttpStatusCode.PartialContent)
                                call.response.header(HttpHeaders.ContentRange, "bytes $startByte-${fileSize - 1}/$fileSize")
                                Log.d(TAG, "[HTTP GET /song] Partial content: bytes $startByte-${fileSize - 1}/$fileSize")
                            } else {
                                call.response.status(HttpStatusCode.OK)
                                Log.d(TAG, "[HTTP GET /song] Full content: $fileSize bytes (starting from 0)")
                            }

                            call.response.header(HttpHeaders.AcceptRanges, "bytes")
                            call.response.header(HttpHeaders.ContentType, "audio/mpeg")
                            // Informational headers for debugging/clients (not used by ExoPlayer)
                            run {
                                val pos = latestPlaybackState.currentPosition
                                val dur = latestPlaybackState.duration
                                val playing = latestPlaybackState.isPlaying
                                call.response.header("X-Broadcaster-Position-Ms", pos.toString())
                                call.response.header("X-Broadcaster-Duration-Ms", dur.toString())
                                call.response.header("X-Broadcaster-IsPlaying", playing.toString())
                            }

                            Log.d(TAG, "[HTTP GET /song] Starting stream transfer from byte $startByte")
                            val streamStartTime = System.currentTimeMillis()
                            var bytesTransferred = 0L
                            
                            call.respond(object : OutgoingContent.WriteChannelContent() {
                                override val contentLength: Long? = null
                                override val headers: Headers = Headers.Empty

                                override suspend fun writeTo(channel: ByteWriteChannel) {
                                    try {
                                        Log.d(TAG, "[HTTP GET /song] Opening file input stream, starting at byte $startByte")
                                        FileInputStream(fd).use { input ->
                                            // Only skip if startByte > 0 (seek operation)
                                            if (startByte > 0) {
                                                input.skip(startByte)
                                                Log.d(TAG, "[HTTP GET /song] Skipped to byte $startByte (seek operation)")
                                            } else {
                                                Log.d(TAG, "[HTTP GET /song] Starting from beginning (new song)")
                                            }

                                            // Properly write bytes to ByteWriteChannel
                                            val buffer = ByteArray(8192)
                                            var bytesRead: Int
                                            var chunkCount = 0L
                                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                                channel.writeFully(buffer, 0, bytesRead)
                                                bytesTransferred += bytesRead
                                                chunkCount++
                                                
                                                if (chunkCount % 1000 == 0L) {
                                                    Log.d(TAG, "[HTTP GET /song] Transferred ${bytesTransferred} bytes in $chunkCount chunks")
                                                }

                                                // CRITICAL: Keep streaming - don't stop when we reach "end"
                                                // The stream should continue until the client disconnects
                                                // or the broadcaster stops
                                            }
                                            Log.d(TAG, "[HTTP GET /song] File read complete: total bytes=$bytesTransferred, chunks=$chunkCount")
                                        }
                                        val streamDuration = System.currentTimeMillis() - streamStartTime
                                        Log.d(TAG, "[HTTP GET /song] Stream transfer completed: ${bytesTransferred} bytes in ${streamDuration}ms")
                                    } catch (e: Exception) {
                                        val streamDuration = System.currentTimeMillis() - streamStartTime
                                        Log.w(TAG, "⚠️ [HTTP GET /song] Client disconnected during stream: ${e.message} (after ${streamDuration}ms, ${bytesTransferred} bytes)")
                                        throw e
                                    } finally {
                                        Log.d(TAG, "[HTTP GET /song] Closing write channel...")
                                        channel.close()
                                        Log.d(TAG, "[HTTP GET /song] Write channel closed")
                                    }
                                }
                            })
                            Log.i(TAG, "<<< [HTTP GET /song] Success: Stream sent to $remoteHost")
                        }
                            ?: run {
                            Log.e(TAG, "!!! [HTTP GET /song] Error: Cannot open file descriptor for ${song.contentUri}")
                            call.respond(HttpStatusCode.InternalServerError, "Cannot open file")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "!!! [HTTP GET /song] Error during streaming", e)
                        call.respond(HttpStatusCode.InternalServerError, "Stream error: ${e.message}")
                    }
                }

                get("/stream") {
                    val remoteHost = call.request.origin.remoteHost
                    val clientId = "${remoteHost}_${System.currentTimeMillis()}"
                    val requestTime = System.currentTimeMillis()
                    Log.d(TAG, "�� [HTTP GET /stream] Persistent stream request from $remoteHost (clientId=$clientId)")
                    
                    val tokenParam = call.request.queryParameters["token"]
                    if (tokenParam == null) {
                        Log.e(TAG, "!!! [HTTP GET /stream] Missing token from $remoteHost")
                        return@get call.respond(HttpStatusCode.Unauthorized, "Missing token")
                    }

                    if (tokenParam != currentToken) {
                        Log.w(TAG, "⚠️ [HTTP GET /stream] Invalid token from $remoteHost")
                        return@get call.respond(HttpStatusCode.Forbidden, "Wrong token")
                    }

                    // Set headers for persistent streaming
                    call.response.header(HttpHeaders.ContentType, "audio/mpeg")
                    call.response.header(HttpHeaders.AcceptRanges, "bytes")
                    call.response.status(HttpStatusCode.OK)
                    
                    Log.d(TAG, "[HTTP GET /stream] Starting persistent stream for client $clientId")
                    
                    try {
                        call.respond(object : OutgoingContent.WriteChannelContent() {
                            override val contentLength: Long? = null
                            override val headers: Headers = Headers.Empty

                            override suspend fun writeTo(channel: ByteWriteChannel) {
                                var lastCompletedSongId: Long? = null
                                var streamStartTime = System.currentTimeMillis()
                                
                                try {
                    while (true) { // Keep connection alive
                        val currentSong = latestPlaybackState.currentSong
                        
                        if (currentSong == null) {
                            Log.d(TAG, "[HTTP GET /stream] No song playing, waiting...")
                            delay(100)
                            continue
                        }
                        
                        // If we already streamed this song completely, wait for broadcaster to switch
                        if (lastCompletedSongId != null && currentSong.id == lastCompletedSongId) {
                            delay(100)
                            continue
                        }
                        
                        Log.i(TAG, "[HTTP GET /stream] Starting stream for song ${currentSong.id} (${currentSong.title})")
                        streamStartTime = System.currentTimeMillis()
                        
                        // Open and stream current song file fully before switching
                        contentResolver.openFileDescriptor(currentSong.contentUri, "r")?.use { pfd ->
                            val fd = pfd.fileDescriptor
                            
                            FileInputStream(fd).use { input ->
                                val buffer = ByteArray(8192)
                                var bytesRead: Int
                                var totalBytesForSong = 0L
                                
                                while (input.read(buffer).also { bytesRead = it } != -1) {
                                    try {
                                        channel.writeFully(buffer, 0, bytesRead)
                                        totalBytesForSong += bytesRead
                                    } catch (e: Exception) {
                                        Log.w(TAG, "[HTTP GET /stream] Client disconnected: ${e.message}")
                                        throw e
                                    }
                                }
                                
                                val duration = System.currentTimeMillis() - streamStartTime
                                Log.d(TAG, "[HTTP GET /stream] Completed stream for song ${currentSong.id}: ${totalBytesForSong} bytes in ${duration}ms")
                            }
                        } ?: run {
                            Log.w(TAG, "[HTTP GET /stream] Cannot open file for ${currentSong.contentUri}, ending stream for this client")
                            delay(100)
                            return@writeTo
                        }
                        
                        // Mark song as fully streamed so we don't resend unless broadcaster changes track
                        lastCompletedSongId = currentSong.id
                    }
                                } catch (e: Exception) {
                                    if (e is ClosedChannelException || e.message?.contains("closed") == true) {
                                        Log.d(TAG, "[HTTP GET /stream] Client $clientId disconnected normally")
                                    } else {
                                        Log.e(TAG, "[HTTP GET /stream] Error in persistent stream for client $clientId", e)
                                    }
                                    throw e
                                } finally {
                                    persistentStreamClients.remove(clientId)
                                    Log.d(TAG, "[HTTP GET /stream] Persistent stream ended for client $clientId")
                                }
                            }
                        })
                    } catch (e: Exception) {
                        Log.e(TAG, "!!! [HTTP GET /stream] Error setting up persistent stream", e)
                        call.respond(HttpStatusCode.InternalServerError, "Stream error: ${e.message}")
                    }
                }





                get("/current") {
                    val remoteHost = call.request.origin.remoteHost
                    Log.d(TAG, "🌐 [HTTP GET /current] Request from $remoteHost")
                    try {
                        val tokenParam = call.request.queryParameters["token"]
                        if (tokenParam == null) {
                            Log.w(TAG, "⚠️ [HTTP GET /current] Missing token from $remoteHost")
                            return@get call.respond(HttpStatusCode.Unauthorized, "Missing token")
                        }
                        Log.d(TAG, "[HTTP GET /current] Token received: $tokenParam")

                        if (tokenParam != currentToken) {
                            Log.w(TAG, "⚠️ [HTTP GET /current] Invalid token from $remoteHost: expected=$currentToken, got=$tokenParam")
                            return@get call.respond(HttpStatusCode.Forbidden, "Wrong token")
                        }
                        Log.d(TAG, "[HTTP GET /current] Token validated successfully")

                        val song = latestPlaybackState.currentSong
                        if (song == null) {
                            Log.d(TAG, "[HTTP GET /current] No song playing")
                            return@get call.respond(HttpStatusCode.NoContent)
                        }
                        val position = latestPlaybackState.currentPosition
                        val duration = latestPlaybackState.duration
                        val isPlaying = latestPlaybackState.isPlaying
                        val serverTimestamp = System.currentTimeMillis()
                        
                        Log.d(TAG, "[HTTP GET /current] Song: ${song.title} by ${song.artist}")
                        Log.d(TAG, "[HTTP GET /current] Position: ${position}ms / ${duration}ms, isPlaying: $isPlaying")

                        val response = BroadcastSongInfo(
                            songId = song.id,
                            title = song.title,
                            artist = song.artist,
                            durationMs = duration,
                            positionMs = position,
                            isPlaying = isPlaying,
                            serverTimestamp = serverTimestamp
                        )
                        call.respond(response)
                        Log.d(TAG, "<<< [HTTP GET /current] Success: Response sent")
                    } catch (t: Throwable) {
                        Log.e(TAG, "!!! [HTTP GET /current] Error: endpoint crashed!", t)
                        call.respond(HttpStatusCode.InternalServerError, "Crash: ${t.message}")
                    }
                }

                // WebSocket endpoint for real-time sync
                webSocket("/sync") {
                    val remoteHost = call.request.origin.remoteHost
                    Log.d(TAG, "🔌 [WebSocket /sync] Connection attempt from $remoteHost")
                    
                    try {
                        val tokenParam = call.request.queryParameters["token"]
                        if (tokenParam == null) {
                            Log.w(TAG, "⚠️ [WebSocket /sync] Missing token from $remoteHost")
                            close(io.ktor.websocket.CloseReason(io.ktor.websocket.CloseReason.Codes.PROTOCOL_ERROR, "Missing token"))
                            return@webSocket
                        }
                        Log.d(TAG, "[WebSocket /sync] Token received: $tokenParam")

                        if (tokenParam != currentToken) {
                            Log.w(TAG, "⚠️ [WebSocket /sync] Invalid token from $remoteHost: expected=$currentToken, got=$tokenParam")
                            close(io.ktor.websocket.CloseReason(io.ktor.websocket.CloseReason.Codes.PROTOCOL_ERROR, "Wrong token"))
                            return@webSocket
                        }
                        Log.d(TAG, "[WebSocket /sync] Token validated successfully")

                        // Add client to connected set
                        connectedClients.add(this)
                        Log.i(TAG, "✅ [WebSocket /sync] Client connected from $remoteHost (total clients: ${connectedClients.size})")

                        // Start sync job if not already running
                        if (syncJob == null || !syncJob!!.isActive) {
                            Log.d(TAG, "[WebSocket /sync] Starting sync job for broadcasting state")
                            startSyncJob()
                        }

                        // Handle initial clock sync message from client
                        Log.d(TAG, "[WebSocket /sync] Waiting for initial clock sync message from client...")
                        try {
                            val initialFrame = incoming.receive() as? Frame.Text
                            if (initialFrame != null) {
                                val message = initialFrame.readText()
                                Log.d(TAG, "[WebSocket /sync] Received initial message from client: $message")
                                // Client sends: {"clientTime": 1234567890}
                                // Server responds with server time for clock offset calculation
                                val serverTime = System.currentTimeMillis()
                                val response = """{"serverTime": $serverTime}"""
                                Log.d(TAG, "[WebSocket /sync] Sending clock sync response: serverTime=$serverTime")
                                send(Frame.Text(response))
                                Log.d(TAG, "[WebSocket /sync] Clock sync response sent successfully")
                            } else {
                                Log.w(TAG, "⚠️ [WebSocket /sync] Received null or non-text frame for clock sync")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "!!! [WebSocket /sync] Error receiving/processing initial message: ${e.message}", e)
                            Log.e(TAG, "!!! [WebSocket /sync] Error details: ${e.javaClass.simpleName}")
                        }

                        // Keep connection alive and handle disconnections
                        Log.d(TAG, "[WebSocket /sync] Entering message receive loop for client $remoteHost")
                        var frameCount = 0L
                        for (frame in incoming) {
                            frameCount++
                            Log.d(TAG, "[WebSocket /sync] Received frame #$frameCount from $remoteHost: ${frame::class.simpleName}")
                            
                            // Clients don't send regular messages, just keep connection alive
                            if (frame is Frame.Close) {
                                Log.w(TAG, "[WebSocket /sync] Client $remoteHost sent close frame")
                                break
                            } else if (frame is Frame.Text) {
                                val text = frame.readText()
                                Log.d(TAG, "[WebSocket /sync] Received text frame from client: $text")
                            }
                        }
                        Log.d(TAG, "[WebSocket /sync] Message receive loop ended for client $remoteHost (received $frameCount frames)")
                    } catch (e: Exception) {
                        Log.e(TAG, "!!! [WebSocket /sync] Error handling client $remoteHost: ${e.message}", e)
                        Log.e(TAG, "!!! [WebSocket /sync] Error details: ${e.javaClass.simpleName}")
                        e.printStackTrace()
                    } finally {
                        // Remove client from connected set
                        val wasInSet = connectedClients.contains(this)
                        connectedClients.remove(this)
                        val remainingClients = connectedClients.size
                        Log.i(TAG, "🔌 [WebSocket /sync] Client disconnected from $remoteHost (wasInSet=$wasInSet, remaining clients: $remainingClients)")
                        
                        // Stop sync job if no clients remain
                        if (connectedClients.isEmpty()) {
                            Log.w(TAG, "[WebSocket /sync] No clients remaining, stopping sync job")
                            val wasJobActive = syncJob?.isActive ?: false
                            syncJob?.cancel()
                            syncJob = null
                            Log.d(TAG, "[WebSocket /sync] Sync job stopped (wasActive=$wasJobActive)")
                        } else {
                            Log.d(TAG, "[WebSocket /sync] Sync job continues with $remainingClients remaining clients")
                        }
                    }
                }

            }
        }

        Log.d(TAG, "[startBroadcast] Server configuration complete, starting server...")
        serverScope.launch {
            try {
                server?.start(wait = false)  // NON-BLOCKING — CRITICAL
                Log.i(TAG, "[startBroadcast] ✅ SERVER STARTED → http://$serverIP:$PORT")
                Log.d(TAG, "[startBroadcast] Server endpoints: /, /song?token=..., /stream?token=... (persistent), /current?token=..., /sync?token=... (WebSocket)")
                
                val notification = createNotification("Running • $serverIP:$PORT")
                startForeground(NOTIFICATION_ID, notification)
                Log.d(TAG, "[startBroadcast] Foreground service started with notification")
                Log.i(TAG, "<<< [startBroadcast] Success: Broadcast server is running")
            } catch (e: Exception) {
                Log.e(TAG, "!!! [startBroadcast] Error starting server", e)
                stopBroadcast()
            }
        }
    }

        private fun startSyncJob() {
            Log.d(TAG, ">>> [startSyncJob] Entry")
            if (syncJob != null && syncJob!!.isActive) {
                Log.w(TAG, "⚠️ [startSyncJob] Sync job already running")
                return
            }

            syncJob = serverScope.launch {
                Log.d(TAG, "[startSyncJob] Broadcast loop started (50ms interval - 20 updates/sec)")
                var updateCount = 0L
                var loopIteration = 0L

                while (isActive && connectedClients.isNotEmpty()) {
                    loopIteration++
                    try {
                        Log.d(TAG, "[startSyncJob] Loop iteration #$loopIteration, connected clients: ${connectedClients.size}")

                        val song = latestPlaybackState.currentSong
                        if (song == null) {
                            Log.d(TAG, "[startSyncJob] No song playing, skipping update (iteration #$loopIteration)")
                            delay(100)
                            continue
                        }
                        Log.d(TAG, "[startSyncJob] Song found: ${song.title} (id=${song.id})")
                        val position = latestPlaybackState.currentPosition
                        val duration = latestPlaybackState.duration
                        val isPlaying = latestPlaybackState.isPlaying
                        val serverTimestamp = System.currentTimeMillis()
                        Log.d(TAG, "[startSyncJob] Playback state: songId=${song.id}, position=${position}ms, duration=${duration}ms, isPlaying=$isPlaying, timestamp=$serverTimestamp")

                        val songInfo = BroadcastSongInfo(
                            songId = song.id,
                            title = song.title,
                            artist = song.artist,
                            durationMs = duration,
                            positionMs = position,
                            isPlaying = isPlaying,
                            serverTimestamp = serverTimestamp
                        )

                        // Serialize to JSON
                        val json = Json { ignoreUnknownKeys = true }
                        val message = json.encodeToString(BroadcastSongInfo.serializer(), songInfo)
                        Log.d(TAG, "[startSyncJob] Serialized message length: ${message.length} bytes")

                        // Broadcast to all connected clients
                        val clientsToRemove = mutableSetOf<DefaultWebSocketServerSession>()
                        var successfulSends = 0
                        var failedSends = 0
                        for (client in connectedClients) {
                            try {
                                client.send(Frame.Text(message))
                                successfulSends++
                                updateCount++
                                if (updateCount % 200 == 0L) {
                                    Log.d(TAG, "[startSyncJob] Sent $updateCount updates to ${connectedClients.size} clients (successful: $successfulSends, failed: $failedSends)")
                                }
                            } catch (e: Exception) {
                                failedSends++
                                Log.w(TAG, "⚠️ [startSyncJob] Error sending to client, will remove: ${e.message}")
                                clientsToRemove.add(client)
                            }
                        }
                        if (updateCount % 200 != 0L) {
                            Log.d(TAG, "[startSyncJob] Broadcast complete: sent to $successfulSends/${connectedClients.size} clients, failed: $failedSends")
                        }

                        // Remove disconnected clients
                        if (clientsToRemove.isNotEmpty()) {
                            Log.w(TAG, "[startSyncJob] Removing ${clientsToRemove.size} disconnected clients")
                            for (client in clientsToRemove) {
                                connectedClients.remove(client)
                                try {
                                    client.close()
                                    Log.d(TAG, "[startSyncJob] Disconnected client closed successfully")
                                } catch (e: Exception) {
                                    Log.w(TAG, "⚠️ [startSyncJob] Error closing disconnected client: ${e.message}")
                                }
                            }
                            Log.d(TAG, "[startSyncJob] Remaining clients: ${connectedClients.size}")
                        }

                        if (connectedClients.isEmpty()) {
                            Log.w(TAG, "[startSyncJob] No clients remaining, stopping broadcast loop")
                            break
                        }

                    } catch (e: Exception) {
                        Log.e(TAG, "!!! [startSyncJob] Error in broadcast loop (iteration #$loopIteration)", e)
                        Log.e(TAG, "!!! [startSyncJob] Error details: ${e.javaClass.simpleName}: ${e.message}")
                        e.printStackTrace()
                    }

                    delay(100) // 20 updates per second (50ms interval) for balanced performance
                    Log.d(TAG, "[startSyncJob] Waiting 50ms before next update...")
                }

                Log.d(TAG, "<<< [startSyncJob] Broadcast loop ended")
            }
            Log.d(TAG, "<<< [startSyncJob] Success: Sync job started")
        }

    private fun stopBroadcast() {
        Log.d(TAG, ">>> [stopBroadcast] Entry")
        val clientCount = connectedClients.size
        val wasServerRunning = server != null
        Log.d(TAG, "[stopBroadcast] Current state: server running=$wasServerRunning, clients=$clientCount, token=$currentToken")
        
        try {
            if (server != null) {
                Log.d(TAG, "[stopBroadcast] Stopping server...")
                server?.stop(1, 5, TimeUnit.SECONDS)
                Log.d(TAG, "[stopBroadcast] Server stopped")
            } else {
                Log.d(TAG, "[stopBroadcast] Server was not running")
            }
            server = null
        } catch (e: Exception) {
            Log.e(TAG, "!!! [stopBroadcast] Error stopping server", e)
        }
        
        currentToken = ""
        Log.d(TAG, "[stopBroadcast] Token cleared")
        
        syncJob?.cancel()
        syncJob = null
        Log.d(TAG, "[stopBroadcast] Sync job cancelled")
        
        connectedClients.clear()
        Log.d(TAG, "[stopBroadcast] Connected clients cleared: $clientCount clients removed")
        
        try {
            stopForeground(true)
            Log.d(TAG, "[stopBroadcast] Foreground service stopped")
        } catch (e: Exception) {
            Log.e(TAG, "!!! [stopBroadcast] Error stopping foreground", e)
        }
        
        stopSelf()
        Log.i(TAG, "<<< [stopBroadcast] Success: Broadcast stopped and service will be destroyed")
    }

    private fun createNotificationChannel() {
        Log.d(TAG, ">>> [createNotificationChannel] Entry")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Broadcast", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
            Log.d(TAG, "[createNotificationChannel] Notification channel created: $CHANNEL_ID")
        } else {
            Log.d(TAG, "[createNotificationChannel] API < O, notification channel not needed")
        }
        Log.d(TAG, "<<< [createNotificationChannel] Success")
    }

    private fun createNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Music Broadcast Active")
            .setContentText(text)
            .setSmallIcon(R.drawable.baseline_music_note_24)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        Log.d(TAG, ">>> [onDestroy] Entry")
        super.onDestroy()
        stopBroadcast()
        serverScope.cancel()
        playbackObserverJob?.cancel()
        Log.d(TAG, "[onDestroy] Server scope cancelled")
        Log.d(TAG, "<<< [onDestroy] Success: Service destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

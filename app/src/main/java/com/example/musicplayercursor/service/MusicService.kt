package com.example.musicplayercursor.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.example.musicplayercursor.MainActivity
import com.example.musicplayercursor.R
import com.example.musicplayercursor.model.Song
import com.example.musicplayercursor.repository.CurrentSongRepository
import com.example.musicplayercursor.repository.LastPlayedRepository
import com.example.musicplayercursor.repository.PlayCountRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class PlaybackState(
    val currentSong: Song? = null,
    val isPlaying: Boolean = false,
    val currentPosition: Long = 0L,
    val duration: Long = 0L,
    val isReceiverMode: Boolean = false,
    val receiverStreamUrl: String? = null,
    val isLooping: Boolean = false
)

class MusicService : Service() {

    companion object {
        private const val TAG = "MusicService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "music_playback_channel"
        
        const val ACTION_PLAY = "com.example.musicplayercursor.ACTION_PLAY"
        const val ACTION_PAUSE = "com.example.musicplayercursor.ACTION_PAUSE"
        const val ACTION_NEXT = "com.example.musicplayercursor.ACTION_NEXT"
        const val ACTION_PREV = "com.example.musicplayercursor.ACTION_PREV"
        const val ACTION_STOP = "com.example.musicplayercursor.ACTION_STOP"
    }

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSessionCompat? = null
    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    // Playback state
    private var currentSong: Song? = null
    private var playlist: List<Song> = emptyList()
    private var currentQueueSongIds: List<Long> = emptyList()
    private var currentQueueSource: String? = null
    private var currentIndex: Int = -1
    private var isLooping: Boolean = false
    
    // Receiver mode state
    private var isReceiverMode: Boolean = false
    private var receiverStreamUrl: String? = null
    private var broadcasterTargetPosition: Long? = null
    private var isBuffering: Boolean = false
    private var hasSetMediaItem: Boolean = false
    private var receiverProgressJob: Job? = null
    private var receiverPlaybackMonitorJob: Job? = null
    
    // Progress update job
    private var progressUpdateJob: Job? = null
    
    // StateFlow for playback state
    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()
    
    // Callback for notifying ViewModel of state changes (legacy support)
   // private var stateCallback: MusicServiceCallback? = null

    // Callback for MusicViewModel to handle notification actions
    private var viewModelActionCallback: ViewModelActionCallback? = null

    // Track last known position for MediaSession updates
    private var lastKnownPosition: Long = 0L
    private var lastKnownDuration: Long = 0L
    
    inner class LocalBinder : Binder() {
        fun getService(): MusicService = this@MusicService
    }

//    interface MusicServiceCallback {
//        fun onSongChanged(song: Song?)
//        fun onPlaybackStateChanged(isPlaying: Boolean)
//        fun onPositionChanged(position: Long, duration: Long)
//    }

    // Callback for MusicViewModel to handle notification actions
    interface ViewModelActionCallback {
        fun onPlayPauseRequested()
        fun onNextRequested()
        fun onPreviousRequested()
        fun onNextSongRequested(nextSongId: Long)
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")
        
        createNotificationChannel()
        initializePlayer()
        initializeMediaSession()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Music Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Controls for music playback"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun initializePlayer() {
        player = ExoPlayer.Builder(this).build().apply {
            val attrs = AudioAttributes.Builder()
                .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
                .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                .build()
            setAudioAttributes(attrs, true)
            
            addListener(object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    super.onMediaItemTransition(mediaItem, reason)
                    Log.d(TAG, "Media item transition: ${currentSong?.title}")
                    if (!isReceiverMode) {
                        updateMediaSessionMetadata()
                        updateNotification()
                       // stateCallback?.onSongChanged(currentSong)
                        updatePlaybackState()
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    super.onIsPlayingChanged(isPlaying)
                    Log.d(TAG, "Playback state changed: isPlaying=$isPlaying")
                    updateMediaSessionPlaybackState()
                    updateNotification()
                   // stateCallback?.onPlaybackStateChanged(isPlaying)
                    updatePlaybackState()
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    super.onPlaybackStateChanged(playbackState)
                    updateMediaSessionPlaybackState()
                    when (playbackState) {
                        Player.STATE_ENDED -> {
                            if (isReceiverMode) {
                                // Handle receiver mode end state
                                handleReceiverEndState()
                            } else {
                                if (isLooping && currentSong != null) {
                                    // Loop current song
                                    player?.seekTo(0)
                                    player?.play()
                                } else {
                                    // Play next song - use callback if available, otherwise try direct play
                                    if (viewModelActionCallback != null) {
                                        val nextSongId = getNextSongId()
                                        if (nextSongId != null) {
                                            viewModelActionCallback?.onNextSongRequested(nextSongId)
                                        }
                                    } else {
                                        playNext()
                                    }
                                }
                            }
                        }
                        Player.STATE_BUFFERING -> {
                            if (isReceiverMode) {
                                isBuffering = true
                                Log.w(TAG, "[Receiver Player] ⏳ Buffering...")
                            }
                        }
                        Player.STATE_READY -> {
                            if (isReceiverMode) {
                                handleReceiverReadyState()
                            }
                        }
                        Player.STATE_IDLE -> {
                            if (isReceiverMode && playWhenReady) {
                                serviceScope.launch {
                                    delay(100)
                                    Log.w(TAG, "[Receiver Player] Preparing player from IDLE state...")
                                    player?.prepare()
                                    delay(200)
                                    player?.playWhenReady = true
                                    player?.play()
                                }
                            }
                        }
                    }
                }
                
                override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int
                ) {
                    super.onPositionDiscontinuity(oldPosition, newPosition, reason)
                    updateMediaSessionPlaybackState()
                    updateNotification()
                    updatePlaybackState()
                }
                
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    Log.e(TAG, "!!! [Player] ERROR: ${error.message}", error)
                    if (isReceiverMode) {
                        Log.w(TAG, "[Receiver Player] Attempting recovery from error...")
                        player?.prepare()
                        player?.playWhenReady = true
                    }
                }
            })
        }
        
        // Start progress updates
        startProgressUpdates()
    }
    
    private fun handleReceiverEndState() {
        val duration = player?.duration ?: 0L
        val currentPos = player?.currentPosition ?: 0L
        val isNearEnd = duration > 0 && currentPos >= (duration - 5000)
        
        if (isNearEnd) {
            Log.d(TAG, "[Receiver Player] Near end of song, waiting for next song...")
        } else {
            Log.w(TAG, "[Receiver Player] Playback ended unexpectedly, reconnecting stream...")
            reconnectReceiverStream()
        }
    }
    
    private fun handleReceiverReadyState() {
        if (isBuffering && broadcasterTargetPosition != null) {
            val broadcasterActualPos = broadcasterTargetPosition!!
            val currentPos = player?.currentPosition ?: 0L
            val catchUpPosition = broadcasterActualPos + 100
            val drift = catchUpPosition - currentPos
            
            if (drift > 300) {
                serviceScope.launch {
                    delay(50)
                    player?.seekTo(catchUpPosition.coerceAtLeast(0L))
                    Log.d(TAG, "[Receiver Player] ✅ Caught up: seeked to ${catchUpPosition}ms")
                }
            }
            
            isBuffering = false
            broadcasterTargetPosition = null
        }
    }
    
    private fun reconnectReceiverStream() {
        val streamUrl = receiverStreamUrl ?: CurrentSongRepository.getReceiverStreamUrl()
        if (streamUrl == null) {
            Log.e(TAG, "!!! [reconnectReceiverStream] No stream URL available!")
            return
        }
        
        serviceScope.launch {
            try {
                Log.w(TAG, "[reconnectReceiverStream] Reconnecting to: $streamUrl")
                val p = player ?: return@launch
                
                val broadcasterActualPos = broadcasterTargetPosition ?: p.currentPosition.coerceAtLeast(0L)
                val catchUpPosition = broadcasterActualPos
                
                p.stop()
                p.clearMediaItems()
                isBuffering = true
                
                val newMediaItem = MediaItem.Builder()
                    .setUri(streamUrl)
                    .setLiveConfiguration(
                        androidx.media3.common.MediaItem.LiveConfiguration.Builder()
                            .setMaxPlaybackSpeed(1.05f)
                            .setMinPlaybackSpeed(0.95f)
                            .build()
                    )
                    .build()
                
                p.setMediaItem(newMediaItem)
                p.prepare()
                
                // Seek exactly to broadcaster-reported position (no artificial lead)
                p.seekTo(catchUpPosition.coerceAtLeast(0L))
                p.playWhenReady = true
                p.play()
                
                Log.d(TAG, "[reconnectReceiverStream] ✅ Stream reconnected at position ${catchUpPosition}ms")
            } catch (e: Exception) {
                Log.e(TAG, "!!! [reconnectReceiverStream] Error reconnecting", e)
                isBuffering = false
            }
        }
    }

    /**
     * Public wrapper to force re-connection of the receiver stream to the current `receiverStreamUrl`.
     * Used when broadcaster changes track to ensure ExoPlayer requests the new resource with proper Range.
     */
    fun reconnectAudioStream() {
        reconnectReceiverStream()
    }

    /**
     * Adjust receiver playback speed slightly to correct small lead/lag without seeking.
     */
    fun setReceiverPlaybackSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.95f, 1.05f)
        player?.setPlaybackParameters(androidx.media3.common.PlaybackParameters(clamped))
        updatePlaybackState()
    }
    
    private fun updatePlaybackState() {
        _playbackState.value = PlaybackState(
            currentSong = currentSong,
            isPlaying = player?.isPlaying ?: false,
            currentPosition = player?.currentPosition ?: 0L,
            duration = player?.duration?.coerceAtLeast(0L) ?: 0L,
            isReceiverMode = isReceiverMode,
            receiverStreamUrl = receiverStreamUrl,
            isLooping = isLooping
        )
    }
    
    private fun startProgressUpdates() {
        progressUpdateJob?.cancel()
        progressUpdateJob = serviceScope.launch {
            var lastSavedPosition = 0L
            var lastMediaSessionUpdate = 0L
            while (isActive) {
                delay(100)
                val p = player ?: continue
                
                val currentPos = p.currentPosition
                val duration = p.duration.coerceAtLeast(0L)
                
                if (duration > 0) {
                    updatePlaybackState()
                    
                    // Update MediaSession playback state every 500ms for smooth progress bar
                    if (kotlin.math.abs(currentPos - lastMediaSessionUpdate) >= 500) {
                        updateMediaSessionPlaybackState()
                        lastMediaSessionUpdate = currentPos
                    }
                    
                    // Save position every 1 second (10 updates)
                    if (kotlin.math.abs(currentPos - lastSavedPosition) >= 1000 && !isReceiverMode) {
                        currentSong?.let { song ->
                            CurrentSongRepository.saveCurrentSong(
                                songId = song.id,
                                position = currentPos,
                                isPlaying = p.isPlaying,
                                isReceiverMode = false
                            )
                        }
                        lastSavedPosition = currentPos
                    }
                }
            }
        }
    }

    private fun initializeMediaSession() {
        mediaSession = MediaSessionCompat(this, TAG).apply {
            isActive = true
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    Log.d(TAG, "[MediaSession] onPlay called")
                    if (viewModelActionCallback != null) {
                        viewModelActionCallback?.onPlayPauseRequested()
                    } else {
                        togglePlayPause()
                    }
                }
                override fun onPause() {
                    Log.d(TAG, "[MediaSession] onPause called")
                    if (viewModelActionCallback != null) {
                        viewModelActionCallback?.onPlayPauseRequested()
                    } else {
                        togglePlayPause()
                    }
                }
                override fun onSkipToNext() {
                    Log.d(TAG, "[MediaSession] onSkipToNext called")
                    if (viewModelActionCallback != null) {
                        viewModelActionCallback?.onNextRequested()
                    } else {
                        playNext()
                    }
                }
                override fun onSkipToPrevious() {
                    Log.d(TAG, "[MediaSession] onSkipToPrevious called")
                    if (viewModelActionCallback != null) {
                        viewModelActionCallback?.onPreviousRequested()
                    } else {
                        playPrevious()
                    }
                }
                override fun onSeekTo(pos: Long) {
                    Log.d(TAG, "[MediaSession] onSeekTo called: $pos")
                    if (isReceiverMode) {
                        seekToReceiver(pos)
                    } else {
                        seekTo(pos)
                    }
                }
            })
        }
    }

    private fun updateMediaSessionMetadata() {
        val song = currentSong ?: return
        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, song.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, song.artist)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, song.durationMs)
            .build()
        mediaSession?.setMetadata(metadata)
    }

    private fun updateMediaSessionPlaybackState() {
        val state = if (player?.isPlaying == true) 
            PlaybackStateCompat.STATE_PLAYING 
        else 
            PlaybackStateCompat.STATE_PAUSED
            
        val playbackState = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_SEEK_TO
            )
            .setState(
                state,
                player?.currentPosition ?: 0L,
                player?.playbackParameters?.speed ?: 1f
            )
            .build()
        mediaSession?.setPlaybackState(playbackState)
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.d(TAG, "Service onBind")
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service onStartCommand: ${intent?.action}")

        when (intent?.action) {
            ACTION_PLAY, ACTION_PAUSE -> {
                // If callback exists, forward to ViewModel; otherwise use MusicService's own player
                if (viewModelActionCallback != null) {
                    Log.d(TAG, "[onStartCommand] Forwarding play/pause to ViewModel")
                    viewModelActionCallback?.onPlayPauseRequested()
                } else {
                    togglePlayPause()
                }
            }
            ACTION_NEXT -> {
                if (viewModelActionCallback != null) {
                    Log.d(TAG, "[onStartCommand] Forwarding next to ViewModel")
                    viewModelActionCallback?.onNextRequested()
                } else {
                    playNext()
                }
            }
            ACTION_PREV -> {
                if (viewModelActionCallback != null) {
                    Log.d(TAG, "[onStartCommand] Forwarding previous to ViewModel")
                    viewModelActionCallback?.onPreviousRequested()
                } else {
                    playPrevious()
                }
            }
            ACTION_STOP -> {
                stopForeground(true)
                stopSelf()
            }
        }

        return START_STICKY
    }

//    fun setCallback(callback: MusicServiceCallback?) {
//        stateCallback = callback
//    }

    fun setViewModelActionCallback(callback: ViewModelActionCallback?) {
        viewModelActionCallback = callback
        Log.d(TAG, "[setViewModelActionCallback] Callback set: ${callback != null}")
    }

    fun setLooping(enabled: Boolean) {
        isLooping = enabled
        Log.d(TAG, "Looping set to: $enabled")
        updatePlaybackState()
    }

    fun getCurrentPosition(): Long = player?.currentPosition ?: 0L
    
    fun getDuration(): Long = player?.duration?.coerceAtLeast(0L) ?: 0L
    
    fun isPlaying(): Boolean = player?.isPlaying ?: false
    
    fun getCurrentSong(): Song? = currentSong
    
    // ========== Receiver Mode Methods ==========
    
    /**
     * Connect to broadcast stream
     */
    fun connectToBroadcast(streamUrl: String) {
        Log.d(TAG, ">>> [connectToBroadcast] Entry: streamUrl=$streamUrl")
        serviceScope.launch {
            try {
                Log.d(TAG, "[connectToBroadcast] Starting connection to: $streamUrl")
                val oldReceiverMode = isReceiverMode
                isReceiverMode = true
                receiverStreamUrl = streamUrl
                hasSetMediaItem = false
                Log.d(TAG, "[connectToBroadcast] Receiver mode enabled: $oldReceiverMode -> $isReceiverMode")
                
                val mediaItem = MediaItem.Builder()
                    .setUri(streamUrl)
                    .setLiveConfiguration(
                        androidx.media3.common.MediaItem.LiveConfiguration.Builder()
                            .setMaxPlaybackSpeed(1.05f)
                            .setMinPlaybackSpeed(0.95f)
                            .build()
                    )
                    .build()
                
                player?.apply {
                    if (!hasSetMediaItem) {
                        setMediaItem(mediaItem)
                        hasSetMediaItem = true
                    } else {
                        setMediaItem(mediaItem)
                    }
                    
                    repeatMode = androidx.media3.common.Player.REPEAT_MODE_OFF
                    prepare()
                    playWhenReady = true
                    play()
                } ?: run {
                    Log.e(TAG, "!!! [connectToBroadcast] Error: player is null!")
                    return@launch
                }
                
                CurrentSongRepository.saveCurrentSong(
                    songId = null,
                    position = 0L,
                    isPlaying = true,
                    isReceiverMode = true,
                    receiverStreamUrl = streamUrl
                )
                
                hideNotification()
                startReceiverProgressUpdates()
                startReceiverPlaybackMonitor()
                updatePlaybackState()
                
                Log.i(TAG, "<<< [connectToBroadcast] Success: CONNECTED & SYNC STARTED!")
            } catch (e: Exception) {
                Log.e(TAG, "!!! [connectToBroadcast] Error connecting to broadcast", e)
                isReceiverMode = false
                receiverStreamUrl = null
                updatePlaybackState()
            }
        }
    }
    
    /**
     * Disconnect from broadcast
     */
    fun disconnectFromBroadcast() {
        Log.d(TAG, ">>> [disconnectFromBroadcast] Entry")
        val oldReceiverMode = isReceiverMode
        isReceiverMode = false
        receiverStreamUrl = null
        
        receiverPlaybackMonitorJob?.cancel()
        receiverPlaybackMonitorJob = null
        
        receiverProgressJob?.cancel()
        receiverProgressJob = null
        
        hasSetMediaItem = false
        
        player?.apply {
            stop()
            clearMediaItems()
        }
        
        CurrentSongRepository.saveCurrentSong(
            songId = null,
            position = 0L,
            isPlaying = false,
            isReceiverMode = false
        )
        
        broadcasterTargetPosition = null
        isBuffering = false
        
        // Show notification again if there's a local song playing
        currentSong?.let { song ->
            showNotificationForViewModel(song, player?.isPlaying ?: false)
        }
        
        updatePlaybackState()
        Log.i(TAG, "<<< [disconnectFromBroadcast] Success: Disconnected from broadcast")
    }
    
    /**
     * Seek receiver player
     */
    fun seekToReceiver(positionMs: Long) {
        val targetPosition = positionMs.coerceAtLeast(0L)
        val currentPos = player?.currentPosition ?: 0L
        Log.d(TAG, "[seekToReceiver] Seeking: ${currentPos}ms -> ${targetPosition}ms")
        player?.seekTo(targetPosition)
        updatePlaybackState()
        updateMediaSessionPlaybackState() // Immediately update MediaSession for notification progress bar
    }
    
    /**
     * Play receiver
     */
    fun playReceiver() {
        val p = player ?: run {
            Log.e(TAG, "!!! [playReceiver] Player is null!")
            return
        }
        
        val playbackState = p.playbackState
        val isReady = playbackState == androidx.media3.common.Player.STATE_READY ||
                playbackState == androidx.media3.common.Player.STATE_BUFFERING
        
        if (playbackState == androidx.media3.common.Player.STATE_ENDED) {
            Log.w(TAG, "[playReceiver] Player is in ENDED state, reconnecting stream...")
            reconnectReceiverStream()
            return
        }
        
        if (!isReady && playbackState == androidx.media3.common.Player.STATE_IDLE) {
            p.prepare()
        }
        
        p.playWhenReady = true
        p.play()
        updatePlaybackState()
    }
    
    /**
     * Pause receiver
     */
    fun pauseReceiver() {
        val p = player ?: return
        p.playWhenReady = false
        p.pause()
        updatePlaybackState()
    }
    
    /**
     * Check if receiver is playing
     */
    fun isReceiverPlaying(): Boolean {
        return player?.isPlaying ?: false
    }
    
    /**
     * Get receiver current position
     */
    fun getReceiverPosition(): Long {
        return player?.currentPosition ?: 0L
    }
    
    /**
     * Get receiver duration
     */
    fun getReceiverDuration(): Long {
        return player?.duration?.coerceAtLeast(0L) ?: 0L
    }
    
    /**
     * Check if in receiver mode
     */
    fun isInReceiverMode(): Boolean {
        return isReceiverMode
    }
    
    /**
     * Update broadcaster target position (for catch-up after buffering)
     */
    fun updateBroadcasterTargetPosition(positionMs: Long) {
        if (isReceiverMode) {
            broadcasterTargetPosition = positionMs
            Log.d(TAG, "[updateBroadcasterTargetPosition] Updated target: ${positionMs}ms")
        }
    }
    
    private fun startReceiverProgressUpdates() {
        receiverProgressJob?.cancel()
        receiverProgressJob = serviceScope.launch {
            while (isActive && isReceiverMode && player != null) {
                delay(100)
                val p = player ?: break
                val currentPos = p.currentPosition
                val duration = p.duration.coerceAtLeast(0L)
                
                if (duration > 0L) {
                    updatePlaybackState()
                }
            }
        }
    }
    
    private fun startReceiverPlaybackMonitor() {
        receiverPlaybackMonitorJob?.cancel()
        receiverPlaybackMonitorJob = serviceScope.launch {
            var consecutiveStoppedChecks = 0
            while (isActive && isReceiverMode && player != null) {
                delay(500)
                val p = player ?: break
                val playbackState = p.playbackState
                val shouldBePlaying = p.playWhenReady
                val isActuallyPlaying = p.isPlaying
                
                if (shouldBePlaying && !isActuallyPlaying) {
                    consecutiveStoppedChecks++
                    if (consecutiveStoppedChecks >= 2) {
                        when (playbackState) {
                            androidx.media3.common.Player.STATE_IDLE -> {
                                p.prepare()
                                delay(200)
                                p.playWhenReady = true
                                p.play()
                            }
                            androidx.media3.common.Player.STATE_ENDED -> {
                                reconnectReceiverStream()
                            }
                            androidx.media3.common.Player.STATE_BUFFERING -> {
                                p.playWhenReady = true
                                p.play()
                            }
                            else -> {
                                p.playWhenReady = true
                                p.play()
                            }
                        }
                        consecutiveStoppedChecks = 0
                    }
                } else if (isActuallyPlaying) {
                    consecutiveStoppedChecks = 0
                }
            }
        }
    }
    
    // ========== Local Playback Methods (Refactored) ==========
    
    /**
     * Play a song with queue context
     */
    fun play(song: Song, queueIds: List<Long>? = null, source: String? = null) {
        if (isReceiverMode) {
            Log.d(TAG, "[play] BLOCKED: Cannot play local song while in receiver mode")
            return
        }
        
        if (queueIds != null) {
            currentQueueSongIds = queueIds
            currentQueueSource = source
        }
        
        Log.d(TAG, "play: ${song.title}")
        PlayCountRepository.incrementPlayCount(song.id)
        LastPlayedRepository.setLastPlayed(song.id, System.currentTimeMillis())
        
        currentSong = song
        
        val index = if (queueIds != null) queueIds.indexOf(song.id) else -1
        if (index >= 0) {
            currentIndex = index
        }
        
        player?.apply {
            stop()
            clearMediaItems()
            setMediaItem(MediaItem.fromUri(song.contentUri))
            prepare()
            play()
        }
        
        startForeground(NOTIFICATION_ID, createNotification())
        updateMediaSessionMetadata()
        updateMediaSessionPlaybackState()
//        stateCallback?.onSongChanged(song)
//        stateCallback?.onPlaybackStateChanged(true)
//
        CurrentSongRepository.saveCurrentSong(
            songId = song.id,
            position = 0L,
            isPlaying = true,
            isReceiverMode = false
        )
        
        updatePlaybackState()
    }
    
    /**
     * Play from queue
     */
    fun playFromQueue(queueIds: List<Long>?, startSongId: Long, source: String?) {
        if (isReceiverMode) {
            Log.d(TAG, "[playFromQueue] BLOCKED: Cannot play local song while in receiver mode")
            return
        }
        
        if (queueIds != null) {
            currentQueueSongIds = queueIds
            currentQueueSource = source
        }
        
        // Find song in current songs list (would need to be passed or accessed differently)
        // For now, this is a placeholder - the actual song lookup should be done by ViewModel
        Log.d(TAG, "[playFromQueue] Called with songId=$startSongId, source=$source")
    }
    
    /**
     * Toggle play/pause
     */
    fun togglePlayPause() {
        if (isReceiverMode) {
            Log.d(TAG, "[togglePlayPause] BLOCKED: Cannot toggle local playback while in receiver mode")
            return
        }
        
        player?.let { p ->
            if (p.isPlaying) {
                p.pause()
              //  stateCallback?.onPlaybackStateChanged(false)
            } else {
                p.play()
               // stateCallback?.onPlaybackStateChanged(true)
            }
            updateNotification()
            updatePlaybackState()
            
            currentSong?.let { song ->
                CurrentSongRepository.saveCurrentSong(
                    songId = song.id,
                    position = p.currentPosition,
                    isPlaying = p.isPlaying,
                    isReceiverMode = false
                )
            }
        }
    }
    
    /**
     * Play next song
     */
    fun playNext() {
        if (isReceiverMode) {
            Log.d(TAG, "[playNext] BLOCKED: Cannot play next song while in receiver mode")
            return
        }
        
        if (currentQueueSongIds.isEmpty() || currentIndex < 0) {
            Log.w(TAG, "No queue or invalid index")
            return
        }
        
        val nextIndex = if (currentIndex < currentQueueSongIds.size - 1) {
            currentIndex + 1
        } else {
            0 // Loop back to first
        }
        
        val nextId = currentQueueSongIds.getOrNull(nextIndex)
        if (nextId != null) {
            // Try to use callback first, otherwise log
            if (viewModelActionCallback != null) {
                Log.d(TAG, "[playNext] Requesting ViewModel to play next song ID: $nextId")
                // We need to add a method to the callback interface for this
                // For now, we'll use onNextRequested which should trigger next song in ViewModel
                viewModelActionCallback?.onNextRequested()
            } else {
                Log.w(TAG, "[playNext] Next song ID: $nextId but no callback available to play it")
            }
        }
    }
    
    /**
     * Play previous song
     */
    fun playPrevious() {
        if (isReceiverMode) {
            Log.d(TAG, "[playPrevious] BLOCKED: Cannot play previous song while in receiver mode")
            return
        }
        
        if (currentQueueSongIds.isEmpty() || currentIndex <= 0) {
            Log.w(TAG, "No queue or at first song")
            return
        }
        
        val prevIndex = currentIndex - 1
        val prevId = currentQueueSongIds.getOrNull(prevIndex)
        if (prevId != null) {
            // Would need song lookup - this is handled by ViewModel
            Log.d(TAG, "[playPrevious] Previous song ID: $prevId")
        }
    }
    
    /**
     * Seek to position
     */
    fun seekTo(positionMs: Long) {
        if (isReceiverMode) {
            Log.d(TAG, "[seekTo] BLOCKED: Cannot seek local playback while in receiver mode")
            return
        }
        
        val targetPosition = positionMs.coerceIn(0L, player?.duration?.coerceAtLeast(0L) ?: 0L)
        player?.seekTo(targetPosition)
        updatePlaybackState()
        updateMediaSessionPlaybackState() // Immediately update MediaSession for notification progress bar
    }

    /**
     * Show notification for MusicViewModel's playback
     * This allows MusicViewModel to use its own player while MusicService handles notifications
     */
    fun showNotificationForViewModel(song: Song, isPlaying: Boolean) {
        Log.d(TAG, "[showNotificationForViewModel] Showing notification for: ${song.title}, isPlaying=$isPlaying")
        currentSong = song  // Store song info for notification

        // Initialize position (will be updated by progress updates)
        lastKnownPosition = 0L
        lastKnownDuration = song.durationMs

        // Start as foreground service and show notification
        startForeground(NOTIFICATION_ID, createNotificationForViewModel(song, isPlaying))
        updateMediaSessionMetadataForViewModel(song, isPlaying)

        // Set initial MediaSession state with position 0
        val state = if (isPlaying)
            PlaybackStateCompat.STATE_PLAYING
        else
            PlaybackStateCompat.STATE_PAUSED

        val playbackState = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_SEEK_TO
            )
            .setState(state, 0L, 1f)
            .build()
        mediaSession?.setPlaybackState(playbackState)
    }

    /**
     * Update notification for MusicViewModel's playback state
     */
    fun updateNotificationForViewModel(isPlaying: Boolean) {
        val song = currentSong ?: return
        Log.d(TAG, "[updateNotificationForViewModel] Updating notification, isPlaying=$isPlaying")

        // Update MediaSession state WITHOUT changing position (preserve lastKnownPosition)
        val state = if (isPlaying)
            PlaybackStateCompat.STATE_PLAYING
        else
            PlaybackStateCompat.STATE_PAUSED

        val playbackState = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_SEEK_TO
            )
            .setState(state, lastKnownPosition, 1f)  // Preserve position
            .build()
        mediaSession?.setPlaybackState(playbackState)

        // Update notification UI only
        val notification = createNotificationForViewModel(song, isPlaying)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * Update notification and MediaSession with current playback position
     */
    fun updateNotificationWithPosition(isPlaying: Boolean, position: Long, duration: Long) {
        val song = currentSong ?: return
        Log.d(TAG, "[updateNotificationWithPosition] Updating: isPlaying=$isPlaying, pos=$position/$duration")

        // Store position for later use
        lastKnownPosition = position
        lastKnownDuration = duration

        // Update MediaSession with position for progress bar
        val state = if (isPlaying)
            PlaybackStateCompat.STATE_PLAYING
        else
            PlaybackStateCompat.STATE_PAUSED

        val playbackState = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_SEEK_TO
            )
            .setState(state, position, 1f)
            .build()
        mediaSession?.setPlaybackState(playbackState)

        // Update notification
        val notification = createNotificationForViewModel(song, isPlaying)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * Create notification for MusicViewModel (doesn't use MusicService's player state)
     */
    private fun createNotificationForViewModel(song: Song, isPlaying: Boolean): Notification {
        val title = song.title
        val artist = song.artist

        // Intent to open app when notification is clicked
        val contentIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action intents - these will call back to MusicViewModel via broadcast
        val playPauseIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, MusicService::class.java).apply { action = ACTION_PLAY },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val nextIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, MusicService::class.java).apply { action = ACTION_NEXT },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val prevIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, MusicService::class.java).apply { action = ACTION_PREV },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Determine play/pause icon based on isPlaying parameter
        val playPauseIcon = if (isPlaying) {
            android.R.drawable.ic_media_pause
        } else {
            android.R.drawable.ic_media_play
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(artist)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(contentPendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setOngoing(true)  // Keep notification visible while playing
            .addAction(android.R.drawable.ic_media_previous, "Previous", prevIntent)
            .addAction(playPauseIcon, if (isPlaying) "Pause" else "Play", playPauseIntent)
            .addAction(android.R.drawable.ic_media_next, "Next", nextIntent)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession?.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    private fun updateMediaSessionMetadataForViewModel(song: Song, isPlaying: Boolean) {
        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, song.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, song.artist)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, song.durationMs)
            .build()
        mediaSession?.setMetadata(metadata)
        updateMediaSessionPlaybackStateForViewModel(isPlaying)
    }

    private fun updateMediaSessionPlaybackStateForViewModel(isPlaying: Boolean) {
        val state = if (isPlaying)
            PlaybackStateCompat.STATE_PLAYING
        else
            PlaybackStateCompat.STATE_PAUSED

        val playbackState = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_SEEK_TO
            )
            .setState(state, lastKnownPosition, 1f)  // Use lastKnownPosition instead of 0L
            .build()
        mediaSession?.setPlaybackState(playbackState)
    }

    /**
     * Hide notification when playback stops
     */
    fun hideNotification() {
        Log.d(TAG, "[hideNotification] Hiding notification")
        stopForeground(true)
        currentSong = null
    }


    private fun createNotification(): Notification {
        val song = currentSong
        val title = song?.title ?: "No song playing"
        val artist = song?.artist ?: "Unknown artist"
        
        // Intent to open app when notification is clicked
        val contentIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        // Action intents
        val playPauseIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, MusicService::class.java).apply { action = ACTION_PLAY },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val nextIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, MusicService::class.java).apply { action = ACTION_NEXT },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val prevIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, MusicService::class.java).apply { action = ACTION_PREV },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        // Determine play/pause icon
        val playPauseIcon = if (player?.isPlaying == true) {
            android.R.drawable.ic_media_pause
        } else {
            android.R.drawable.ic_media_play
        }
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(artist)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(contentPendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_media_previous, "Previous", prevIntent)
            .addAction(playPauseIcon, "Play/Pause", playPauseIntent)
            .addAction(android.R.drawable.ic_media_next, "Next", nextIntent)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession?.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    private fun updateNotification() {
        val notification = createNotification()
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service onDestroy")
        
        progressUpdateJob?.cancel()
        receiverProgressJob?.cancel()
        receiverPlaybackMonitorJob?.cancel()
        
        mediaSession?.release()
        mediaSession = null
        
        player?.release()
        player = null
        
      //  stateCallback = null
        viewModelActionCallback = null
    }

    /**
     * Get next song ID from queue
     */
    private fun getNextSongId(): Long? {
        if (currentQueueSongIds.isEmpty() || currentIndex < 0) {
            return null
        }
        
        val nextIndex = if (currentIndex < currentQueueSongIds.size - 1) {
            currentIndex + 1
        } else {
            0 // Loop back to first
        }
        
        return currentQueueSongIds.getOrNull(nextIndex)
    }
}

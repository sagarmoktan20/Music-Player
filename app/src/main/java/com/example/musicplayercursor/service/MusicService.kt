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
import com.example.musicplayercursor.repository.SongRepository
import com.example.musicplayercursor.repository.QueueRepository
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
    // Queue is now managed by QueueRepository
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
    // Removed ViewModelActionCallback as it is no longer used

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

    // Removed ViewModelActionCallback as we now use Repository pattern
    // private var viewModelActionCallback: ViewModelActionCallback? = null

    override fun onCreate() {
        super.onCreate()
        Log.d("FirstPlay", "MusicService: onCreate called")
        Log.d(TAG, "Service onCreate")

        // Initialize Repositories
        // Do not pre-load songs here; permissions may not be granted yet
        CurrentSongRepository.init(applicationContext)
        // QueueRepository is an object, so it's initialized on access

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
        Log.d("FirstPlay", "MusicService: initializePlayer called")
        player = ExoPlayer.Builder(this).build().apply {
            val attrs = AudioAttributes.Builder()
                .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
                .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                .build()
            setAudioAttributes(attrs, true)

            addListener(object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    super.onMediaItemTransition(mediaItem, reason)
                    Log.d("FirstPlay", "MusicService: onMediaItemTransition reason=$reason")
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
                    Log.d("FirstPlay", "MusicService: onIsPlayingChanged isPlaying=$isPlaying")
                    Log.d(TAG, "Playback state changed: isPlaying=$isPlaying")
                    updateMediaSessionPlaybackState()
                    updateNotification()
                    // stateCallback?.onPlaybackStateChanged(isPlaying)
                    updatePlaybackState()
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    super.onPlaybackStateChanged(playbackState)
                    Log.d("FirstPlay", "MusicService: onPlaybackStateChanged state=$playbackState")
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
                                    // Play next song using Repository
                                    playNext()
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
                    Log.d("FirstPlay", "MusicService: onPlayerError ${error.message}")
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
        if (isBuffering) {
             isBuffering = false
             broadcasterTargetPosition = null
             Log.d(TAG, "[Receiver Player] Ready state reached, buffering complete")
        }
        /*
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
        */
    }

    private fun reconnectReceiverStream(startPosition: Long? = null) {
        val streamUrl = receiverStreamUrl ?: CurrentSongRepository.getReceiverStreamUrl()
        if (streamUrl == null) {
            Log.e(TAG, "!!! [reconnectReceiverStream] No stream URL available!")
            return
        }

        serviceScope.launch {
            try {
                Log.w(TAG, "[reconnectReceiverStream] Reconnecting to: $streamUrl with startPos=$startPosition")
                val p = player ?: return@launch

                // Use provided start position, or broadcaster target, or current position
                val catchUpPosition = startPosition ?: broadcasterTargetPosition ?: p.currentPosition.coerceAtLeast(0L)

                p.stop()
                p.clearMediaItems()
                isBuffering = true

                val newMediaItem = MediaItem.Builder()
                    .setUri(streamUrl)
                    // Removed LiveConfiguration to prevent ExoPlayer from attempting internal live sync
                    // We handle sync manually in ConnectViewModel
                    .build()

                p.setMediaItem(newMediaItem)
                p.prepare()

                // Seek exactly to target position
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
        // Force start at 0 on explicit reconnect (song change)
        reconnectReceiverStream(0L)
    }

    /**
     * Adjust receiver playback speed slightly to correct small lead/lag without seeking.
     */
    fun setReceiverPlaybackSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.95f, 1.05f)
        player?.setPlaybackParameters(androidx.media3.common.PlaybackParameters(clamped))
        runCatching {
            val pos = player?.currentPosition ?: 0L
            android.util.Log.d("receivertime", "speedChange=" + clamped + " pos=" + pos + "ms")
        }
        updatePlaybackState()
    }

    fun getReceiverSpeed(): Float {
        return player?.playbackParameters?.speed ?: 1.0f
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
                    togglePlayPause()
                }
                override fun onPause() {
                    Log.d(TAG, "[MediaSession] onPause called")
                    togglePlayPause()
                }
                override fun onSkipToNext() {
                    Log.d(TAG, "[MediaSession] onSkipToNext called")
                    playNext()
                }
                override fun onSkipToPrevious() {
                    Log.d(TAG, "[MediaSession] onSkipToPrevious called")
                    playPrevious()
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
        Log.d("FirstPlay", "MusicService: onBind called")
        Log.d(TAG, "Service onBind")
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("FirstPlay", "MusicService: onStartCommand called")
        val action = intent?.action
        Log.d(TAG, "onStartCommand: action=$action")

        when (action) {
            ACTION_PLAY -> {
                if (player?.isPlaying == false) {
                    player?.play()
                } else if (player?.playbackState == androidx.media3.common.Player.STATE_IDLE) {
                    // If player is idle but we have a current song, try to reload it
                    currentSong?.let {
                        Log.d(TAG, "onStartCommand: Player idle, reloading current song")
                        play(it, QueueRepository.queue.value, "restored")
                    }
                }
            }
            ACTION_PAUSE -> player?.pause()
            ACTION_NEXT -> playNext()
            ACTION_PREV -> playPrevious()
            ACTION_STOP -> {
                player?.stop()
                stopSelf()
            }
        }

        return START_STICKY
    }

//    fun setCallback(callback: MusicServiceCallback?) {
//        stateCallback = callback
//    }

//    fun setViewModelActionCallback(callback: ViewModelActionCallback?) {
//        viewModelActionCallback = callback
//        Log.d(TAG, "[setViewModelActionCallback] Callback set: ${callback != null}")
//    }

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
            updateNotification()
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
    fun play(song: Song, queueIds: List<Long>? = null, source: String? = null, startPosition: Long = 0L) {
        Log.d("FirstPlay", "MusicService: play() called for ${song.title}, queueSize: ${queueIds?.size}, source: $source, startPos: $startPosition")
        if (isReceiverMode) {
            Log.d(TAG, "[play] BLOCKED: Cannot play local song while in receiver mode")
            Log.d("FirstPlay", "MusicService: play() BLOCKED due to Receiver Mode")
            return
        }

        if (queueIds != null) {
            QueueRepository.setQueue(queueIds)
            Log.d("FirstPlay", "MusicService: play() Updated queue ids: ${queueIds.size}")
        }

        Log.d(TAG, "play: ${song.title} starting at $startPosition")
        PlayCountRepository.incrementPlayCount(song.id)
        LastPlayedRepository.setLastPlayed(song.id, System.currentTimeMillis())

        currentSong = song
        QueueRepository.setCurrentSongId(song.id)

        val currentQueue = QueueRepository.queue.value
        val index = currentQueue.indexOf(song.id)
        if (index >= 0) {
            currentIndex = index
        }

        player?.apply {
            Log.d("FirstPlay", "MusicService: play() Preparing player")
            stop()
            clearMediaItems()
            setMediaItem(MediaItem.fromUri(song.contentUri), startPosition)
            prepare()
            play()
            Log.d("FirstPlay", "MusicService: play() Player prepared and play() called with startPosition: $startPosition")
        }

        startForeground(NOTIFICATION_ID, createNotification())
        updateMediaSessionMetadata()
        updateMediaSessionPlaybackState()
//        stateCallback?.onSongChanged(song)
//        stateCallback?.onPlaybackStateChanged(true)
//
        CurrentSongRepository.saveCurrentSong(
            songId = song.id,
            position = startPosition,
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
            QueueRepository.setQueue(queueIds)
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

        val currentId = currentSong?.id ?: return
        val nextId = QueueRepository.getNextSongId(currentId)

        if (nextId != null) {
            val nextSong = SongRepository.getSongById(nextId)
            if (nextSong != null) {
                Log.d(TAG, "[playNext] Playing next song: ${nextSong.title}")
                play(nextSong, null, null)
            } else {
                Log.w(TAG, "[playNext] Next song ID $nextId found in queue but song details missing")
            }
        } else {
            Log.d(TAG, "[playNext] No next song in queue")
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

        val currentId = currentSong?.id ?: return
        val prevId = QueueRepository.getPreviousSongId(currentId)

        if (prevId != null) {
            val prevSong = SongRepository.getSongById(prevId)
            if (prevSong != null) {
                Log.d(TAG, "[playPrevious] Playing previous song: ${prevSong.title}")
                play(prevSong, null, null)
            } else {
                Log.w(TAG, "[playPrevious] Previous song ID $prevId found in queue but song details missing")
            }
        } else {
            Log.d(TAG, "[playPrevious] No previous song in queue")
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
    }
}

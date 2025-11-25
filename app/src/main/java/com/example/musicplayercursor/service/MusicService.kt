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
import com.example.musicplayercursor.repository.LastPlayedRepository
import com.example.musicplayercursor.repository.PlayCountRepository

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
    
    // Playback state
    private var currentSong: Song? = null
    private var playlist: List<Song> = emptyList()
    private var currentIndex: Int = -1
    private var isLooping: Boolean = false
    
    // Callback for notifying ViewModel of state changes
    // Callback for notifying ViewModel of state changes
    private var stateCallback: MusicServiceCallback? = null

    // Callback for MusicViewModel to handle notification actions
    private var viewModelActionCallback: ViewModelActionCallback? = null

    // Callback for MusicViewModel to handle notification actions
  //  private var viewModelActionCallback: ViewModelActionCallback? = null

    // Track last known position for MediaSession updates
    private var lastKnownPosition: Long = 0L
    private var lastKnownDuration: Long = 0L
    inner class LocalBinder : Binder() {
        fun getService(): MusicService = this@MusicService
    }

    interface MusicServiceCallback {
        fun onSongChanged(song: Song?)
        fun onPlaybackStateChanged(isPlaying: Boolean)
        fun onPositionChanged(position: Long, duration: Long)
    }

    // Callback for MusicViewModel to handle notification actions
    interface ViewModelActionCallback {
        fun onPlayPauseRequested()
        fun onNextRequested()
        fun onPreviousRequested()
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
                    updateMediaSessionMetadata()
                    updateNotification()
                    stateCallback?.onSongChanged(currentSong)
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    super.onIsPlayingChanged(isPlaying)
                    Log.d(TAG, "Playback state changed: isPlaying=$isPlaying")
                    updateMediaSessionPlaybackState()
                    updateNotification()
                    stateCallback?.onPlaybackStateChanged(isPlaying)
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    super.onPlaybackStateChanged(playbackState)
                    updateMediaSessionPlaybackState()
                    when (playbackState) {
                        Player.STATE_ENDED -> {
                            if (isLooping && currentSong != null) {
                                // Loop current song
                                player?.seekTo(0)
                                player?.play()
                            } else {
                                // Play next song
                                playNext()
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
                }
            })
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
                    // For now, we can't seek from notification - would need callback
                    // seekTo(pos)
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

    fun setCallback(callback: MusicServiceCallback?) {
        stateCallback = callback
    }

    fun setViewModelActionCallback(callback: ViewModelActionCallback?) {
        viewModelActionCallback = callback
        Log.d(TAG, "[setViewModelActionCallback] Callback set: ${callback != null}")
    }

    fun playPlaylist(songs: List<Song>, startIndex: Int) {
      //  Log.d(TAG, "playPlaylist: ${songs.size} songs, startIndex=$startIndex")
        Log.d("QueueDebug", "Queue order (Service): ${songs.map { it.playCount }}")

        if (songs.isEmpty() || startIndex < 0 || startIndex >= songs.size) {
            Log.w(TAG, "Invalid playlist or index")
            return
        }
        
        playlist = songs
        currentIndex = startIndex
        currentSong = songs[startIndex]
        
        playSong(songs[startIndex])
    }

    fun playSong(song: Song) {
        Log.d(TAG, "playSong: ${song.title}")
        PlayCountRepository.incrementPlayCount(song.id)
        LastPlayedRepository.setLastPlayed(song.id, System.currentTimeMillis())


        currentSong = song
        
        // Update current index if song is in playlist
        val index = playlist.indexOfFirst { it.id == song.id }
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
        stateCallback?.onSongChanged(song)
        stateCallback?.onPlaybackStateChanged(true)
    }

    fun togglePlayPause() {
        player?.let { p ->
            if (p.isPlaying) {
                p.pause()
                stateCallback?.onPlaybackStateChanged(false)
            } else {
                p.play()
                stateCallback?.onPlaybackStateChanged(true)
            }
            updateNotification()
        }
    }

    fun playNext() {
        if (playlist.isEmpty() || currentIndex < 0) {
            Log.w(TAG, "No playlist or invalid index")
            return
        }
        
        if (currentIndex < playlist.size - 1) {
            currentIndex++
            playSong(playlist[currentIndex])
        } else {
            Log.d(TAG, "Already at last song")
        }
    }

    fun playPrevious() {
        if (playlist.isEmpty() || currentIndex < 0) {
            Log.w(TAG, "No playlist or invalid index")
            return
        }
        
        if (currentIndex > 0) {
            currentIndex--
            playSong(playlist[currentIndex])
        } else {
            Log.d(TAG, "Already at first song")
        }
    }

    fun seekTo(positionMs: Long) {
        player?.seekTo(positionMs.coerceIn(0L, player?.duration?.coerceAtLeast(0L) ?: 0L))
    }

    fun setLooping(enabled: Boolean) {
        isLooping = enabled
        Log.d(TAG, "Looping set to: $enabled")
    }

    fun getCurrentPosition(): Long = player?.currentPosition ?: 0L
    
    fun getDuration(): Long = player?.duration?.coerceAtLeast(0L) ?: 0L
    
    fun isPlaying(): Boolean = player?.isPlaying ?: false
    
    fun getCurrentSong(): Song? = currentSong

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
        
        mediaSession?.release()
        mediaSession = null
        
        player?.release()
        player = null
        
        stateCallback = null
    }
}

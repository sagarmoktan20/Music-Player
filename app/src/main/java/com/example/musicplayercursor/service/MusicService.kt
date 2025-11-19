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
    private var stateCallback: MusicServiceCallback? = null

    inner class LocalBinder : Binder() {
        fun getService(): MusicService = this@MusicService
    }

    interface MusicServiceCallback {
        fun onSongChanged(song: Song?)
        fun onPlaybackStateChanged(isPlaying: Boolean)
        fun onPositionChanged(position: Long, duration: Long)
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
                override fun onPlay() = togglePlayPause()
                override fun onPause() = togglePlayPause()
                override fun onSkipToNext() = playNext()
                override fun onSkipToPrevious() = playPrevious()
                override fun onSeekTo(pos: Long) = seekTo(pos)
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
            ACTION_PLAY -> togglePlayPause()
            ACTION_PAUSE -> togglePlayPause()
            ACTION_NEXT -> playNext()
            ACTION_PREV -> playPrevious()
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

    fun playPlaylist(songs: List<Song>, startIndex: Int) {
        Log.d(TAG, "playPlaylist: ${songs.size} songs, startIndex=$startIndex")
        
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

package com.example.musicplayercursor.view

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun TrackScreen(
    title: String,
    artist: String,
    isPlaying: Boolean,
    currentPosition: Long,
    duration: Long,
    isFavourite: Boolean,
    isLooping: Boolean,
    onToggle: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleFavourite: () -> Unit,
    onToggleLoop: () -> Unit,
    isReceiverMode: Boolean = false,
    isConnectedToBroadcast: Boolean = false,
    onDisconnect: () -> Unit = {}  // ADD THIS PARAMETER
) {
    var isUserSeeking by remember { mutableStateOf(false) }
    var seekPosition by remember { mutableFloatStateOf(0f) }

    // Smoothly animate the slider position
    val targetSliderPosition = if (duration > 0) (currentPosition.toFloat() / duration.toFloat()) else 0f
    val animatedSliderPosition by animateFloatAsState(
        targetValue = targetSliderPosition,
        label = "Progress Animation"
    )

    val sliderPosition = if (isUserSeeking) seekPosition else animatedSliderPosition

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly
    ) {
        // Show "Connected to Broadcast" banner in receiver mode
        // Show "Connected to Broadcast" banner in receiver mode
        if (isReceiverMode && isConnectedToBroadcast) {
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                ) {
                    Text(
                        text = "Connected to Broadcast",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp)
                    )
                }

                // Disconnect button
                 Button(
                    onClick = onDisconnect,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(
                        text = "Disconnect from Broadcast",
                        color = MaterialTheme.colorScheme.onError
                    )
                }
            }
        }
        // 1. Album Art Placeholder
        Surface(
            modifier = Modifier
                .size(300.dp)
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            shadowElevation = 8.dp,
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = "Album Art",
                    modifier = Modifier.size(120.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 2. Track Info
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = artist,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 3. Progress Bar
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
        ) {
            Slider(
                value = sliderPosition,
                onValueChange = { newValue ->
                    if (!isReceiverMode) {
                        isUserSeeking = true
                        seekPosition = newValue
                    }
                },
                onValueChangeFinished = {
                    if (!isReceiverMode) {
                        val newPosition = (seekPosition * duration).toLong()
                        onSeek(newPosition)
                        isUserSeeking = false
                    }
                },
                enabled = !isReceiverMode,
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formatTime(if (isUserSeeking) (seekPosition * duration).toLong() else currentPosition),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = formatTime(duration),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 4. Playback Controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Loop
            IconButton(
                onClick = onToggleLoop,
                enabled = !isReceiverMode
            ) {
                Icon(
                    imageVector = Icons.Default.RepeatOne,
                    contentDescription = "Loop",
                    tint = if (isReceiverMode) 
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                    else if (isLooping) 
                        MaterialTheme.colorScheme.primary 
                    else 
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(28.dp)
                )
            }

            // Previous
            IconButton(
                onClick = onPrevious,
                enabled = !isReceiverMode,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.SkipPrevious,
                    contentDescription = "Previous",
                    tint = if (isReceiverMode) 
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                    else 
                        Color.Unspecified,
                    modifier = Modifier.size(36.dp)
                )
            }

            // Play/Pause (Prominent)
            FilledIconButton(
                onClick = onToggle,
                enabled = !isReceiverMode,
                modifier = Modifier.size(72.dp)
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = if (isReceiverMode) 
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                    else 
                        Color.Unspecified,
                    modifier = Modifier.size(40.dp)
                )
            }

            // Next
            IconButton(
                onClick = onNext,
                enabled = !isReceiverMode,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.SkipNext,
                    contentDescription = "Next",
                    tint = if (isReceiverMode) 
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                    else 
                        Color.Unspecified,
                    modifier = Modifier.size(36.dp)
                )
            }

            // Favourite
            IconButton(
                onClick = onToggleFavourite,
                enabled = !isReceiverMode
            ) {
                Icon(
                    imageVector = if (isFavourite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = "Favourite",
                    tint = if (isReceiverMode) 
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                    else if (isFavourite) 
                        Color.Red 
                    else 
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(28.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}

private fun formatTime(timeMs: Long): String {
    if (timeMs < 0) return "0:00"
    val totalSeconds = timeMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%d:%02d", minutes, seconds)
}
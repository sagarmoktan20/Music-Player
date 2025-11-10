package com.example.musicplayercursor.view

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.musicplayercursor.R

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
	onToggleLoop: () -> Unit
) {
	var isUserSeeking by remember { mutableStateOf(false) }
	var seekPosition by remember { mutableStateOf(0f) }
	
	// Update slider position when position changes (unless user is seeking)
	val sliderPosition = if (isUserSeeking) {
		seekPosition
	} else {
		if (duration > 0) (currentPosition.toFloat() / duration.toFloat()) else 0f
	}
	
	Column(
		modifier = Modifier
			.fillMaxSize()
			.padding(24.dp),
		horizontalAlignment = Alignment.CenterHorizontally,
		verticalArrangement = Arrangement.Center
	) {
		Spacer(modifier = Modifier.weight(1f))
		
		// Song Title
		Text(
			text = title,
			style = MaterialTheme.typography.headlineLarge,
			textAlign = TextAlign.Center,
			maxLines = 2,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 16.dp, vertical = 8.dp)
		)

		// Artist Name
		Text(
			text = artist,
			style = MaterialTheme.typography.titleMedium,
			textAlign = TextAlign.Center,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 16.dp, vertical = 4.dp)
		)
		
		Spacer(modifier = Modifier.height(32.dp))
		
		// Progress Bar with Time Labels
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 16.dp)
		) {
			Slider(
				value = sliderPosition,
				onValueChange = { newValue ->
					isUserSeeking = true
					seekPosition = newValue
				},
				onValueChangeFinished = {
					val newPosition = (seekPosition * duration).toLong()
					onSeek(newPosition)
					isUserSeeking = false
				},
				modifier = Modifier.fillMaxWidth()
			)
			
			// Time labels
			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.SpaceBetween
			) {
				Text(
					text = formatTime(if (isUserSeeking) (seekPosition * duration).toLong() else currentPosition),
					style = MaterialTheme.typography.bodySmall
				)
				Text(
					text = formatTime(duration),
					style = MaterialTheme.typography.bodySmall
				)
			}
		}
		
		Spacer(modifier = Modifier.height(32.dp))
		
		// Playback Controls - Larger buttons
		Row(
			horizontalArrangement = Arrangement.spacedBy(16.dp),
			verticalAlignment = Alignment.CenterVertically
		) {
			// Favourite button
			IconButton(
				onClick = onToggleFavourite,
				modifier = Modifier.size(56.dp)
			) {
				Icon(
					imageVector = if (isFavourite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
					contentDescription = if (isFavourite) "Remove from Favourites" else "Add to Favourites",
					modifier = Modifier.size(40.dp),
					tint = if (isFavourite) androidx.compose.ui.graphics.Color.Red else androidx.compose.ui.graphics.Color.Unspecified
				)
			}

			// Previous button - larger
			IconButton(
				onClick = onPrevious,
				modifier = Modifier.size(64.dp)
			) {
				Icon(
					imageVector = Icons.Default.SkipPrevious,
					contentDescription = "Previous",
					modifier = Modifier.size(48.dp)
				)
			}

			// Play/Pause button - largest
			IconButton(
				onClick = onToggle,
				modifier = Modifier.size(80.dp)
			) {
				Icon(
					imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
					contentDescription = if (isPlaying) "Pause" else "Play",
					modifier = Modifier.size(64.dp)
				)
			}



			// Next button - larger
			IconButton(
				onClick = onNext,
				modifier = Modifier.size(64.dp)
			) {
				Icon(
					imageVector = Icons.Default.SkipNext,
					contentDescription = "Next",
					modifier = Modifier.size(48.dp)
				)
			}
			// Loop button - on left side of Next button
			IconButton(
				onClick = onToggleLoop,
				modifier = Modifier.size(64.dp)
			) {
				Icon(
					imageVector = if (isLooping) Icons.Default.RepeatOne else Icons.Default.SkipNext,
					contentDescription = if (isLooping) "Loop Current" else "Next",
					modifier = Modifier.size(32.dp),
					tint = if (isLooping)
						MaterialTheme.colorScheme.primary
					else
						LocalContentColor.current.copy(alpha = 0.7f)
				)
			}

		}
		
		Spacer(modifier = Modifier.weight(1f))
	}
}

private fun formatTime(timeMs: Long): String {
	if (timeMs < 0) return "0:00"
	val totalSeconds = timeMs / 1000
	val minutes = totalSeconds / 60
	val seconds = totalSeconds % 60
	return String.format("%d:%02d", minutes, seconds)
}

package com.example.musicplayercursor.view

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun SelectionBottomBar(
    selectedCount: Int,
    onShare: (() -> Unit)? = null,
	onDelete: () -> Unit,
	onRemove: (() -> Unit)? = null,
    onAdd: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Surface(
        tonalElevation = 4.dp,
        shadowElevation = 8.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Selected count
            Text(
                text = "$selectedCount selected",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Start
            )
            
            Spacer(modifier = Modifier.width(8.dp))
            
            // Share button - only show if callback is provided
            if (onShare != null) {
                IconButton(onClick = onShare) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "Share",
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
            
			// Delete or Remove button
			if (onRemove != null) {
				IconButton(onClick = onRemove) {
					Icon(
						imageVector = Icons.Default.Delete,
						contentDescription = "Remove",
						modifier = Modifier.size(24.dp)
					)
				}
			} else {
				IconButton(onClick = onDelete) {
					Icon(
						imageVector = Icons.Default.Delete,
						contentDescription = "Delete",
						modifier = Modifier.size(24.dp),
						tint = MaterialTheme.colorScheme.error
					)
				}
			}
            
            // Add button - only show if callback is provided
            if (onAdd != null) {
                IconButton(onClick = onAdd) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Add",
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}


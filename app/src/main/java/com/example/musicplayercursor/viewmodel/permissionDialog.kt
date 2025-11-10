package com.example.musicplayercursor.viewmodel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionDialog(
    permissionTextProvider: PermissionTextProvider,
    isPermanentlyDeclined: Boolean,
    onDismiss: () -> Unit,
    onOkClick: () -> Unit,
    onGoToAppSettingsClick: () -> Unit,
    modifier: Modifier = Modifier

){
    BasicAlertDialog(
        onDismissRequest = onDismiss,
    ) {
        androidx.compose.material3.Surface(
            modifier = modifier,
            shape = androidx.compose.material3.MaterialTheme.shapes.medium,
            shadowElevation = 6.dp,
            color = androidx.compose.material3.MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Permission required",
                )
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = permissionTextProvider.getDescription(isPermanentlyDeclined)
                )
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    if (isPermanentlyDeclined) {
                        Button(
                            onClick = onGoToAppSettingsClick
                        ) {
                            Text("Go to Settings")
                        }
                    } else {
                        Button(
                            onClick = onOkClick
                        ) {
                            Text("OK")
                        }
                    }
                }
            }
        }
    }
}

interface PermissionTextProvider {
    fun getDescription(isPermanentlyDeclined: Boolean): String
}

class ReadMediaAudio: PermissionTextProvider {
    override fun getDescription(isPermanentlyDeclined: Boolean): String {
        return if(isPermanentlyDeclined) {
            "It seems you permanently declined media permission. " +
                    "You can go to the app settings to grant it."
        } else {
            "This app needs access to your media to play songs."
        }
    }
}

class WriteMediaAudio: PermissionTextProvider {
    override fun getDescription(isPermanentlyDeclined: Boolean): String {
        return if (isPermanentlyDeclined) {
            "Delete permission denied permanently. Go to settings to allow deleting songs."
        } else {
            "Allow deleting songs from your device."
        }
    }
}
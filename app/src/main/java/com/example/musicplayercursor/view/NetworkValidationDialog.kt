package com.example.musicplayercursor.view

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.musicplayercursor.util.NetworkUtils

@Composable
fun NetworkValidationDialog(
    onDismiss: () -> Unit,
    onNetworkDetected: () -> Unit
) {
    val context = LocalContext.current
    
    // Auto-dismiss when correct network detected
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            if (NetworkUtils.getLocalIPAddress() != null) {
                // Check if on hotspot network
                val localIP = NetworkUtils.getLocalIPAddress()
                val isOnHotspot = localIP != null && (
                    localIP.startsWith("192.168.") ||
                    localIP.startsWith("172.16.") ||
                    localIP.startsWith("172.17.") ||
                    localIP.startsWith("172.18.") ||
                    localIP.startsWith("172.19.") ||
                    localIP.startsWith("172.20.") ||
                    localIP.startsWith("172.21.") ||
                    localIP.startsWith("172.22.") ||
                    localIP.startsWith("172.23.") ||
                    localIP.startsWith("172.24.") ||
                    localIP.startsWith("172.25.") ||
                    localIP.startsWith("172.26.") ||
                    localIP.startsWith("172.27.") ||
                    localIP.startsWith("172.28.") ||
                    localIP.startsWith("172.29.") ||
                    localIP.startsWith("172.30.") ||
                    localIP.startsWith("172.31.") ||
                    localIP.startsWith("10.")
                )
                
                if (isOnHotspot) {
                    onNetworkDetected()
                    onDismiss()
                    break
                }
            }
        }
    }
    
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Join the hotspot first",
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Button(
                    onClick = {
                        // Open Wi-Fi Settings
                        val intent = Intent(Settings.ACTION_WIRELESS_SETTINGS)
                        context.startActivity(intent)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Open Wi-Fi Settings")
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Cancel")
                }
            }
        }
    }
}


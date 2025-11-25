package com.example.musicplayercursor.view

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.musicplayercursor.util.QRCodeGenerator
import com.example.musicplayercursor.viewmodel.BroadcastViewModel

@Composable
fun BroadcastScreen(
    viewModel: BroadcastViewModel,
    onStop: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val broadcastState by viewModel.broadcastState.collectAsStateWithLifecycle()
    
    // BackHandler to go back to MusicScreen without stopping broadcast
    BackHandler(onBack = onBack)
    
    // Generate QR code from deep link
    val deepLink = remember(broadcastState.serverIP, broadcastState.token) {
        if (broadcastState.serverIP != null && broadcastState.token != null) {
            "musicplayer://broadcast?ip=${broadcastState.serverIP}&token=${broadcastState.token}"
        } else {
            null
        }
    }
    
    val qrCodeBitmap = remember(deepLink) {
        if (deepLink != null) {
            QRCodeGenerator.generateQRCode(deepLink, 512)
        } else {
            null
        }
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Stop Broadcast button (replaces "Music Player" text in header)
        Button(
            onClick = {
                viewModel.stopBroadcast(context)
                onStop()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Stop Broadcast")
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        
        // QR Code
        if (qrCodeBitmap != null) {
            Image(
                bitmap = qrCodeBitmap.asImageBitmap(),
                contentDescription = "QR Code for broadcast connection",
                modifier = Modifier.size(300.dp)
            )
        } else {
            Text(
                text = "Generating QR code...",
                style = MaterialTheme.typography.bodyLarge
            )
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        
        // Server IP and port
        if (broadcastState.serverIP != null) {
            Text(
                text = "${broadcastState.serverIP}:8080",
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center
            )
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Connected clients count
        Text(
            text = "Connected clients: ${broadcastState.connectedClients}",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center
        )
    }
}


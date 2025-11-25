package com.example.musicplayercursor.view

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.musicplayercursor.viewmodel.BroadcastViewModel

@Composable
fun BroadcastDialog(
    viewModel: BroadcastViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val broadcastState by viewModel.broadcastState.collectAsStateWithLifecycle()
    
    LaunchedEffect(Unit) {
        viewModel.startHotspotDetection(context)
    }
    
    LaunchedEffect(broadcastState.isBroadcasting) {
        if (broadcastState.isBroadcasting) {
            onDismiss()
        }
    }
    
    // Check hotspot state periodically
//    LaunchedEffect(Unit) {
//        while (true) {
//            kotlinx.coroutines.delay(1000)
//            if (viewModel.isHotspotActive() && !broadcastState.isBroadcasting) {
//                // Auto-start broadcast when hotspot is detected
//                viewModel.startBroadcast(context)
//            }
//        }
//    }
    
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "Turn on your Mobile Hotspot to broadcast",
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                
                Spacer(modifier = Modifier.height(32.dp))
                
                CircularProgressIndicator(
                    modifier = Modifier.padding(bottom = 32.dp)
                )
                
                Button(
                    onClick = {
                        // Open hotspot settings
                        val intent = Intent(Settings.ACTION_WIRELESS_SETTINGS)
                        context.startActivity(intent)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Open Hotspot Settings")
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
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


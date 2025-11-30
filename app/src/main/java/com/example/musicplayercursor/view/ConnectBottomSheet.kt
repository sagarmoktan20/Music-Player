 package com.example.musicplayercursor.view

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.musicplayercursor.viewmodel.ConnectViewModel
import com.example.musicplayercursor.viewmodel.MusicViewModel
import androidx.activity.compose.rememberLauncherForActivityResult
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

private const val CONNECT_UI_TAG = "ConnectViewModel" // Use same tag as ConnectViewModel for filtering

@Composable
fun ConnectBottomSheet(
    connectViewModel: ConnectViewModel,
    onDismiss: () -> Unit,
    onConnected: () -> Unit
) {
    Log.d(CONNECT_UI_TAG, "[ConnectBottomSheet] Composable called")
    val context = LocalContext.current
    val connectState by connectViewModel.connectState.collectAsStateWithLifecycle()
    var selectedTabIndex by remember { mutableIntStateOf(0) } // 0 = QR Scan, 1 = Manual Entry
    Log.d(CONNECT_UI_TAG, "[ConnectBottomSheet] Initial state: selectedTabIndex=$selectedTabIndex")
    
    var manualIP by remember { mutableStateOf("") }
    var manualToken by remember { mutableStateOf("") }
    var showNetworkDialog by remember { mutableStateOf(false) }
    var scannedIP by remember { mutableStateOf<String?>(null) }
    var scannedToken by remember { mutableStateOf<String?>(null) }
    
    // QR Scanner launcher using ZXing - using new ScanContract API
    val qrScannerLauncher = rememberLauncherForActivityResult(
        contract = ScanContract()
    ) { result ->
        Log.d(CONNECT_UI_TAG, "[ConnectBottomSheet] QR scan result received")
        val contents = result.contents
        if (contents != null) {
            Log.d(CONNECT_UI_TAG, "[ConnectBottomSheet] QR scan contents: $contents")
            val deepLink = contents
            val (ip, token) = connectViewModel.parseDeepLink(deepLink)
            if (ip != null && token != null) {
                Log.d(CONNECT_UI_TAG, "[ConnectBottomSheet] Deep link parsed: ip=$ip, token=$token")
                scannedIP = ip
                scannedToken = token
                // Check network before connecting
                val isOnHotspot = connectViewModel.isOnHotspotNetwork(context)
                Log.d(CONNECT_UI_TAG, "[ConnectBottomSheet] Network check: isOnHotspot=$isOnHotspot")
                if (isOnHotspot) {
                    Log.d(CONNECT_UI_TAG, "[ConnectBottomSheet] On hotspot network, connecting...")
                    connectViewModel.connectToBroadcast(ip, token, context)
                } else {
                    Log.w(CONNECT_UI_TAG, "⚠️ [ConnectBottomSheet] Not on hotspot network, showing dialog")
                    showNetworkDialog = true
                }
            } else {
                Log.w(CONNECT_UI_TAG, "⚠️ [ConnectBottomSheet] Failed to parse deep link: ip=$ip, token=$token")
            }
        } else {
            Log.w(CONNECT_UI_TAG, "⚠️ [ConnectBottomSheet] QR scan result contents is null")
        }
    }
    
    // Handle connection success
    LaunchedEffect(connectState.isConnected) {
        if (connectState.isConnected) {
            Log.i(CONNECT_UI_TAG, "[ConnectBottomSheet] ✅ Connection successful, calling onConnected and dismissing")
            onConnected()
            onDismiss()
        }
    }
    
    // Network validation dialog
    if (showNetworkDialog) {
        Log.d(CONNECT_UI_TAG, "[ConnectBottomSheet] Showing network validation dialog")
        NetworkValidationDialog(
            onDismiss = { 
                Log.d(CONNECT_UI_TAG, "[ConnectBottomSheet] Network dialog dismissed")
                showNetworkDialog = false 
            },
            onNetworkDetected = {
                Log.d(CONNECT_UI_TAG, "[ConnectBottomSheet] Network detected, connecting...")
                // Network detected - now connect
                val ip = scannedIP ?: manualIP
                val token = scannedToken ?: manualToken
                Log.d(CONNECT_UI_TAG, "[ConnectBottomSheet] Connecting with: ip=$ip, token=$token")
                if (ip.isNotEmpty() && token.isNotEmpty()) {
                    connectViewModel.connectToBroadcast(ip, token, context)
                    Log.d(CONNECT_UI_TAG, "[ConnectBottomSheet] Connection initiated")
                } else {
                    Log.w(CONNECT_UI_TAG, "⚠️ [ConnectBottomSheet] IP or token is empty, cannot connect")
                }
                showNetworkDialog = false
            }
        )
    }
    
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // Tabs
            TabRow(selectedTabIndex = selectedTabIndex) {
                Tab(
                    selected = selectedTabIndex == 0,
                    onClick = { 
                        Log.d(CONNECT_UI_TAG, "[ConnectBottomSheet] Tab changed: Scan QR")
                        selectedTabIndex = 0 
                    },
                    text = { Text("Scan QR") }
                )
                Tab(
                    selected = selectedTabIndex == 1,
                    onClick = { 
                        Log.d(CONNECT_UI_TAG, "[ConnectBottomSheet] Tab changed: Manual Entry")
                        selectedTabIndex = 1 
                    },
                    text = { Text("Manual Entry") }
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Tab content
            when (selectedTabIndex) {
                0 -> {
                    // QR Scan tab
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        if (connectState.isConnecting) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("Connecting...")
                        } else {
                            Text(
                                text = "Scan QR code from broadcaster",
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(bottom = 16.dp)
                            )
                            
                            Button(
                                onClick = {
                                    Log.d(CONNECT_UI_TAG, "[ConnectBottomSheet] QR scan button clicked")
                                    val options = ScanOptions().apply {
                                        setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                        setPrompt("Scan QR Code")
                                        setCameraId(0)
                                        setBeepEnabled(false)
                                        setBarcodeImageEnabled(true)
                                    }
                                    Log.d(CONNECT_UI_TAG, "[ConnectBottomSheet] Launching QR scanner...")
                                    qrScannerLauncher.launch(options)
                                }
                            ) {
                                Text("Scan QR Code")
                            }
                        }
                        
                        // Error display
                        connectState.connectionError?.let { error ->
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = error,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
                
                1 -> {
                    // Manual Entry tab
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        OutlinedTextField(
                            value = manualIP,
                            onValueChange = { manualIP = it },
                            label = { Text("Server IP") },
                            placeholder = { Text("192.168.43.1") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !connectState.isConnecting
                        )
                        
                        OutlinedTextField(
                            value = manualToken,
                            onValueChange = { manualToken = it },
                            label = { Text("Token") },
                            placeholder = { Text("k9m3p2x8") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !connectState.isConnecting
                        )
                        
                        if (connectState.isConnecting) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .align(Alignment.CenterHorizontally)
                                    .padding(16.dp)
                            )
                            Text(
                                text = "Connecting...",
                                modifier = Modifier.align(Alignment.CenterHorizontally)
                            )
                        } else {
                            Button(
                                onClick = {
                                    Log.d(CONNECT_UI_TAG, "[ConnectBottomSheet] Manual connect button clicked: ip=$manualIP, token=$manualToken")
                                    if (manualIP.isNotEmpty() && manualToken.isNotEmpty()) {
                                        // Check network before connecting
                                        val isOnHotspot = connectViewModel.isOnHotspotNetwork(context)
                                        Log.d(CONNECT_UI_TAG, "[ConnectBottomSheet] Network check: isOnHotspot=$isOnHotspot")
                                        if (isOnHotspot) {
                                            Log.d(CONNECT_UI_TAG, "[ConnectBottomSheet] On hotspot network, connecting...")
                                            connectViewModel.connectToBroadcast(manualIP, manualToken, context)
                                        } else {
                                            Log.w(CONNECT_UI_TAG, "⚠️ [ConnectBottomSheet] Not on hotspot network, showing dialog")
                                            showNetworkDialog = true
                                        }
                                    } else {
                                        Log.w(CONNECT_UI_TAG, "⚠️ [ConnectBottomSheet] IP or token is empty, cannot connect")
                                    }
                                },
                                enabled = manualIP.isNotEmpty() && manualToken.isNotEmpty() && !connectState.isConnecting,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Connect")
                            }
                        }
                        
                        // Error display
                        connectState.connectionError?.let { error ->
                            Text(
                                text = error,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Close button
            TextButton(
                onClick = {
                    Log.d(CONNECT_UI_TAG, "[ConnectBottomSheet] Cancel button clicked, dismissing")
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Cancel")
            }
        }
    }
}


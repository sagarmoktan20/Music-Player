package com.example.musicplayercursor

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.example.musicplayercursor.ui.theme.MusicPlayercursorTheme
import com.example.musicplayercursor.view.MusicScreen
import com.example.musicplayercursor.viewmodel.MusicViewModel
import com.example.musicplayercursor.viewmodel.PermissionDialog
import com.example.musicplayercursor.viewmodel.PermissionViewModel
import com.example.musicplayercursor.viewmodel.ReadMediaAudio
import com.example.musicplayercursor.viewmodel.BroadcastViewModel
import com.example.musicplayercursor.viewmodel.ConnectViewModel
import com.example.musicplayercursor.service.MusicService
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {

    private val permissionsToRequest = arrayOf(

        if (Build.VERSION.SDK_INT >= 33)
            Manifest.permission.READ_MEDIA_AUDIO
        else
            Manifest.permission.READ_EXTERNAL_STORAGE
    )
    
    private var musicService by mutableStateOf<MusicService?>(null)
    private var serviceBound by mutableStateOf(false)
    
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d("FirstPlay", "MainActivity: onServiceConnected called")
            val binder = service as MusicService.LocalBinder
            musicService = binder.getService()
            serviceBound = true
            Log.d("FirstPlay", "MainActivity: Service connected, musicService=$musicService")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d("FirstPlay", "MainActivity: onServiceDisconnected called")
            musicService = null
            serviceBound = false
        }
    }
    
    @SuppressLint("ViewModelConstructorInComposable")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("FirstPlay", "MainActivity: onCreate started")
        enableEdgeToEdge()
        
        // Start and bind to MusicService
        val serviceIntent = Intent(this, MusicService::class.java)
        startService(serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
        Log.d("FirstPlay", "MainActivity: Service started and bind requested")
        
        setContent {

            MusicPlayercursorTheme {
                val viewModel: MusicViewModel = viewModel()
                val permissionViewModel: PermissionViewModel = viewModel()
                val broadcastViewModel: BroadcastViewModel = viewModel()
                val connectViewModel: ConnectViewModel = viewModel()
                val dialogQueue = permissionViewModel.visiblePermissionDialogQueue
               // val viewModel: MusicViewModel = MusicViewModel()

// Pass MusicService to ViewModels when service is connected
                LaunchedEffect(serviceBound) {
                    Log.d("FirstPlay", "MainActivity: LaunchedEffect(serviceBound) triggered. serviceBound=$serviceBound, musicService=$musicService")
                    if (serviceBound && musicService != null) {
                        Log.d("FirstPlay", "MainActivity: Setting music service to ViewModels")
                        viewModel.setMusicService(musicService)
                        connectViewModel.setMusicService(musicService)
                        broadcastViewModel.setMusicService(musicService)
                    }
                }

// Also update when service connection changes
                LaunchedEffect(musicService) {
                    Log.d("FirstPlay", "MainActivity: LaunchedEffect(musicService) triggered. musicService=$musicService")
                    musicService?.let { service ->
                        viewModel.setMusicService(service)
                        connectViewModel.setMusicService(service)
                        broadcastViewModel.setMusicService(service)
                    }
                }
                // Handle deep link intent
                LaunchedEffect(Unit) {
                    intent?.data?.let { uri ->
                        if (uri.scheme == "musicplayer" && uri.host == "broadcast") {
                            val ip = uri.getQueryParameter("ip")
                            val token = uri.getQueryParameter("token")
                            if (ip != null && token != null) {
                                // Pre-fill connection data (will be shown in ConnectBottomSheet)
                                // The actual connection will be initiated from ConnectBottomSheet
                            }
                        }
                    }
                }

                val multiplePermissionResultLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions(),
                    onResult = { perms ->
                        permissionsToRequest.forEach { permission ->
                            permissionViewModel.onPermissionResult(
                                permission = permission,
                                isGranted = perms[permission] == true
                            )
                        }
                    }
                )
                LaunchedEffect(Unit) {
                    multiplePermissionResultLauncher.launch(permissionsToRequest)
                }
                
                // Service binding is handled separately if needed
                // The ViewModel manages its own ExoPlayer instance

                if (dialogQueue.isNotEmpty()) {
                    Log.d("DIALOG_DEBUG", "Dialog queue is NOT empty: $dialogQueue")
                    val permissionToShow = dialogQueue.reversed().first()
                    val isGranted = androidx.core.content.ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        permissionToShow
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED

                    val isPermanentlyDeclined = !isGranted && !shouldShowRequestPermissionRationale(permissionToShow)
                    Log.d("should", "Dialog queue: $dialogQueue, permissionToShow: $permissionToShow, isGranted: $isGranted, isPermanentlyDeclined: $isPermanentlyDeclined")

                    PermissionDialog(
                        permissionTextProvider = when (permissionToShow) {
                            Manifest.permission.READ_MEDIA_AUDIO-> ReadMediaAudio()


                            else -> return@MusicPlayercursorTheme
                        },
                        isPermanentlyDeclined = isPermanentlyDeclined,
                        onDismiss = permissionViewModel::dismissDialog,
                        onOkClick = {
                            permissionViewModel.dismissDialog()
                            multiplePermissionResultLauncher.launch(
                                arrayOf(permissionToShow)
                            )
                        },
                        onGoToAppSettingsClick = ::openAppSettings
                    )
                } else {
                    Log.d("DIALOG_DEBUG", "Dialog queue is empty, no dialog shown")
                }

                        MusicScreen(
                        viewModel = viewModel,
                        permissionViewModel = permissionViewModel,
                        broadcastViewModel = broadcastViewModel,
                        connectViewModel = connectViewModel,
                        onRequestSongs = { viewModel.loadSongs(applicationContext) },
                        onPlay = { viewModel.play(applicationContext, it) },
                        onToggle = { viewModel.togglePlayPause() }
                    )

            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
    }
}

fun Activity.openAppSettings() {
    android.util.Log.d("SETTINGS_DEBUG", "openAppSettings() called - this should only happen when user clicks 'Go to Settings' in permission dialog")
    Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", packageName, null)
    ).also(::startActivity)
}
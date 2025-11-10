package com.example.musicplayercursor.viewmodel

import android.Manifest
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class PermissionViewModel: ViewModel() {

    val visiblePermissionDialogQueue = mutableStateListOf<String>()

    private val _hasAudioPermission = MutableStateFlow(false)
    val hasAudioPermission = _hasAudioPermission.asStateFlow()

    fun refreshPermissionStatus(context: android.content.Context) {
        val permission = if (android.os.Build.VERSION.SDK_INT >= 33)
            android.Manifest.permission.READ_MEDIA_AUDIO
        else
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            context, permission
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        _hasAudioPermission.value = granted
    }


    fun dismissDialog() {
        visiblePermissionDialogQueue.removeAt(0)
    }

    fun onPermissionResult(
        permission: String,
        isGranted: Boolean
    ) {
        android.util.Log.d("QUEUE_DEBUG", "onPermissionResult called for $permission, isGranted: $isGranted, current queue: $visiblePermissionDialogQueue")

        if (isGranted) {
            // If the permission is granted, remove it from the queue if it was there
            val wasRemoved = visiblePermissionDialogQueue.remove(permission)
            if (permission == Manifest.permission.READ_MEDIA_AUDIO ||
                permission == Manifest.permission.READ_EXTERNAL_STORAGE) {
                _hasAudioPermission.value = true
            }
            android.util.Log.d("QUEUE_DEBUG", "Permission $permission granted, removed from queue: $wasRemoved, new queue: $visiblePermissionDialogQueue")
        } else {
            // Only add to the queue if it's not already there and not granted
            if (!visiblePermissionDialogQueue.contains(permission)) {
                visiblePermissionDialogQueue.add(permission)
                android.util.Log.d("QUEUE_DEBUG", "Permission $permission denied, added to queue, new queue: $visiblePermissionDialogQueue")
            } else {
                android.util.Log.d("QUEUE_DEBUG", "Permission $permission already in queue, not adding again")
            }
            _hasAudioPermission.value = false
        }
    }
}
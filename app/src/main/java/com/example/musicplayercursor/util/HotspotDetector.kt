package com.example.musicplayercursor.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.NetworkInterface

class HotspotDetector(private val context: Context) {
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val handler = Handler(Looper.getMainLooper())
    
    private val _isHotspotActive = MutableStateFlow<Boolean>(false)
    val isHotspotActive: StateFlow<Boolean> = _isHotspotActive
    
    private val _hotspotIP = MutableStateFlow<String?>(null)
    val hotspotIP: StateFlow<String?> = _hotspotIP
    
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var pollingRunnable: Runnable? = null
    private var pollingAttempts = 0
    private val maxPollingAttempts = 15 // 15 seconds max
    
    /**
     * Start detecting hotspot using NetworkCallback (primary) with polling fallback
     */
    fun startDetection() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // Use NetworkCallback for instant detection (Android 7.0+)
            startNetworkCallbackDetection()
        } else {
            // Fallback to polling for older Android versions
            startPollingDetection()
        }
    }
    
    /**
     * Primary method: Use NetworkCallback for instant detection (Android 7.0+)
     */
    private fun startNetworkCallbackDetection() {
        if (networkCallback != null) return // Already started
        
        val networkRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                checkHotspotState()
            }
            
            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                checkHotspotState()
            }
            
            override fun onLost(network: Network) {
                checkHotspotState()
            }
        }
        
        try {
            connectivityManager.registerNetworkCallback(networkRequest, networkCallback!!)
            // Also check immediately
            checkHotspotState()
        } catch (e: Exception) {
            e.printStackTrace()
            // Fallback to polling if NetworkCallback fails
            startPollingDetection()
        }
    }
    
    /**
     * Fallback method: Polling mechanism for older Android versions
     */
    private fun startPollingDetection() {
        if (pollingRunnable != null) return // Already started
        
        pollingAttempts = 0
        pollingRunnable = object : Runnable {
            override fun run() {
                checkHotspotState()
                pollingAttempts++
                if (pollingAttempts < maxPollingAttempts) {
                    handler.postDelayed(this, 1000) // Poll every 1 second
                }
            }
        }
        handler.post(pollingRunnable!!)
    }
    
    /**
     * Check if hotspot is active and get its IP
     */
//    private fun checkHotspotState() {
//        val ip = detectHotspotIP()
//        Log.d("HOTSPOT", "Detected hotspot IP: $ip")  // ← ADD THIS
//        val isActive = ip != null
//
//        _hotspotIP.value = ip
//        _isHotspotActive.value = isActive
//    }
    private fun checkHotspotState() {
        var attempts = 0
        val maxAttempts = 12

        handler.post(object : Runnable {
            override fun run() {
                val ip = detectHotspotIP()
                Log.d("HOTSPOT", "Attempt ${++attempts}/$maxAttempts → Detected IP: $ip")

                if (ip != null) {
                    _hotspotIP.value = ip
                    _isHotspotActive.value = true
                    Log.w("HOTSPOT", "HOTSPOT IP FOUND: $ip → Broadcasting will start now!")
                    return  // Success → stop trying
                }

                if (attempts < maxAttempts) {
                    handler.postDelayed(this, 800)  // retry every 800 ms
                } else {
                    Log.e("HOTSPOT", "Failed to detect hotspot IP after $maxAttempts attempts")
                }
            }
        })
    }
    
    /**
     * Get hotspot IP address via NetworkInterface
     */
    fun detectHotspotIP(): String? {
        try {
            NetworkInterface.getNetworkInterfaces()?.asSequence()?.forEach { intf ->
                // Samsung: swlan0, ap0, wlan0
                // Xiaomi/OPPO: wlan1, ap0
                // Google Pixel: p2p0, wlan0
                if (intf.name.startsWith("swlan") ||
                    intf.name.startsWith("ap") ||
                    intf.name.startsWith("wlan") ||
                    intf.name.startsWith("p2p")) {

                    intf.inetAddresses.asSequence()
                        .filter { !it.isLoopbackAddress && it is java.net.Inet4Address }
                        .forEach { addr ->
                            val ip = addr.hostAddress!!
                            // Common hotspot ranges
                            if (ip.startsWith("192.168.") ||
                                ip.startsWith("172.") ||
                                ip.startsWith("10.")) {
                                return ip
                            }
                        }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }
    
    /**
     * Check if mobile hotspot is active
     */
    fun isHotspotActive(): Boolean {
        return detectHotspotIP() != null
    }
    
    /**
     * Stop detection
     */
    fun stopDetection() {
        networkCallback?.let {
            try {
                connectivityManager.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            networkCallback = null
        }
        
        pollingRunnable?.let {
            handler.removeCallbacks(it)
            pollingRunnable = null
        }
        pollingAttempts = 0
    }
}


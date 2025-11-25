package com.example.musicplayercursor.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import java.net.NetworkInterface

object NetworkUtils {
    /**
     * Get local IP address from active network interface (192.168.x.x)
     */
    /**
     * Get local IP address from active network interface (private IP ranges)
     * Returns IPs in 192.168.x.x, 10.x.x.x, or 172.16-31.x.x ranges
     */
    fun getLocalIPAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address.hostAddress != null) {
                        val hostAddress = address.hostAddress
                        // Check if it's an IPv4 address in private ranges (hotspot networks)
                        // 192.168.x.x, 10.x.x.x, or 172.16.x.x - 172.31.x.x
                        if (hostAddress.startsWith("192.168.") ||
                            hostAddress.startsWith("10.") ||
                            (hostAddress.startsWith("172.16.") || hostAddress.startsWith("172.17.") ||
                                    hostAddress.startsWith("172.18.") || hostAddress.startsWith("172.19.") ||
                                    hostAddress.startsWith("172.20.") || hostAddress.startsWith("172.21.") ||
                                    hostAddress.startsWith("172.22.") || hostAddress.startsWith("172.23.") ||
                                    hostAddress.startsWith("172.24.") || hostAddress.startsWith("172.25.") ||
                                    hostAddress.startsWith("172.26.") || hostAddress.startsWith("172.27.") ||
                                    hostAddress.startsWith("172.28.") || hostAddress.startsWith("172.29.") ||
                                    hostAddress.startsWith("172.30.") || hostAddress.startsWith("172.31."))
                        ) {
                            return hostAddress
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
     * Check network connectivity state
     */
    fun isNetworkConnected(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } else {
            @Suppress("DEPRECATION")
            val networkInfo: NetworkInfo? = connectivityManager.activeNetworkInfo
            networkInfo?.isConnected == true
        }
    }

    /**
     * Validate if current network is hotspot
     */
    fun isHotspotNetwork(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            // Check if network has WiFi transport (hotspot typically uses WiFi)
            // Note: There's no direct API to check if we're hosting a hotspot,
            // so we check if we have a WiFi connection with internet capability
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } else {
            // For older versions, check if we have a WiFi connection
            @Suppress("DEPRECATION")
            val networkInfo: NetworkInfo? = connectivityManager.activeNetworkInfo
            networkInfo?.type == ConnectivityManager.TYPE_WIFI
        }
    }
}


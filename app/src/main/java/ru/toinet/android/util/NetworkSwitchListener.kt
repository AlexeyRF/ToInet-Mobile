package ru.toinet.android.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log

object NetworkSwitchListener {
    private const val TAG = "NetworkSwitchListener"
    private var isRegistered = false
    private var lastNetworkType = -1 // 0 for Wifi, 1 for Cellular

    fun register(context: Context) {
        if (isRegistered) return
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        connectivityManager.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                val currentType = when {
                    networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 0
                    networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 1
                    else -> -1
                }

                if (currentType != -1 && currentType != lastNetworkType) {
                    if (lastNetworkType != -1) { // Ignore the very first call
                        Log.i(TAG, "Network switched! New type: ${if (currentType == 0) "WIFI" else "CELLULAR"}")
                        val presetJson = if (currentType == 0) Prefs.wifiPreset else Prefs.mobilePreset
                        if (presetJson.isNotBlank()) {
                            PresetManager.applySnapshot(context, presetJson)
                        } else {
                            Log.i(TAG, "No preset saved for this network type.")
                        }
                    }
                    lastNetworkType = currentType
                }
            }
        })
        isRegistered = true
    }
}

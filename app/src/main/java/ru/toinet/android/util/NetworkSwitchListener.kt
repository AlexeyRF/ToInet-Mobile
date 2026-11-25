package ru.toinet.android.util

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import ru.toinet.android.byedpi.services.ByeDpiProxyService
import ru.toinet.android.vpn.data.ServiceStatus
import ru.toinet.android.vpn.rules.ProxifierRulesManager

object NetworkSwitchListener {
    private const val TAG = "NetworkSwitchListener"
    private var isRegistered = false
    private var lastNetworkType = -1 // 0 for Wifi/Ethernet, 1 for Cellular

    fun register(context: Context) {
        if (isRegistered) return
        val appContext = context.applicationContext ?: context
        val connectivityManager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return

        try {
            connectivityManager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    val currentType = when {
                        networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                                networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 0
                        networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 1
                        else -> -1
                    }

                    if (currentType != -1 && currentType != lastNetworkType) {
                        val prevType = lastNetworkType
                        lastNetworkType = currentType
                        val networkKey = if (currentType == 0) Prefs.VPN_PROFILE_WIFI else Prefs.VPN_PROFILE_MOBILE
                        if (prevType == -1) {
                            Prefs.currentVpnNetworkType = networkKey
                        } else {
                            val typeName = if (currentType == 0) "Wi-Fi" else "Мобильная сеть"
                            Log.i(TAG, "Default network switched to $typeName")
                            checkAndSwitchZdpiStrategy(appContext, currentType, typeName)
                            checkAndSwitchVpnProfile(appContext, networkKey, typeName)
                        }
                    }
                }

                override fun onLost(network: Network) {
                    Log.i(TAG, "Default network lost")
                }
            })
            isRegistered = true
            Log.i(TAG, "NetworkSwitchListener registered successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback", e)
        }
    }

    private fun checkAndSwitchZdpiStrategy(context: Context, networkType: Int, typeName: String) {
        val neededByVpn = Prefs.isGlobalVpnEnabled && try {
            ProxifierRulesManager.getAllNeededVpnProviders().contains("byedpi")
        } catch (_: Throwable) {
            false
        }
        val isZdpiActive = ByeDpiProxyService.status == ServiceStatus.Connected || neededByVpn

        if (!isZdpiActive) {
            Log.d(TAG, "zDPI is not active, skipping strategy switch")
            return
        }

        val newStrategy = if (networkType == 0) {
            Prefs.byedpiWifiArgs
        } else {
            Prefs.byedpiMobileArgs
        }.ifBlank { "--preset general" }

        val currentStrategy = ByeDpiProxyService.currentRunningArgs

        if (currentStrategy != null && currentStrategy == newStrategy) {
            Log.i(TAG, "zDPI strategy for $typeName is already active: $newStrategy")
            return
        }

        Log.i(TAG, "Switching zDPI strategy for $typeName to: $newStrategy")
        NotificationLogger.log(context, "byedpi", "Смена сети на $typeName: переключение стратегии zDPI...")

        val intent = Intent(context, ByeDpiProxyService::class.java).apply {
            action = ByeDpiProxyService.ACTION_RESTART
            putExtra("is_foreground", true)
        }
        context.sendIntentToService(intent, isForeground = true)
    }

    private fun checkAndSwitchVpnProfile(context: Context, newNetworkKey: String, typeName: String) {
        if (!Prefs.vpnSeparateNetworkProfiles) {
            Prefs.currentVpnNetworkType = newNetworkKey
            return
        }
        val previousVpnEnabled = Prefs.isGlobalVpnEnabled
        Prefs.currentVpnNetworkType = newNetworkKey
        ProxifierRulesManager.invalidateCache()

        Log.i(
            TAG,
            "Applying VPN profile for $typeName (prevVpnEnabled=$previousVpnEnabled, newVpnEnabled=${Prefs.isGlobalVpnEnabled})"
        )
        NotificationLogger.log(context, "proxifier", "Смена сети на $typeName: применен VPN-профиль ($typeName)")
        context.applyActiveNetworkVpnProfile(previousVpnEnabled)
        context.sendBroadcast(
            Intent(ACTION_VPN_PROFILE_CHANGED).setPackage(context.packageName)
        )
    }
}

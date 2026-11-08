package ru.toinet.android.util

import android.content.Context
import android.content.Intent
import android.util.Log

object UpstreamProxyCoordinator {
    private const val TAG = "UpstreamCoordinator"

    const val PROVIDER_NONE = "none"
    const val PROVIDER_EASYVPN = "easyvpn"
    const val PROVIDER_BYEDPI = "byedpi"
    const val PROVIDER_TOR = "tor"
    const val PROVIDER_CUSTOM = "custom"

    fun getPortForProvider(provider: String): Int {
        return when (provider) {
            PROVIDER_EASYVPN -> Prefs.easyVpnProxyPort
            PROVIDER_BYEDPI -> 1780
            PROVIDER_TOR -> 9853
            else -> 1780
        }
    }

    fun ensureUpstreamStarted(context: Context, provider: String) {
        if (provider.isBlank() || provider == PROVIDER_NONE || provider == PROVIDER_CUSTOM) {
            return
        }

        try {
            when (provider) {
                PROVIDER_EASYVPN -> {
                    Log.i(TAG, "Ensuring EasyVPN is started as upstream")
                    ru.toinet.android.easyvpn.EasyVpnService.start(context)
                }
                PROVIDER_BYEDPI -> {
                    Log.i(TAG, "Ensuring zDPI is started as upstream")
                    val intent = Intent(context, ru.toinet.android.byedpi.services.ByeDpiProxyService::class.java).apply {
                        action = ru.toinet.android.vpn.data.START_ACTION
                    }
                    context.sendIntentToService(intent, isForeground = true)
                }
                PROVIDER_TOR -> {
                    Log.i(TAG, "Ensuring Tor is started as upstream")
                    val intent = Intent(context, ru.toinet.android.service.ToInetService::class.java).apply {
                        action = org.torproject.jni.TorService.ACTION_START
                    }
                    context.sendIntentToService(intent, isForeground = true)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start upstream service $provider", e)
        }
    }

    fun getTorUpstreamOptions(): List<Pair<String, String>> {
        return listOf(
            PROVIDER_NONE to "Прямое соединение (без upstream)",
            PROVIDER_EASYVPN to "Easy-VPN (127.0.0.1:${Prefs.easyVpnProxyPort}) — для белых списков",
            PROVIDER_BYEDPI to "zDPI (127.0.0.1:1780)",
            PROVIDER_CUSTOM to "Пользовательский SOCKS5 / HTTPS..."
        )
    }

    fun getEasyVpnUpstreamOptions(): List<Pair<String, String>> {
        return listOf(
            PROVIDER_NONE to "Прямое соединение (без upstream)",
            PROVIDER_TOR to "TOR (127.0.0.1:9853)",
            PROVIDER_BYEDPI to "zDPI (127.0.0.1:1780)",
            PROVIDER_CUSTOM to "Пользовательский SOCKS5..."
        )
    }

    fun getByeDpiUpstreamOptions(): List<Pair<String, String>> {
        return listOf(
            PROVIDER_NONE to "Прямое соединение (без upstream)",
            PROVIDER_EASYVPN to "Easy-VPN (127.0.0.1:${Prefs.easyVpnProxyPort})",
            PROVIDER_TOR to "TOR (127.0.0.1:9853)",
            PROVIDER_CUSTOM to "Пользовательский SOCKS5..."
        )
    }
}

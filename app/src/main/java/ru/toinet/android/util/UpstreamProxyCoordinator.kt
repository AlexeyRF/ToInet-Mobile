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
    const val PROVIDER_ZAPRET = "zapret"
    const val PROVIDER_REHAB = "rehab"
    const val PROVIDER_CUSTOM = "custom"

    fun getPortForProvider(provider: String): Int {
        return when (provider) {
            PROVIDER_EASYVPN -> Prefs.easyVpnProxyPort
            PROVIDER_BYEDPI -> 1080
            PROVIDER_TOR -> 5242
            PROVIDER_ZAPRET -> Prefs.zapretPort
            PROVIDER_REHAB -> 1788
            else -> 1080
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
                    Log.i(TAG, "Ensuring ByeDPI is started as upstream")
                    val intent = Intent(context, ru.toinet.android.byedpi.services.ByeDpiProxyService::class.java).apply {
                        action = ru.toinet.android.vpn.data.START_ACTION
                    }
                    context.sendIntentToService(intent, isForeground = Prefs.shouldServiceShowNotification("byedpi"))
                }
                PROVIDER_TOR -> {
                    Log.i(TAG, "Ensuring Tor is started as upstream")
                    val intent = Intent(context, ru.toinet.android.service.ToInetService::class.java).apply {
                        action = org.torproject.jni.TorService.ACTION_START
                    }
                    context.sendIntentToService(intent, isForeground = Prefs.shouldServiceShowNotification("tor"))
                }
                PROVIDER_ZAPRET -> {
                    Log.i(TAG, "Ensuring Zapret is started as upstream")
                    ru.toinet.android.zapret.ZapretService.start(context)
                }
                PROVIDER_REHAB -> {
                    Log.i(TAG, "Ensuring SocksRehabilitator is started as upstream")
                    ru.toinet.android.rehabilitator.RehabilitatorService.start(context)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start upstream service $provider", e)
        }
    }

    fun getTorUpstreamOptions(): List<Pair<String, String>> {
        return listOf(
            PROVIDER_NONE to "Прямое соединение (без upstream)",
            PROVIDER_EASYVPN to "Easy-VPN (127.0.0.1:10808) — для белых списков",
            PROVIDER_BYEDPI to "ByeDPI (127.0.0.1:1080)",
            PROVIDER_ZAPRET to "Zapret (127.0.0.1:1081)",
            PROVIDER_REHAB to "Socks Rehab (127.0.0.1:1788)",
            PROVIDER_CUSTOM to "Пользовательский SOCKS5 / HTTPS..."
        )
    }

    fun getEasyVpnUpstreamOptions(): List<Pair<String, String>> {
        return listOf(
            PROVIDER_NONE to "Прямое соединение (без upstream)",
            PROVIDER_TOR to "TOR (127.0.0.1:5242)",
            PROVIDER_BYEDPI to "ByeDPI (127.0.0.1:1080)",
            PROVIDER_ZAPRET to "Zapret (127.0.0.1:1081)",
            PROVIDER_REHAB to "Socks Rehab (127.0.0.1:1788)",
            PROVIDER_CUSTOM to "Пользовательский SOCKS5..."
        )
    }

    fun getByeDpiUpstreamOptions(): List<Pair<String, String>> {
        return listOf(
            PROVIDER_NONE to "Прямое соединение (без upstream)",
            PROVIDER_EASYVPN to "Easy-VPN (127.0.0.1:10808)",
            PROVIDER_TOR to "TOR (127.0.0.1:5242)",
            PROVIDER_ZAPRET to "Zapret (127.0.0.1:1081)",
            PROVIDER_REHAB to "Socks Rehab (127.0.0.1:1788)",
            PROVIDER_CUSTOM to "Пользовательский SOCKS5..."
        )
    }
}

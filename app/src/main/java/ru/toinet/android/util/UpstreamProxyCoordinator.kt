package ru.toinet.android.util

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.util.Log
import ru.toinet.android.vpn.rules.ProxifierRulesManager
import ru.toinet.android.vpn.services.AppVpnService
import java.net.InetSocketAddress
import java.net.Socket

object UpstreamProxyCoordinator {
    private const val TAG = "UpstreamCoordinator"

    const val PROVIDER_NONE = "none"
    const val PROVIDER_EASYVPN = "easyvpn"
    const val PROVIDER_BYEDPI = "byedpi"
    const val PROVIDER_TOR = "tor"
    const val PROVIDER_OPERAPROXY = "operaproxy"
    const val PROVIDER_SVPN = "svpn"
    const val PROVIDER_CUSTOM = "custom"

    fun getPortForProvider(provider: String): Int {
        return when (provider) {
            PROVIDER_EASYVPN -> Prefs.easyVpnProxyPort
            PROVIDER_BYEDPI -> 1780
            PROVIDER_TOR -> {
                val resolved = Prefs.torSocksPortResolved
                if (resolved in 1..65535) resolved else 9853
            }
            PROVIDER_OPERAPROXY -> Prefs.operaProxyBindAddress.split(":").last().toIntOrNull() ?: 1785
            PROVIDER_SVPN, "ssh" -> Prefs.svpnLocalPort
            else -> 1780
        }
    }

    private fun isPortListening(port: Int): Boolean {
        if (port !in 1..65535) return false
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress("127.0.0.1", port), 100)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun waitForUpstreamPort(port: Int, timeoutMs: Long = 1500L) {
        if (port !in 1..65535) return
        if (Looper.myLooper() == Looper.getMainLooper()) return
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (isPortListening(port)) return
            try {
                Thread.sleep(50)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    fun ensureUpstreamStarted(context: Context, provider: String) {
        if (provider.isBlank() || provider == PROVIDER_NONE || provider == PROVIDER_CUSTOM) {
            return
        }

        val normalized = if (provider == "ssh") PROVIDER_SVPN else provider
        val targetPort = getPortForProvider(normalized)
        if (isPortListening(targetPort)) {
            return
        }

        val activeVpn = AppVpnService.activeService
        val neededInVpn = if (Prefs.isGlobalVpnEnabled || activeVpn != null) {
            try {
                ProxifierRulesManager.getAllNeededVpnProviders()
            } catch (_: Throwable) {
                emptySet()
            }
        } else {
            emptySet()
        }
        val managedByVpn = (Prefs.isGlobalVpnEnabled || activeVpn != null) &&
            (neededInVpn.contains(normalized) || (normalized == PROVIDER_SVPN && neededInVpn.contains("ssh")))

        try {
            when (normalized) {
                PROVIDER_EASYVPN -> {
                    if (activeVpn != null && managedByVpn) {
                        Log.i(TAG, "Ensuring in-process EasyVPN is started inside AppVpnService")
                        activeVpn.ensureProviderStarted(PROVIDER_EASYVPN)
                    } else if (!managedByVpn) {
                        Log.i(TAG, "Ensuring EasyVPN is started as upstream")
                        ru.toinet.android.easyvpn.EasyVpnService.start(context)
                    } else {
                        Log.i(TAG, "Skipping standalone EasyVPN start from secondary process while VPN manages it")
                    }
                    waitForUpstreamPort(targetPort)
                }
                PROVIDER_OPERAPROXY -> {
                    if (!ArchHelper.isOperaSupported) return
                    if (activeVpn != null && managedByVpn) {
                        Log.i(TAG, "Ensuring in-process OperaProxy is started inside AppVpnService")
                        activeVpn.ensureProviderStarted(PROVIDER_OPERAPROXY)
                    } else if (!managedByVpn) {
                        Log.i(TAG, "Ensuring OperaProxy is started as upstream")
                        ru.toinet.android.operaproxy.OperaProxyService.start(context)
                    }
                    waitForUpstreamPort(targetPort)
                }
                PROVIDER_SVPN -> {
                    if (activeVpn != null && managedByVpn) {
                        Log.i(TAG, "Ensuring in-process SVPN is started inside AppVpnService")
                        activeVpn.ensureProviderStarted(PROVIDER_SVPN)
                    } else if (!managedByVpn) {
                        Log.i(TAG, "Ensuring SVPN is started as upstream")
                        ru.toinet.android.svpn.SvpnService.start(context)
                    }
                    waitForUpstreamPort(targetPort)
                }
                PROVIDER_BYEDPI -> {
                    Log.i(TAG, "Ensuring zDPI is started as upstream")
                    val intent = Intent(context, ru.toinet.android.byedpi.services.ByeDpiProxyService::class.java).apply {
                        action = ru.toinet.android.vpn.data.START_ACTION
                        putExtra("is_foreground", true)
                    }
                    context.sendIntentToService(intent, isForeground = true)
                    waitForUpstreamPort(targetPort)
                }
                PROVIDER_TOR -> {
                    Log.i(TAG, "Ensuring Tor is started as upstream")
                    val intent = Intent(context, ru.toinet.android.service.ToInetService::class.java).apply {
                        action = org.torproject.jni.TorService.ACTION_START
                        putExtra("is_foreground", true)
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

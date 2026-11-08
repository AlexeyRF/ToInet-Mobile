package ru.toinet.android.util

import android.content.Context
import android.os.Build
import ru.toinet.android.vpn.services.AppVpnService
import java.net.InetAddress

object AddressStatusHelper {

    const val STATUS_HIDDEN = "Состояние адреса: скрыто"
    const val STATUS_LEAK = "Состояние адреса: утечка"
    const val STATUS_VISIBLE_ALL = "Состояние адреса: виден всем"

    @JvmStatic
    fun isLoopbackAddress(host: String): Boolean {
        val clean = host.trim().lowercase()
        if (clean.isEmpty() || clean == "localhost" || clean == "::1" || clean == "0.0.0.0") {
            return true
        }
        if (clean.startsWith("127.")) {
            return true
        }
        return try {
            val addr = InetAddress.getByName(clean)
            addr.isLoopbackAddress || addr.isAnyLocalAddress
        } catch (_: Exception) {
            false
        }
    }

    @JvmStatic
    fun isIpChangingProvider(provider: String): Boolean {
        return when (provider) {
            "tor", "easyvpn", "operaproxy" -> true
            else -> false
        }
    }

    @JvmStatic
    fun isRuPackage(pkgName: String): Boolean {
        return pkgName.contains("ru.", ignoreCase = true) || pkgName.contains(".ru", ignoreCase = true)
    }

    @JvmStatic
    fun isUsedForRuApps(context: Context): Boolean {
        // If Proxifier (per-app routing) is active
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && Prefs.appRoutingEnabled) {
            try {
                val rules = ru.toinet.android.vpn.rules.ProxifierRulesManager.getRules()
                for (rule in rules) {
                    if (!rule.enabled) continue
                    for (pkg in rule.targetPackages) {
                        if (isRuPackage(pkg) && isIpChangingProvider(rule.action)) {
                            return true
                        }
                    }
                }
            } catch (_: Exception) {}
            return false
        }

        // Standard VPN: check if any installed RU app is NOT excluded
        val excluded = Prefs.vpnExcludedApps
        val pm = context.packageManager
        return try {
            val packages = pm.getInstalledPackages(0)
            packages.any { pkg ->
                val name = pkg.packageName
                isRuPackage(name) && !excluded.contains(name)
            }
        } catch (_: Exception) {
            false
        }
    }

    @JvmStatic
    fun getAddressStatus(context: Context): String {
        val isVpnActive = Prefs.isGlobalVpnEnabled && AppVpnService.activeService != null

        if (!isVpnActive) {
            return STATUS_VISIBLE_ALL
        }

        val provider = Prefs.vpnProvider

        // If VPN is provided by a non-IP-changing provider (fakevpn, byedpi)
        if (!isIpChangingProvider(provider)) {
            return STATUS_VISIBLE_ALL
        }

        // It is an IP-changing provider (tor, easyvpn, operaproxy, or remote socks rehab)
        val ruUsed = isUsedForRuApps(context)
        if (ruUsed) {
            return STATUS_LEAK
        }

        // Check if VPN is used for the whole device (not per-app Proxifier)
        val isWholeDevice = !(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && Prefs.appRoutingEnabled)
        if (isWholeDevice) {
            return STATUS_HIDDEN
        }

        return STATUS_VISIBLE_ALL
    }
}

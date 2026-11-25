package ru.toinet.android.util

import android.content.Context
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

    private val ruCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    private val RU_ECOSYSTEM_KEYWORDS = arrayOf(
        // Search & Portals & Tech
        "yandex", "ya.ru", "kinopoisk", "dzen", "eda.yandex", "lavka",
        // VK & Mail.ru
        "vkontakte", "vk.com", "vk.me", "mailru", "mail.ru", "my.mail", "mygames", "odnoklassniki", "ok.ru",
        // Banks & FinTech
        "sberbank", "sber", "tcsbank", "tinkoff", "tbank", "alfabank", "vtb", "gazprombank",
        "raiffeisen", "sovkombank", "rshb", "psbank", "openbank", "yoomoney", "nspk", "mirpay", "sbp",
        // Government & Public Services
        "gosuslugi", "nalog", "mos.ru", "emias", "pension", "fssprus", "gibdd",
        // Marketplaces & Delivery & Stores
        "wildberries", "ozon", "avito", "cdek", "boxberry", "samokat", "megamarket", "kufar",
        // Navigation & Transport
        "dublgis", "2gis", "citimobil", "yandexnavi", "yandextraffic", "maxim", "rzd",
        // Media & Video & Streaming
        "rutube", "okko", "premier", "start.ru", "kion", "ivi.ru", "smotrim", "amediateka", "wink",
        // Telecoms
        "mts", "megafon", "beeline", "tele2", "rostelecom", "yota", "t2mobile",
        // Classifieds & Career
        "headhunter", "hh.ru", "auto.ru", "drom", "cian", "domclick",
        // Security & Software
        "kaspersky", "drweb", "myoffice", "astralinux", "1c"
    )

    @JvmStatic
    fun isRuPackage(pkgName: String): Boolean {
        if (pkgName.isBlank()) return false
        val cached = ruCache[pkgName]
        if (cached != null) return cached

        val lower = pkgName.lowercase()
        val isRu = lower.startsWith("ru.") ||
                lower.endsWith(".ru") ||
                lower.contains(".ru.") ||
                lower.startsWith("su.") ||
                lower.endsWith(".su") ||
                lower.contains(".su.") ||
                lower.startsWith("рф.") ||
                lower.endsWith(".рф") ||
                lower.contains(".рф.") ||
                RU_ECOSYSTEM_KEYWORDS.any { lower.contains(it) }

        ruCache[pkgName] = isRu
        return isRu
    }

    @JvmStatic
    fun isUsedForRuApps(context: Context): Boolean {
        // If Proxifier (per-app routing) is active
        if (Prefs.appRoutingEnabled) {
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

        // If VPN is provided by a non-IP-changing provider (byedpi)
        if (!isIpChangingProvider(provider)) {
            return STATUS_VISIBLE_ALL
        }

        // It is an IP-changing provider (tor, easyvpn, operaproxy, or remote socks rehab)
        val ruUsed = isUsedForRuApps(context)
        if (ruUsed) {
            return STATUS_LEAK
        }

        // Check if VPN is used for the whole device (not per-app Proxifier)
        val isWholeDevice = !Prefs.appRoutingEnabled
        if (isWholeDevice) {
            return STATUS_HIDDEN
        }

        return STATUS_VISIBLE_ALL
    }
}

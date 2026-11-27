package ru.toinet.android.vpn.rules

import android.util.Log
import org.json.JSONArray
import ru.toinet.android.util.ArchHelper
import ru.toinet.android.util.Prefs

object ProxifierRulesManager {
    private const val TAG = "ProxifierRulesManager"

    private val lock = Any()

    private data class CacheHolder(
        val rules: List<ProxifierRule>,
        val packageToRuleMap: Map<String, ProxifierRule>,
        val neededProviders: Set<String>,
        val version: Long,
        val defaultAction: String,
        val profileKey: String
    )

    @Volatile
    private var cacheHolder: CacheHolder? = null

    private fun getOrLoadCache(): CacheHolder {
        val currentProfile = Prefs.getActiveVpnProfileKey()
        val currentVersion = Prefs.appRoutingRulesVersion
        val currentDefault = Prefs.getAppRoutingDefaultActionForProfile(currentProfile)
        cacheHolder?.let {
            if (it.profileKey == currentProfile &&
                it.version == currentVersion &&
                it.defaultAction == currentDefault
            ) {
                return it
            }
        }
        return synchronized(lock) {
            val prof = Prefs.getActiveVpnProfileKey()
            val ver = Prefs.appRoutingRulesVersion
            val def = Prefs.getAppRoutingDefaultActionForProfile(prof)
            cacheHolder?.let {
                if (it.profileKey == prof && it.version == ver && it.defaultAction == def) {
                    return it
                }
            }
            val loaded = loadRulesFromPrefs(prof)
            val newCache = buildCacheHolder(loaded, ver, def, prof)
            cacheHolder = newCache
            newCache
        }
    }

    private fun buildCacheHolder(
        rules: List<ProxifierRule>,
        version: Long = Prefs.appRoutingRulesVersion,
        defaultAction: String = Prefs.appRoutingDefaultAction,
        profileKey: String = Prefs.getActiveVpnProfileKey()
    ): CacheHolder {
        val map = mutableMapOf<String, ProxifierRule>()
        val needed = mutableSetOf<String>()

        for (rule in rules) {
            if (rule.enabled) {
                for (pkg in rule.targetPackages) {
                    if (!map.containsKey(pkg)) {
                        map[pkg] = rule
                    }
                }
                if (rule.action != ProxifierRule.ACTION_DIRECT && rule.action != ProxifierRule.ACTION_BLOCK) {
                    needed.add(rule.action)
                }
            }
        }

        if (defaultAction != ProxifierRule.ACTION_DIRECT && defaultAction != ProxifierRule.ACTION_BLOCK) {
            needed.add(defaultAction)
        }

        return CacheHolder(rules, map, needed, version, defaultAction, profileKey)
    }

    fun getRules(): MutableList<ProxifierRule> {
        val holder = getOrLoadCache()
        return holder.rules.map { it.copy(targetPackages = it.targetPackages.toMutableList()) }.toMutableList()
    }

    fun getRulesForProfile(profile: String): List<ProxifierRule> {
        if (profile == Prefs.getActiveVpnProfileKey()) {
            return getRules()
        }
        return synchronized(lock) {
            loadRulesFromPrefs(profile).map { it.copy(targetPackages = it.targetPackages.toMutableList()) }
        }
    }

    private fun loadRulesFromPrefs(profile: String): List<ProxifierRule> {
        val raw = Prefs.getAppRoutingRulesJsonForProfile(profile)
        if (raw.isBlank()) {
            val defaults = createDefaultRules()
            saveRulesInternal(profile, defaults)
            return defaults
        }
        return try {
            val list = mutableListOf<ProxifierRule>()
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                list.add(ProxifierRule.fromJson(arr.getJSONObject(i)))
            }
            list
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse rules json for profile=$profile", e)
            val defaults = createDefaultRules()
            saveRulesInternal(profile, defaults)
            defaults
        }
    }

    fun saveRules(rules: List<ProxifierRule>) {
        saveRulesForProfile(Prefs.getActiveVpnProfileKey(), rules)
    }

    fun saveRulesForProfile(profile: String, rules: List<ProxifierRule>) {
        synchronized(lock) {
            saveRulesInternal(profile, rules)
            if (profile == Prefs.getActiveVpnProfileKey()) {
                val newVersion = maxOf(System.currentTimeMillis(), Prefs.appRoutingRulesVersion + 1L)
                Prefs.appRoutingRulesVersion = newVersion
                val defensiveCopy = rules.map { it.copy(targetPackages = it.targetPackages.toMutableList()) }
                cacheHolder = buildCacheHolder(
                    defensiveCopy,
                    newVersion,
                    Prefs.getAppRoutingDefaultActionForProfile(profile),
                    profile
                )
            }
        }
    }

    private fun saveRulesInternal(profile: String, rules: List<ProxifierRule>) {
        val arr = JSONArray()
        rules.forEach { arr.put(it.toJson()) }
        Prefs.setAppRoutingRulesJsonForProfile(profile, arr.toString())
    }

    fun invalidateCache() {
        synchronized(lock) {
            Prefs.appRoutingRulesVersion = maxOf(System.currentTimeMillis(), Prefs.appRoutingRulesVersion + 1L)
            cacheHolder = null
        }
    }

    fun createDefaultRules(): List<ProxifierRule> {
        return listOf(
            ProxifierRule(
                name = "Telegram",
                enabled = true,
                targetPackages = mutableListOf("org.telegram.messenger", "org.telegram.messenger.web"),
                action = ProxifierRule.ACTION_EASYVPN
            ),
            ProxifierRule(
                name = "YouTube",
                enabled = true,
                targetPackages = mutableListOf(
                    "com.google.android.youtube",
                    "com.google.android.apps.youtube.music",
                    "com.google.android.youtube.tv",
                    "com.google.android.youtube.googletv",
                    "app.revanced.android.youtube"
                ),
                action = ProxifierRule.ACTION_BYEDPI
            ),
            ProxifierRule(
                name = "Браузеры",
                enabled = true,
                targetPackages = mutableListOf(
                    "com.android.chrome",
                    "org.mozilla.firefox",
                    "com.opera.browser",
                    "com.opera.mini.native",
                    "com.opera.gx",
                    "com.yandex.browser",
                    "com.sec.android.app.sbrowser",
                    "com.microsoft.emmx",
                    "com.brave.browser",
                    "com.duckduckgo.mobile.android",
                    "com.kiwibrowser.browser",
                    "com.vivaldi.browser"
                ),
                action = ProxifierRule.ACTION_EASYVPN
            ),
            ProxifierRule(
                name = "Social & Media",
                enabled = true,
                targetPackages = mutableListOf("com.instagram.android", "com.twitter.android", "com.facebook.katana"),
                action = ProxifierRule.ACTION_EASYVPN
            ),
            ProxifierRule(
                name = "Tor Browser",
                enabled = true,
                targetPackages = mutableListOf("org.torproject.torbrowser"),
                action = ProxifierRule.ACTION_TOR
            )
        )
    }

    fun findRuleForPackages(packages: Array<String>): ProxifierRule? {
        val rules = getOrLoadCache().rules
        val pkgSet = packages.toSet()
        // Priority order: first matching enabled rule in the list wins
        for (rule in rules) {
            if (rule.enabled && rule.targetPackages.any { pkgSet.contains(it) }) {
                return rule
            }
        }
        return null
    }

    fun findRuleForPackage(packageName: String): ProxifierRule? {
        if (Prefs.isLockedVpnPackage(packageName)) return null
        val rules = getOrLoadCache().rules
        // Priority order: first matching enabled rule in the list wins
        for (rule in rules) {
            if (rule.enabled && rule.targetPackages.contains(packageName)) {
                return rule
            }
        }
        return null
    }

/* Domain filtering disabled: per-app routing only
    fun findRuleForDomain(domain: String): ProxifierRule? {
        val lower = domain.lowercase().trim()
        if (lower.isBlank()) return null
        val rules = getOrLoadCache().rules

        // 1. Direct domain match in targetPackages in priority order
        for (rule in rules) {
            if (!rule.enabled) continue
            for (target in rule.targetPackages) {
                val t = target.lowercase().trim()
                if (t.isNotBlank()) {
                    if (lower == t || lower.endsWith(".$t") || (t.startsWith("*.") && lower.endsWith(t.removePrefix("*")))) {
                        return rule
                    }
                }
            }
        }

        // 2. Known domain categories matched against user rules in priority order
        for (rule in rules) {
            if (!rule.enabled) continue
            // Check YouTube
            if (isYouTubeDomain(lower) && (rule.targetPackages.any { isYouTubePackage(it) } || rule.name.contains("YouTube", ignoreCase = true))) {
                return rule
            }
            // Check Telegram
            if (isTelegramDomain(lower) && (rule.targetPackages.any { isTelegramPackage(it) } || rule.name.contains("Telegram", ignoreCase = true))) {
                return rule
            }
            // Check Social & Media
            if (isSocialDomain(lower) && (rule.targetPackages.any { isSocialPackage(it) } || rule.name.contains("Social", ignoreCase = true) || rule.name.contains("Media", ignoreCase = true))) {
                return rule
            }
        }

        return null
    }

    private fun isYouTubePackage(pkg: String): Boolean {
        val p = pkg.lowercase()
        return p.contains("youtube") || p.contains("revanced") || p == "com.google.android.youtube"
    }

    private fun isTelegramPackage(pkg: String): Boolean {
        val p = pkg.lowercase()
        return p.contains("telegram") || p == "org.telegram.messenger"
    }

    private fun isSocialPackage(pkg: String): Boolean {
        val p = pkg.lowercase()
        return p.contains("instagram") || p.contains("twitter") || p.contains("facebook")
    }

    private fun isYouTubeDomain(domain: String): Boolean {
        return domain == "youtube.com" || domain.endsWith(".youtube.com") ||
               domain == "googlevideo.com" || domain.endsWith(".googlevideo.com") ||
               domain == "ytimg.com" || domain.endsWith(".ytimg.com") ||
               domain == "ggpht.com" || domain.endsWith(".ggpht.com") ||
               domain == "youtu.be" || domain == "yt.be" ||
               domain == "youtube-nocookie.com" || domain.endsWith(".youtube-nocookie.com")
    }

    private fun isTelegramDomain(domain: String): Boolean {
        return domain == "telegram.org" || domain.endsWith(".telegram.org") ||
               domain == "t.me" || domain.endsWith(".t.me") ||
               domain == "telegram.me" || domain.endsWith(".telegram.me") ||
               domain == "telegra.ph"
    }

    private fun isSocialDomain(domain: String): Boolean {
        return domain == "instagram.com" || domain.endsWith(".instagram.com") ||
               domain == "cdninstagram.com" || domain.endsWith(".cdninstagram.com") ||
               domain == "twitter.com" || domain.endsWith(".twitter.com") ||
               domain == "x.com" || domain.endsWith(".x.com") ||
               domain == "twimg.com" || domain.endsWith(".twimg.com") ||
               domain == "facebook.com" || domain.endsWith(".facebook.com") ||
               domain == "fbcdn.net" || domain.endsWith(".fbcdn.net")
    }
*/

    fun findActionForPackage(packageName: String): String {
        if (Prefs.isLockedVpnPackage(packageName)) return ProxifierRule.ACTION_DIRECT
        return findRuleForPackage(packageName)?.action ?: Prefs.appRoutingDefaultAction
    }

    fun getNeededProviders(): Set<String> {
        return getOrLoadCache().neededProviders
    }

    fun getAllNeededVpnProviders(): Set<String> {
        val needed = getNeededProviders().toMutableSet()
        // Resolve one-hop and chained upstreams cleanly
        repeat(2) {
            if (needed.contains(ProxifierRule.ACTION_TOR) && Prefs.proxyEnabled) {
                var u = Prefs.torUpstreamProvider
                if (u.isBlank() || u == "none") {
                    val pair = Prefs.outboundProxy.first
                    if (pair != null && pair.host == "127.0.0.1") {
                        u = when (pair.port) {
                            Prefs.easyVpnProxyPort -> ProxifierRule.ACTION_EASYVPN
                            1780 -> ProxifierRule.ACTION_BYEDPI
                            else -> u
                        }
                    }
                }
                if (u != "none" && u != "custom" && u.isNotBlank()) {
                    needed.add(u)
                }
            }
            if (needed.contains(ProxifierRule.ACTION_EASYVPN) && Prefs.easyVpnUpstreamEnabled) {
                val u = Prefs.easyVpnUpstreamProvider
                if (u != "none" && u != "custom" && u.isNotBlank()) {
                    needed.add(u)
                }
            }
            if (needed.contains(ProxifierRule.ACTION_BYEDPI) && Prefs.byedpiUpstreamEnabled) {
                val u = Prefs.byedpiUpstreamProvider
                if (u != "none" && u != "custom" && u.isNotBlank()) {
                    needed.add(u)
                }
            }
        }
        return needed
    }

    fun getAvailableActions(): List<Pair<String, String>> {
        val list = mutableListOf(
            ProxifierRule.ACTION_DIRECT to ProxifierRule.getActionTitle(ProxifierRule.ACTION_DIRECT),
            ProxifierRule.ACTION_BLOCK to ProxifierRule.getActionTitle(ProxifierRule.ACTION_BLOCK),
            ProxifierRule.ACTION_BYEDPI to ProxifierRule.getActionTitle(ProxifierRule.ACTION_BYEDPI),
            ProxifierRule.ACTION_EASYVPN to ProxifierRule.getActionTitle(ProxifierRule.ACTION_EASYVPN),
            ProxifierRule.ACTION_TOR to ProxifierRule.getActionTitle(ProxifierRule.ACTION_TOR),
            ProxifierRule.ACTION_SVPN to ProxifierRule.getActionTitle(ProxifierRule.ACTION_SVPN)
        )
        if (ArchHelper.isOperaSupported) {
            list.add(ProxifierRule.ACTION_OPERAPROXY to ProxifierRule.getActionTitle(ProxifierRule.ACTION_OPERAPROXY))
        }
        return list
    }
}

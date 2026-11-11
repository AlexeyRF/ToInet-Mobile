package ru.toinet.android.vpn.rules

import android.util.Log
import org.json.JSONArray
import ru.toinet.android.util.ArchHelper
import ru.toinet.android.util.Prefs

object ProxifierRulesManager {
    private const val TAG = "ProxifierRulesManager"

    private val lock = Any()

    @Volatile
    private var cachedRules: List<ProxifierRule>? = null

    @Volatile
    private var packageToRuleMap: Map<String, ProxifierRule> = emptyMap()

    @Volatile
    private var cachedNeededProviders: Set<String>? = null

    private fun ensureLoaded(): List<ProxifierRule> {
        cachedRules?.let { return it }
        return synchronized(lock) {
            cachedRules?.let { return it }
            val loaded = loadRulesFromPrefs()
            updateInternalCache(loaded)
            loaded
        }
    }

    private fun updateInternalCache(rules: List<ProxifierRule>) {
        cachedRules = rules

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

        val def = Prefs.appRoutingDefaultAction
        if (def != ProxifierRule.ACTION_DIRECT && def != ProxifierRule.ACTION_BLOCK) {
            needed.add(def)
        }

        packageToRuleMap = map
        cachedNeededProviders = needed
    }

    fun getRules(): MutableList<ProxifierRule> {
        val rules = ensureLoaded()
        return rules.map { it.copy(targetPackages = it.targetPackages.toMutableList()) }.toMutableList()
    }

    private fun loadRulesFromPrefs(): List<ProxifierRule> {
        val raw = Prefs.appRoutingRulesJson
        if (raw.isBlank()) {
            val defaults = createDefaultRules()
            saveRulesInternal(defaults)
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
            Log.e(TAG, "Failed to parse rules json", e)
            val defaults = createDefaultRules()
            saveRulesInternal(defaults)
            defaults
        }
    }

    fun saveRules(rules: List<ProxifierRule>) {
        synchronized(lock) {
            saveRulesInternal(rules)
            val defensiveCopy = rules.map { it.copy(targetPackages = it.targetPackages.toMutableList()) }
            updateInternalCache(defensiveCopy)
        }
    }

    private fun saveRulesInternal(rules: List<ProxifierRule>) {
        val arr = JSONArray()
        rules.forEach { arr.put(it.toJson()) }
        Prefs.appRoutingRulesJson = arr.toString()
    }

    fun invalidateCache() {
        synchronized(lock) {
            cachedRules = null
            cachedNeededProviders = null
            packageToRuleMap = emptyMap()
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
                targetPackages = mutableListOf("com.google.android.youtube"),
                action = ProxifierRule.ACTION_BYEDPI
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

    fun findRuleForPackage(packageName: String): ProxifierRule? {
        ensureLoaded()
        return packageToRuleMap[packageName]
    }

    fun findActionForPackage(packageName: String): String {
        return findRuleForPackage(packageName)?.action ?: Prefs.appRoutingDefaultAction
    }

    fun getNeededProviders(): Set<String> {
        ensureLoaded()
        return cachedNeededProviders ?: emptySet()
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

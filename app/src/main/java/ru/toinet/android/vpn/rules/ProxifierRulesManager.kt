package ru.toinet.android.vpn.rules

import android.content.Context
import android.util.Log
import org.json.JSONArray
import ru.toinet.android.util.ArchHelper
import ru.toinet.android.util.Prefs

object ProxifierRulesManager {
    private const val TAG = "ProxifierRulesManager"

    fun getRules(): MutableList<ProxifierRule> {
        val raw = Prefs.appRoutingRulesJson
        if (raw.isBlank()) {
            val defaults = createDefaultRules()
            saveRules(defaults)
            return defaults.toMutableList()
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
            saveRules(defaults)
            defaults.toMutableList()
        }
    }

    fun saveRules(rules: List<ProxifierRule>) {
        val arr = JSONArray()
        rules.forEach { arr.put(it.toJson()) }
        Prefs.appRoutingRulesJson = arr.toString()
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
        val rules = getRules()
        for (rule in rules) {
            if (rule.enabled && rule.targetPackages.contains(packageName)) {
                return rule
            }
        }
        return null
    }

    fun findActionForPackage(packageName: String): String {
        return findRuleForPackage(packageName)?.action ?: Prefs.appRoutingDefaultAction
    }

    fun getNeededProviders(): Set<String> {
        val set = mutableSetOf<String>()
        val rules = getRules()
        for (rule in rules) {
            if (rule.enabled && rule.action != ProxifierRule.ACTION_DIRECT && rule.action != ProxifierRule.ACTION_BLOCK) {
                set.add(rule.action)
            }
        }
        val def = Prefs.appRoutingDefaultAction
        if (def != ProxifierRule.ACTION_DIRECT && def != ProxifierRule.ACTION_BLOCK) {
            set.add(def)
        }
        return set
    }

    fun getAvailableActions(): List<Pair<String, String>> {
        val list = mutableListOf(
            ProxifierRule.ACTION_DIRECT to ProxifierRule.getActionTitle(ProxifierRule.ACTION_DIRECT),
            ProxifierRule.ACTION_BLOCK to ProxifierRule.getActionTitle(ProxifierRule.ACTION_BLOCK),
            ProxifierRule.ACTION_BYEDPI to ProxifierRule.getActionTitle(ProxifierRule.ACTION_BYEDPI),
            ProxifierRule.ACTION_EASYVPN to ProxifierRule.getActionTitle(ProxifierRule.ACTION_EASYVPN),
            ProxifierRule.ACTION_TOR to ProxifierRule.getActionTitle(ProxifierRule.ACTION_TOR),
            ProxifierRule.ACTION_ZAPRET to ProxifierRule.getActionTitle(ProxifierRule.ACTION_ZAPRET),
            ProxifierRule.ACTION_REHAB to ProxifierRule.getActionTitle(ProxifierRule.ACTION_REHAB),
            ProxifierRule.ACTION_FAKEVPN to ProxifierRule.getActionTitle(ProxifierRule.ACTION_FAKEVPN)
        )
        if (ArchHelper.isOperaSupported) {
            list.add(ProxifierRule.ACTION_OPERAPROXY to ProxifierRule.getActionTitle(ProxifierRule.ACTION_OPERAPROXY))
        }
        return list
    }
}

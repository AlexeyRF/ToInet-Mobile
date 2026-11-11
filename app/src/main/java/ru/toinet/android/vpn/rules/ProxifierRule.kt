package ru.toinet.android.vpn.rules

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class ProxifierRule(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var enabled: Boolean = true,
    val targetPackages: MutableList<String> = mutableListOf(),
    var action: String = ACTION_DIRECT
) {
    companion object {
        const val ACTION_DIRECT = "direct"
        const val ACTION_BLOCK = "block"
        const val ACTION_BYEDPI = "byedpi"
        const val ACTION_EASYVPN = "easyvpn"
        const val ACTION_TOR = "tor"
        const val ACTION_OPERAPROXY = "operaproxy"
        const val ACTION_SVPN = "svpn"
        const val ACTION_TGWS = "tgws"

        fun getActionTitle(action: String): String = when (action) {
            ACTION_DIRECT -> "Direct (Напрямую)"
            ACTION_BLOCK -> "Block (Блокировать)"
            ACTION_BYEDPI -> "zDPI (порт 1780)"
            ACTION_EASYVPN -> "Easy-VPN (Xray)"
            ACTION_TOR -> "TOR (порт 9853)"
            ACTION_OPERAPROXY -> "Opera Proxy (1785)"
            ACTION_SVPN -> "SVPN / SSH (${ru.toinet.android.util.Prefs.svpnLocalPort})"
            ACTION_TGWS -> "TGWS (порт ${ru.toinet.android.util.Prefs.tgwsPort})"
            else -> action
        }

        fun fromJson(obj: JSONObject): ProxifierRule {
            val id = obj.optString("id", UUID.randomUUID().toString())
            val name = obj.optString("name", "Правило")
            val enabled = obj.optBoolean("enabled", true)
            val action = obj.optString("action", ACTION_DIRECT)
            val pkgs = mutableListOf<String>()
            val arr = obj.optJSONArray("targetPackages")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    pkgs.add(arr.getString(i))
                }
            }
            return ProxifierRule(id, name, enabled, pkgs, action)
        }
    }

    fun toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("id", id)
        obj.put("name", name)
        obj.put("enabled", enabled)
        obj.put("action", action)
        val arr = JSONArray()
        targetPackages.forEach { arr.put(it) }
        obj.put("targetPackages", arr)
        return obj
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ProxifierRule) return false
        return id == other.id
    }

    override fun hashCode(): Int {
        return id.hashCode()
    }
}

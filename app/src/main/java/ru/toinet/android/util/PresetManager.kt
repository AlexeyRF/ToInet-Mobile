package ru.toinet.android.util

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import ru.toinet.android.operaproxy.OperaProxyService
import ru.toinet.android.tgws.TgwsService
import ru.toinet.android.service.ToInetService

object PresetManager {
    private const val TAG = "PresetManager"

    fun createSnapshot(context: Context): String {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val all = prefs.all
        val json = JSONObject()
        for (entry in all.entries) {
            val key = entry.key
            val value = entry.value
            if (key.startsWith("preset_")) continue // don't recursively save presets
            try {
                json.put(key, value)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save pref: $key", e)
            }
        }
        return json.toString()
    }

    fun applySnapshot(context: Context, snapshotJson: String) {
        if (snapshotJson.isBlank()) return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val json = JSONObject(snapshotJson)
                val currentPrefs = PreferenceManager.getDefaultSharedPreferences(context)
                val oldSnapshot = createSnapshot(context)
                val oldJson = JSONObject(oldSnapshot)

                val editor = currentPrefs.edit()
                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    when (val value = json.get(key)) {
                        is Boolean -> editor.putBoolean(key, value)
                        is String -> editor.putString(key, value)
                        is Int -> editor.putInt(key, value)
                        is Long -> editor.putLong(key, value)
                        is Double -> editor.putFloat(key, value.toFloat())
                        is Float -> editor.putFloat(key, value)
                    }
                }
                editor.commit()

                val mode = Prefs.networkSwitchMode
                if (mode == "restart_all") {
                    restartAll(context)
                } else {
                    smartSwitch(context, oldJson, json)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Failed to apply preset", e)
            }
        }
    }

    suspend fun restartAll(context: Context) {
        stopAll(context)
        delay(1000)
        startEnabled(context)
    }

    private suspend fun smartSwitch(context: Context, oldJson: JSONObject, newJson: JSONObject) {
        var needsDelay = false

        // Tor
        if (oldJson.optBoolean("tor_enabled") && (!newJson.optBoolean("tor_enabled") || changed(oldJson, newJson, listOf("tor_bridges", "tor_transport", "pref_use_bridges", "pref_open_proxy_on_all_interfaces")))) {
            context.sendIntentToService(Intent(context, ToInetService::class.java).apply {
                action = org.torproject.jni.TorService.ACTION_STOP
            }, isForeground = false)
            needsDelay = true
        }

        /*
        // TGWS
        if (oldJson.optBoolean("tgws_enabled") && (!newJson.optBoolean("tgws_enabled") || changed(oldJson, newJson, listOf("tgws_port", "tgws_secret", "tgws_fake_tls", "tgws_dc_mappings", "pref_open_proxy_on_all_interfaces")))) {
            TgwsService.stop(context)
            needsDelay = true
        }
        */

        // ByeDPI
        if (oldJson.optBoolean("byedpi_enabled") && (!newJson.optBoolean("byedpi_enabled") || changed(oldJson, newJson, listOf("byedpi_cmd_args", "byedpi_enable_cmd_settings", "pref_open_proxy_on_all_interfaces")))) {
            ru.toinet.android.vpn.services.ServiceManager.stop(context)
            needsDelay = true
        }

        // Opera Proxy
        if (oldJson.optBoolean("operaproxy_enabled") && (!newJson.optBoolean("operaproxy_enabled") || changed(oldJson, newJson, listOf("operaproxy_bind_address", "operaproxy_upstream", "operaproxy_preset", "opera_proxy_verbose", "pref_open_proxy_on_all_interfaces")))) {
            OperaProxyService.stop(context)
            needsDelay = true
        }
        // EasyVPN (standalone proxy)
        if (oldJson.optBoolean("easyvpn_proxy_enabled") && (!newJson.optBoolean("easyvpn_proxy_enabled") || changed(oldJson, newJson, listOf("pref_easy_vpn_subs", "pref_open_proxy_on_all_interfaces")))) {
            ru.toinet.android.easyvpn.EasyVpnService.stop(context)
            needsDelay = true
        }

        // SVPN (SSH standalone proxy)
        if (oldJson.optBoolean("pref_svpn_enabled") && (!newJson.optBoolean("pref_svpn_enabled") || changed(oldJson, newJson, listOf("pref_svpn_host", "pref_svpn_port", "pref_svpn_user", "pref_svpn_password", "pref_svpn_private_key", "pref_svpn_local_port", "pref_open_proxy_on_all_interfaces")))) {
            ru.toinet.android.svpn.SvpnService.stop(context)
            needsDelay = true
        }

        // VPN
        if (oldJson.optBoolean("pref_global_vpn_enabled") && (!newJson.optBoolean("pref_global_vpn_enabled") || oldJson.optString("pref_vpn_provider") != newJson.optString("pref_vpn_provider") || changed(oldJson, newJson, listOf("pref_easy_vpn_subs", "custom_socks_ip", "custom_socks_port", "pref_open_proxy_on_all_interfaces", "pref_vpn_bypass_lan")))) {
            context.sendIntentToService(Intent(context, ru.toinet.android.vpn.services.AppVpnService::class.java).apply {
                action = ru.toinet.android.vpn.data.STOP_ACTION
            }, isForeground = false)
            needsDelay = true
        }

        if (needsDelay) {
            delay(500)
        }

        startEnabled(context)
    }

    private fun changed(old: JSONObject, new: JSONObject, keys: List<String>): Boolean {
        for (key in keys) {
            if (old.opt(key) != new.opt(key)) return true
        }
        return false
    }

    fun stopAll(context: Context) {
        context.sendIntentToService(org.torproject.jni.TorService.ACTION_STOP)
    }

    private fun startEnabled(context: Context) {
        context.sendIntentToService(org.torproject.jni.TorService.ACTION_START)
    }
}

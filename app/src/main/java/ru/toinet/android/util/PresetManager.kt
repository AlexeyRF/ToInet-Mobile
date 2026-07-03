package ru.toinet.android.util

import android.content.Context
import android.util.Log
import org.json.JSONObject
import ru.toinet.android.byedpi.services.ServiceManager
import ru.toinet.android.operaproxy.OperaProxyService
import ru.toinet.android.rehabilitator.RehabilitatorService
import ru.toinet.android.tgws.TgwsService
import ru.toinet.android.turnproxy.TurnProxyService
import ru.toinet.android.tgws.UniversalTgProxy
import android.content.Intent
import androidx.preference.PreferenceManager

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
                    is Float -> editor.putFloat(key, value)
                }
            }
            editor.apply()
            
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

    private fun restartAll(context: Context) {
        // Stop all
        stopAll(context)
        
        // Wait a bit
        Thread.sleep(1000)
        
        // Start enabled
        startEnabled(context)
    }

    private fun smartSwitch(context: Context, oldJson: JSONObject, newJson: JSONObject) {
        // Stop disabled or changed
        if (oldJson.optBoolean("tor_enabled") && (!newJson.optBoolean("tor_enabled") || changed(oldJson, newJson, listOf("tor_bridges", "tor_transport")))) {
            val intent = Intent(context, org.torproject.jni.TorService::class.java)
            intent.action = org.torproject.jni.TorService.ACTION_STOP
            context.startService(intent)
        }
        
        if (oldJson.optBoolean("tgws_enabled") && (!newJson.optBoolean("tgws_enabled") || changed(oldJson, newJson, listOf("tgws_host", "tgws_port", "tgws_secret", "tgws_faketls", "tgws_use_byedpi")))) {
            TgwsService.stop(context)
        }
        
        if (oldJson.optBoolean("byedpi_enabled") && (!newJson.optBoolean("byedpi_enabled") || changed(oldJson, newJson, listOf("byedpi_port", "byedpi_args")))) {
            ru.toinet.android.byedpi.services.ServiceManager.stop(context)
        }
        
        if (oldJson.optBoolean("turnproxy_enabled") && (!newJson.optBoolean("turnproxy_enabled") || changed(oldJson, newJson, listOf("turnproxy_port", "turnproxy_host")))) {
            TurnProxyService.stop(context)
        }
        
        if (oldJson.optBoolean("operaproxy_enabled") && (!newJson.optBoolean("operaproxy_enabled") || changed(oldJson, newJson, listOf("operaproxy_bind_address", "operaproxy_upstream", "operaproxy_use_byedpi")))) {
            OperaProxyService.stop(context)
        }
        
        if (oldJson.optBoolean("rehabilitator_enabled") && (!newJson.optBoolean("rehabilitator_enabled") || changed(oldJson, newJson, listOf("rehabilitator_bind_address")))) {
            RehabilitatorService.stop(context)
        }

        /*
        if (oldJson.optBoolean("universal_tg_proxy_enabled") && (!newJson.optBoolean("universal_tg_proxy_enabled") || changed(oldJson, newJson, listOf("universal_tg_proxy_provider")))) {
            ru.toinet.android.tgws.Gatik.stop()
        }
        */
        
        // VPN
        if (oldJson.optString("vpn_provider") != newJson.optString("vpn_provider")) {
            context.sendIntentToService(org.torproject.jni.TorService.ACTION_STOP)
        }
        
        Thread.sleep(500)
        
        // Start newly enabled or changed
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
        TgwsService.stop(context)
        ru.toinet.android.byedpi.services.ServiceManager.stop(context)
        TurnProxyService.stop(context)
        OperaProxyService.stop(context)
        RehabilitatorService.stop(context)
        //ru.toinet.android.tgws.Gatik.stop()
    }

    private fun startEnabled(context: Context) {
        if (Prefs.torEnabled) {
            val intent = Intent(context, org.torproject.jni.TorService::class.java)
            intent.action = org.torproject.jni.TorService.ACTION_START
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }
        if (Prefs.tgwsEnabled) {
            TgwsService.start(context, Prefs.tgwsHost, Prefs.tgwsPort, Prefs.tgwsDcMappings, Prefs.tgwsSecret, Prefs.tgwsFakeTls, Prefs.tgwsUseByeDpi)
        }
        if (Prefs.byedpiEnabled) {
            ru.toinet.android.byedpi.services.ServiceManager.start(context, ru.toinet.android.byedpi.data.Mode.Proxy)
        }
        if (Prefs.turnProxyEnabled) {
            TurnProxyService.start(context)
        }
        if (Prefs.operaProxyEnabled) {
            OperaProxyService.start(context)
        }
        if (Prefs.rehabilitatorEnabled) {
            RehabilitatorService.start(context)
        }
        /*
        if (Prefs.universalTgProxyEnabled) {
            ru.toinet.android.tgws.Gatik.start(context)
        }
        */
        if (Prefs.vpnProvider != "none" || Prefs.isGlobalVpnEnabled) {
            context.sendIntentToService(org.torproject.jni.TorService.ACTION_START)
        }
    }
}

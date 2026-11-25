package ru.toinet.android.vpn.services

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import ru.toinet.android.vpn.data.Mode
import ru.toinet.android.vpn.data.START_ACTION
import ru.toinet.android.vpn.data.STOP_ACTION
import ru.toinet.android.byedpi.services.ByeDpiProxyService
import ru.toinet.android.util.sendIntentToService
import ru.toinet.android.util.stopServiceCompat

object ServiceManager {
    private val TAG: String = ServiceManager::class.java.simpleName

    fun start(context: Context, mode: Mode) {
        when (mode) {
            Mode.VPN -> {
                Log.i(TAG, "Starting VPN")
                val intent = Intent(context, AppVpnService::class.java).apply {
                    action = START_ACTION
                    putExtra("is_foreground", true)
                }
                context.sendIntentToService(intent, isForeground = true)
            }

            Mode.Proxy -> {
                Log.i(TAG, "Starting proxy")
                val intent = Intent(context, ByeDpiProxyService::class.java).apply {
                    action = START_ACTION
                    putExtra("is_foreground", true)
                }
                context.sendIntentToService(intent, isForeground = true)
            }
        }
    }

    fun stop(context: Context) {
        val (_, mode) = appStatus
        when (mode) {
            Mode.VPN -> {
                Log.i(TAG, "Stopping VPN")
                val intent = Intent(context, AppVpnService::class.java).apply {
                    action = STOP_ACTION
                    putExtra("is_foreground", false)
                }
                context.sendIntentToService(intent, isForeground = false)
            }

            Mode.Proxy -> {
                Log.i(TAG, "Stopping proxy")
                val intent = Intent(context, ByeDpiProxyService::class.java).apply {
                    action = STOP_ACTION
                    putExtra("is_foreground", false)
                }
                if (!context.sendIntentToService(intent, isForeground = false)) {
                    context.stopServiceCompat(intent)
                }
            }
        }
    }
}

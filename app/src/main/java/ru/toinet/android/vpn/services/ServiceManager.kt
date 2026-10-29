package ru.toinet.android.vpn.services

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import ru.toinet.android.vpn.data.Mode
import ru.toinet.android.vpn.data.START_ACTION
import ru.toinet.android.vpn.data.STOP_ACTION
import ru.toinet.android.byedpi.services.ByeDpiProxyService

object ServiceManager {
    private val TAG: String = ServiceManager::class.java.simpleName

    fun start(context: Context, mode: Mode) {
        when (mode) {
            Mode.VPN -> {
                Log.i(TAG, "Starting VPN")
                val intent = Intent(context, AppVpnService::class.java).apply {
                    action = START_ACTION
                }
                try {
                    ContextCompat.startForegroundService(context, intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start VPN foreground service", e)
                }
            }

            Mode.Proxy -> {
                Log.i(TAG, "Starting proxy")
                val intent = Intent(context, ByeDpiProxyService::class.java).apply {
                    action = START_ACTION
                }
                try {
                    ContextCompat.startForegroundService(context, intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start ByeDPI foreground service", e)
                }
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
                }
                try {
                    context.startService(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to stop VPN service", e)
                }
            }

            Mode.Proxy -> {
                Log.i(TAG, "Stopping proxy")
                val intent = Intent(context, ByeDpiProxyService::class.java).apply {
                    action = STOP_ACTION
                }
                try {
                    context.startService(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to stop proxy service", e)
                }
            }
        }
    }
}

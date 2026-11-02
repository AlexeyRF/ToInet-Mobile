package ru.toinet.android.easyvpn

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import ru.toinet.android.util.Prefs
import ru.toinet.android.vpn.utility.createConnectionNotificationString
import ru.toinet.android.vpn.utility.registerNotificationChannel

class EasyVpnService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var server: EasyVpnServer? = null

    companion object {
        private const val TAG = "EasyVpnService"
        private const val NOTIFICATION_CHANNEL_ID = "EasyVpnProxy"
        private const val NOTIFICATION_ID = 1

        const val ACTION_START = "ru.toinet.android.easyvpn.START"
        const val ACTION_STOP = "ru.toinet.android.easyvpn.STOP"
        const val ACTION_SWITCH_SERVER = "ru.toinet.android.easyvpn.SWITCH_SERVER"

        val isRunning: Boolean
            get() = EasyVpnServer.isRunning && EasyVpnServer.activeInstance != null

        fun start(context: Context) {
            val intent = Intent(context, EasyVpnService::class.java).apply {
                action = ACTION_START
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to start EasyVpnService as foreground, attempting startService", e)
                try {
                    context.startService(intent)
                } catch (e2: Exception) {
                    Log.e(TAG, "Failed to start EasyVpnService", e2)
                }
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, EasyVpnService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop EasyVpnService", e)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        registerNotificationChannel(this, NOTIFICATION_CHANNEL_ID, "EasyVPN Proxy")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForegroundNotification()
                if (server == null) {
                    server = EasyVpnServer(this, scope).apply {
                        onServerChanged = {
                            if (Prefs.shouldServiceShowNotification("easyvpn")) {
                                val nm = getSystemService(NotificationManager::class.java)
                                nm?.notify(NOTIFICATION_ID, buildNotification())
                            }
                        }
                    }
                }
                server?.start()
                return START_STICKY
            }
            ACTION_SWITCH_SERVER -> {
                server?.switchToNextServer()
                return START_STICKY
            }
            "ACTIVE" -> {
                if (server != null) {
                    startForegroundNotification()
                }
                return START_STICKY
            }
            ACTION_STOP, ru.toinet.android.vpn.data.STOP_ACTION -> {
                server?.stop()
                server = null
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    stopForeground(true)
                }
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(): android.app.Notification {
        val title = "EasyVPN"
        val content = server?.getCurrentServerDescription()
            ?: "SOCKS5: ${Prefs.easyVpnProxyHost}:${Prefs.easyVpnProxyPort}"

        val switchIntent = PendingIntent.getService(
            this,
            102,
            Intent(this, EasyVpnService::class.java).setAction(ACTION_SWITCH_SERVER),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return createConnectionNotificationString(
            this,
            NOTIFICATION_CHANNEL_ID,
            title,
            content,
            EasyVpnService::class.java,
            switchIntent,
            serviceKey = "easyvpn"
        )
    }

    private fun startForegroundNotification() {
        if (Prefs.isGlobalVpnEnabled || !Prefs.isPrimaryNotificationService("easyvpn")) return
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed startForeground in EasyVpnService", e)
            try {
                startForeground(NOTIFICATION_ID, notification)
            } catch (_: Exception) {}
        }
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        scope.cancel()
        super.onDestroy()
    }
}

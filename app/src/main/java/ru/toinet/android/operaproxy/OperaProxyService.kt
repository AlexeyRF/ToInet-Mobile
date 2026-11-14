package ru.toinet.android.operaproxy

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import ru.toinet.android.R
import ru.toinet.android.ToInetActivity
import ru.toinet.android.util.Prefs
import ru.toinet.android.util.sendIntentToService

class OperaProxyService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var server: OperaProxyServer? = null

    companion object {
        private const val TAG = "OperaProxyService"
        private const val NOTIFICATION_CHANNEL_ID = "operaproxy_channel"
        private const val NOTIFICATION_ID = 1002

        const val ACTION_START = "ru.toinet.android.operaproxy.START"
        const val ACTION_STOP = "ru.toinet.android.operaproxy.STOP"

        val isRunning: Boolean
            get() = OperaProxyServer.isRunning

        fun start(context: Context) {
            val isForeground = !Prefs.isGlobalVpnEnabled && Prefs.isPrimaryNotificationService("operaproxy")
            val intent = Intent(context, OperaProxyService::class.java).apply {
                action = ACTION_START
                putExtra("is_foreground", isForeground)
            }
            context.sendIntentToService(intent, isForeground = isForeground)
        }

        fun stop(context: Context) {
            val intent = Intent(context, OperaProxyService::class.java).apply {
                action = ACTION_STOP
            }
            context.sendIntentToService(intent, isForeground = false)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ru.toinet.android.vpn.utility.registerNotificationChannel(this, NOTIFICATION_CHANNEL_ID, "Opera Proxy Service")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            if (Prefs.isProviderActive("operaproxy")) {
                val isForeground = !Prefs.isGlobalVpnEnabled && Prefs.isPrimaryNotificationService("operaproxy")
                if (isForeground) {
                    startForegroundNotification()
                }
                if (server == null) {
                    server = OperaProxyServer(this, scope)
                }
                server?.start()
                return START_STICKY
            } else {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        when (intent.action) {
            ACTION_START -> {
                val isForeground = intent.getBooleanExtra("is_foreground", false) ||
                        (!Prefs.isGlobalVpnEnabled && Prefs.isPrimaryNotificationService("operaproxy"))
                if (isForeground) {
                    startForegroundNotification()
                }
                if (server == null) {
                    server = OperaProxyServer(this, scope)
                }
                server?.start()
                return START_STICKY
            }
            "ACTIVE" -> {
                val isForeground = intent.getBooleanExtra("is_foreground", false)
                if (server != null && isForeground) {
                    startForegroundNotification()
                }
                return START_STICKY
            }
            ACTION_STOP -> {
                server?.stop()
                server = null
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundNotification() {
        val notificationIntent = Intent(this, ToInetActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val bindAddress = Prefs.operaProxyBindAddress.takeIf { it.isNotBlank() } ?: "127.0.0.1:1785"

        ru.toinet.android.vpn.utility.registerNotificationChannel(this, NOTIFICATION_CHANNEL_ID, "Opera Proxy Service")

        val (actualId, priority) = ru.toinet.android.vpn.utility.resolveNotificationChannelAndPriority("operaproxy", NOTIFICATION_CHANNEL_ID)
        val isDetailed = Prefs.shouldShowDetailedNotification("operaproxy")

        val builder = NotificationCompat.Builder(this, actualId)
            .setSmallIcon(R.drawable.ic_stat_tor)
            .setSilent(true)
            .setOngoing(true)
            .setGroup("toinet_services")
            .setContentIntent(pendingIntent)
            .setPriority(priority)

        if (!isDetailed) {
            builder.setContentTitle("ToInet")
                .setContentText(getString(R.string.opera_proxy_notification_title))
                .setSubText(null)
        } else {
            builder.setContentTitle(getString(R.string.opera_proxy_notification_title))
                .setContentText("SOCKS5: $bindAddress")
        }

        val notification = builder.build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
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

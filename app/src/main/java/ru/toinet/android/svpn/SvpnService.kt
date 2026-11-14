package ru.toinet.android.svpn

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
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import ru.toinet.android.R
import ru.toinet.android.ToInetActivity
import ru.toinet.android.util.Prefs
import ru.toinet.android.util.sendIntentToService

class SvpnService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var server: SvpnServer? = null

    companion object {
        private const val TAG = "SvpnService"
        private const val NOTIFICATION_CHANNEL_ID = "svpn_channel"
        private const val NOTIFICATION_ID = 1003

        const val ACTION_START = "ru.toinet.android.svpn.START"
        const val ACTION_STOP = "ru.toinet.android.svpn.STOP"

        val isRunning: Boolean
            get() = SvpnServer.isRunning

        fun start(context: Context) {
            val isForeground = !Prefs.isGlobalVpnEnabled && Prefs.isPrimaryNotificationService("svpn")
            val intent = Intent(context, SvpnService::class.java).apply {
                action = ACTION_START
                putExtra("is_foreground", isForeground)
            }
            context.sendIntentToService(intent, isForeground = isForeground)
        }

        fun stop(context: Context) {
            val intent = Intent(context, SvpnService::class.java).apply {
                action = ACTION_STOP
            }
            context.sendIntentToService(intent, isForeground = false)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ru.toinet.android.vpn.utility.registerNotificationChannel(this, NOTIFICATION_CHANNEL_ID, "SVPN Service")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            if (Prefs.isProviderActive("svpn") || Prefs.isProviderActive("ssh")) {
                val isForeground = !Prefs.isGlobalVpnEnabled && Prefs.isPrimaryNotificationService("svpn")
                if (isForeground) {
                    startForegroundNotification()
                }
                if (server == null) {
                    server = SvpnServer(this, scope)
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
                        (!Prefs.isGlobalVpnEnabled && Prefs.isPrimaryNotificationService("svpn"))
                if (isForeground) {
                    startForegroundNotification()
                }
                if (server == null) {
                    server = SvpnServer(this, scope)
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
            ACTION_STOP, ru.toinet.android.vpn.data.STOP_ACTION -> {
                server?.stop()
                server = null
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
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

        val bindAddress = if (Prefs.openProxyOnAllInterfaces) "0.0.0.0:${Prefs.svpnLocalPort}" else "127.0.0.1:${Prefs.svpnLocalPort}"

        ru.toinet.android.vpn.utility.registerNotificationChannel(this, NOTIFICATION_CHANNEL_ID, "SVPN Service")

        val (actualId, priority) = ru.toinet.android.vpn.utility.resolveNotificationChannelAndPriority("svpn", NOTIFICATION_CHANNEL_ID)
        val isDetailed = Prefs.shouldShowDetailedNotification("svpn")

        val builder = NotificationCompat.Builder(this, actualId)
            .setSmallIcon(R.drawable.ic_stat_tor)
            .setSilent(true)
            .setOngoing(true)
            .setGroup("toinet_services")
            .setContentIntent(pendingIntent)
            .setPriority(priority)

        if (!isDetailed) {
            builder.setContentTitle("ToInet")
                .setContentText("SVPN (SSH)")
                .setSubText(null)
        } else {
            builder.setContentTitle("SVPN (SSH Tunnel)")
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

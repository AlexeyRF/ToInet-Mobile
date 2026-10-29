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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import ru.toinet.android.R
import ru.toinet.android.ToInetActivity
import ru.toinet.android.util.Prefs

class SvpnService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var server: SvpnServer? = null

    companion object {
        private const val TAG = "SvpnService"
        private const val NOTIFICATION_CHANNEL_ID = "svpn_channel"
        private const val NOTIFICATION_ID = 1770

        const val ACTION_START = "ru.toinet.android.svpn.START"
        const val ACTION_STOP = "ru.toinet.android.svpn.STOP"

        val isRunning: Boolean
            get() = SvpnServer.isRunning

        fun start(context: Context) {
            val intent = Intent(context, SvpnService::class.java).apply {
                action = ACTION_START
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed startForegroundService, attempting startService", e)
                try {
                    context.startService(intent)
                } catch (e2: Exception) {
                    Log.e(TAG, "Failed to start SvpnService", e2)
                }
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, SvpnService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop SvpnService", e)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ru.toinet.android.vpn.utility.registerNotificationChannel(this, NOTIFICATION_CHANNEL_ID, "SVPN Service")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForegroundNotification()
                if (server == null) {
                    server = SvpnServer(this, scope)
                }
                server?.start()
                return START_STICKY
            }
            "ACTIVE" -> {
                if (server != null) {
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        scope.cancel()
        super.onDestroy()
    }
}

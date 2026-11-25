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
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.toinet.android.R
import ru.toinet.android.ToInetActivity
import ru.toinet.android.util.ArchHelper
import ru.toinet.android.util.Prefs
import ru.toinet.android.util.sendIntentToService
import ru.toinet.android.util.stopServiceCompat
import ru.toinet.android.vpn.rules.ProxifierRulesManager
import ru.toinet.android.vpn.services.AppVpnService

class OperaProxyService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mutex = Mutex()
    @Volatile
    private var server: OperaProxyServer? = null
    @Volatile
    private var isForegroundService = false

    companion object {
        private const val TAG = "OperaProxyService"
        private const val NOTIFICATION_CHANNEL_ID = "operaproxy_channel"
        private const val NOTIFICATION_ID = 1002

        const val ACTION_START = "ru.toinet.android.operaproxy.START"
        const val ACTION_STOP = "ru.toinet.android.operaproxy.STOP"

        @Volatile
        var activeService: OperaProxyService? = null
            private set

        val isRunning: Boolean
            get() = activeService?.server?.isServerRunning == true

        fun start(context: Context) {
            if (!ArchHelper.isOperaSupported) return
            val intent = Intent(context, OperaProxyService::class.java).apply {
                action = ACTION_START
                putExtra("is_foreground", true)
            }
            context.sendIntentToService(intent, isForeground = true)
        }

        fun stop(context: Context) {
            val intent = Intent(context, OperaProxyService::class.java).apply {
                action = ACTION_STOP
            }
            context.stopServiceCompat(intent)
        }

        fun stopAndWait(context: Context, timeoutMs: Long = 2000L) {
            activeService?.stopImmediate()
            if (activeService != null) {
                stop(context)
            }
            val port = Prefs.operaProxyBindAddress.split(":").lastOrNull()?.toIntOrNull() ?: 1785
            val start = System.currentTimeMillis()
            while (System.currentTimeMillis() - start < timeoutMs) {
                if (!isRunning && activeService == null) {
                    var inUse = false
                    try {
                        java.net.Socket().use { s ->
                            s.connect(java.net.InetSocketAddress("127.0.0.1", port), 100)
                            inUse = true
                        }
                    } catch (_: Exception) {}
                    if (!inUse) break
                }
                try { Thread.sleep(50) } catch (_: InterruptedException) { break }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        activeService = this
        ru.toinet.android.vpn.utility.registerNotificationChannel(this, NOTIFICATION_CHANNEL_ID, "Opera Proxy Service")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            val managedByVpn = AppVpnService.activeService != null &&
                ProxifierRulesManager.getAllNeededVpnProviders().contains("operaproxy")
            if (Prefs.isProviderActive("operaproxy") && ArchHelper.isOperaSupported && !managedByVpn) {
                startForegroundNotification()
                scope.launch { startServerInternal() }
                return START_STICKY
            } else {
                stopSelf()
                return START_NOT_STICKY
            }
        }

        val fgRequested = intent.getBooleanExtra("is_foreground", false)
        when (intent.action) {
            ACTION_START -> {
                if (fgRequested || !Prefs.isGlobalVpnEnabled) {
                    startForegroundNotification()
                }
                scope.launch { startServerInternal() }
                return START_STICKY
            }
            "ACTIVE" -> {
                if (server != null && (fgRequested || isForegroundService)) {
                    startForegroundNotification()
                }
                return START_STICKY
            }
            ACTION_STOP, ru.toinet.android.vpn.data.STOP_ACTION -> {
                if (fgRequested) startForegroundNotification()
                stopImmediate()
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun startServerInternal() {
        mutex.withLock {
            val managedByVpn = AppVpnService.activeService != null &&
                ProxifierRulesManager.getAllNeededVpnProviders().contains("operaproxy")
            if (managedByVpn || !ArchHelper.isOperaSupported) {
                Log.w(TAG, "OperaProxy is managed in-process by AppVpnService or unsupported; skipping standalone start")
                stopImmediate()
                return
            }
            if (server == null) {
                server = OperaProxyServer(this, scope).apply {
                    onStateChanged = { running, error ->
                        if (!running && !Prefs.isGlobalVpnEnabled && AppVpnService.activeService == null) {
                            Log.w(TAG, "OperaProxy server stopped/failed: $error")
                            ru.toinet.android.util.broadcastStandaloneStatus(this@OperaProxyService)
                        }
                    }
                }
            }
            val started = server?.start() == true
            if (started) {
                ru.toinet.android.util.broadcastStandaloneStatus(this)
            } else {
                Log.e(TAG, "OperaProxy server failed to start")
                ru.toinet.android.util.broadcastStandaloneStatus(this)
                stopImmediate()
            }
        }
    }

    fun stopImmediate() {
        server?.stop()
        server = null
        if (isForegroundService) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            isForegroundService = false
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm?.cancel(NOTIFICATION_ID)
        if (activeService == this) {
            activeService = null
        }
        stopSelf()
        ru.toinet.android.util.broadcastStandaloneStatus(this)
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
                .setContentText(null)
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
            isForegroundService = true
        } catch (e: Exception) {
            try {
                startForeground(NOTIFICATION_ID, notification)
                isForegroundService = true
            } catch (_: Exception) {}
        }
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        if (activeService == this) {
            activeService = null
        }
        scope.cancel()
        ru.toinet.android.util.broadcastStandaloneStatus(this)
        super.onDestroy()
    }
}

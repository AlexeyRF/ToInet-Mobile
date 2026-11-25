package ru.toinet.android.easyvpn

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.toinet.android.util.Prefs
import ru.toinet.android.util.sendIntentToService
import ru.toinet.android.util.stopServiceCompat
import ru.toinet.android.vpn.rules.ProxifierRulesManager
import ru.toinet.android.vpn.services.AppVpnService
import ru.toinet.android.vpn.utility.createConnectionNotificationString
import ru.toinet.android.vpn.utility.registerNotificationChannel
import ru.toinet.android.vpn.utility.startSharedForeground
import ru.toinet.android.vpn.utility.stopSharedForeground
import ru.toinet.android.vpn.utility.updateSharedForegroundNotification

class EasyVpnService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mutex = Mutex()
    @Volatile
    private var server: EasyVpnServer? = null
    @Volatile
    private var isForegroundService = false

    companion object {
        private const val TAG = "EasyVpnService"
        private const val NOTIFICATION_CHANNEL_ID = "EasyVpnProxy"

        const val ACTION_START = "ru.toinet.android.easyvpn.START"
        const val ACTION_STOP = "ru.toinet.android.easyvpn.STOP"
        const val ACTION_SWITCH_SERVER = "ru.toinet.android.easyvpn.SWITCH_SERVER"

        @Volatile
        var activeService: EasyVpnService? = null
            private set

        val isRunning: Boolean
            get() = activeService?.server?.isServerRunning == true

        fun start(context: Context) {
            val intent = Intent(context, EasyVpnService::class.java).apply {
                action = ACTION_START
                putExtra("is_foreground", true)
            }
            context.sendIntentToService(intent, isForeground = true)
        }

        fun stop(context: Context) {
            val intent = Intent(context, EasyVpnService::class.java).apply {
                action = ACTION_STOP
            }
            context.stopServiceCompat(intent)
        }

        fun stopAndWait(context: Context, timeoutMs: Long = 2000L) {
            activeService?.stopImmediate()
            if (activeService != null) {
                stop(context)
            }
            val port = if (Prefs.easyVpnProxyPort in 1..65535) Prefs.easyVpnProxyPort else 1790
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
        registerNotificationChannel(this, NOTIFICATION_CHANNEL_ID, "EasyVPN Proxy")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            val managedByVpn = AppVpnService.activeService != null &&
                ProxifierRulesManager.getAllNeededVpnProviders().contains("easyvpn")
            if (Prefs.isProviderActive("easyvpn") && !managedByVpn) {
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
            ACTION_SWITCH_SERVER -> {
                if (fgRequested) startForegroundNotification()
                scope.launch { server?.switchToNextServer() }
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
                ProxifierRulesManager.getAllNeededVpnProviders().contains("easyvpn")
            if (managedByVpn) {
                Log.w(TAG, "EasyVPN is managed in-process by AppVpnService; skipping standalone start")
                stopImmediate()
                return
            }
            if (server == null) {
                server = EasyVpnServer(this, scope).apply {
                    onServerChanged = {
                        if (isForegroundService || Prefs.shouldServiceShowNotification("easyvpn")) {
                            updateSharedForegroundNotification(this@EasyVpnService, "easyvpn") { buildNotification() }
                        }
                    }
                    onStateChanged = { running, error ->
                        if (!running && !Prefs.isGlobalVpnEnabled && AppVpnService.activeService == null) {
                            Log.w(TAG, "EasyVPN server stopped/failed: $error")
                            ru.toinet.android.util.broadcastStandaloneStatus(this@EasyVpnService)
                        }
                    }
                }
            }
            val started = server?.start() == true
            if (started) {
                ru.toinet.android.util.broadcastStandaloneStatus(this)
            } else {
                Log.e(TAG, "EasyVPN server failed to start")
                ru.toinet.android.util.broadcastStandaloneStatus(this)
                stopImmediate()
            }
        }
    }

    fun stopImmediate() {
        server?.stop()
        server = null
        val wasFg = isForegroundService
        isForegroundService = false
        stopSharedForeground(this, "easyvpn", wasForeground = wasFg)
        if (activeService == this) {
            activeService = null
        }
        stopSelf()
        ru.toinet.android.util.broadcastStandaloneStatus(this)
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
        startSharedForeground(this, "easyvpn") { buildNotification() }
        isForegroundService = true
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        val wasFg = isForegroundService
        isForegroundService = false
        stopSharedForeground(this, "easyvpn", wasForeground = wasFg)
        if (activeService == this) {
            activeService = null
        }
        scope.cancel()
        ru.toinet.android.util.broadcastStandaloneStatus(this)
        super.onDestroy()
    }
}

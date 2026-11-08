package ru.toinet.android.byedpi.services

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
import android.os.Build
import android.util.Log
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import ru.toinet.android.R
import ru.toinet.android.byedpi.core.*
import ru.toinet.android.vpn.data.*
import ru.toinet.android.byedpi.utility.*
import ru.toinet.android.vpn.utility.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive

class ByeDpiProxyService : LifecycleService() {
    private var proxy = ByeDpiProxy()
    private var proxyJob: Job? = null
    private val mutex = Mutex()

    companion object {
        private val TAG: String = ByeDpiProxyService::class.java.simpleName
        private const val FOREGROUND_SERVICE_ID: Int = 1
        private const val NOTIFICATION_CHANNEL_ID: String = "ByeDPI Proxy"

        private var status: ServiceStatus = ServiceStatus.Disconnected
    }

    override fun onCreate() {
        super.onCreate()
        registerNotificationChannel(
            this,
            NOTIFICATION_CHANNEL_ID,
            R.string.proxy_channel_name,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val action = intent?.action

        if (action == START_ACTION || (action == "ACTIVE" && status == ServiceStatus.Connected)) {
            startForeground()
        }

        return when (action) {
            START_ACTION -> {
                lifecycleScope.launch { start() }
                START_STICKY
            }

            STOP_ACTION -> {
                lifecycleScope.launch { stop() }
                START_NOT_STICKY
            }

            "ACTIVE" -> {
                if (status == ServiceStatus.Connected) {
                    startForeground()
                }
                START_STICKY
            }

            else -> {
                if (action != null) Log.w(TAG, "Unknown action: $action")
                START_NOT_STICKY
            }
        }
    }

    private suspend fun start() {
        Log.i(TAG, "Starting")

        if (status == ServiceStatus.Connected) {
            Log.w(TAG, "Proxy already connected")
            startForeground()
            return
        }

        try {
            if (!ru.toinet.android.util.Prefs.isGlobalVpnEnabled && !ru.toinet.android.util.Prefs.torEnabled) {
                val statusIntent = Intent(ru.toinet.android.service.ToInetConstants.LOCAL_ACTION_STATUS)
                statusIntent.putExtra(org.torproject.jni.TorService.EXTRA_STATUS, org.torproject.jni.TorService.STATUS_STARTING)
                statusIntent.setPackage(packageName)
                sendBroadcast(statusIntent)
            }
            ru.toinet.android.util.NotificationLogger.log(this, "byedpi", "Запуск ByeDPI Proxy...")
            startForeground()
            mutex.withLock {
                startProxy()
            }
            updateStatus(ServiceStatus.Connected)
            ru.toinet.android.util.NotificationLogger.log(this, "byedpi", "ByeDPI Proxy запущен")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start proxy", e)
            updateStatus(ServiceStatus.Failed)
            ru.toinet.android.util.NotificationLogger.log(this, "byedpi", "Ошибка запуска ByeDPI: ${e.message}")
            stop()
        }
    }

    private fun startForeground() {
        if (ru.toinet.android.util.Prefs.isGlobalVpnEnabled || !ru.toinet.android.util.Prefs.isPrimaryNotificationService("byedpi")) {
            return
        }
        val notification: Notification = createNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    FOREGROUND_SERVICE_ID,
                    notification,
                    FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(FOREGROUND_SERVICE_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed startForeground in ByeDpiProxyService", e)
            try {
                startForeground(FOREGROUND_SERVICE_ID, notification)
            } catch (_: Exception) {}
        }
    }

    private suspend fun stop() {
        Log.i(TAG, "Stopping")

        mutex.withLock {
            stopProxy()
        }
        updateStatus(ServiceStatus.Disconnected)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm?.cancel(FOREGROUND_SERVICE_ID)
        stopSelf()
    }

    private suspend fun startProxy() {
        Log.i(TAG, "Starting proxy")

        if (proxyJob != null) {
            Log.w(TAG, "Proxy fields not null")
            throw IllegalStateException("Proxy fields not null")
        }

        var cmdStrOriginal = ru.toinet.android.util.Prefs.byedpiArgs
        val subUrl = ru.toinet.android.util.Prefs.byedpiSubscriptionUrl
        if (subUrl.isNotBlank()) {
            try {
                Log.i(TAG, "Fetching ByeDPI config from $subUrl")
                val fetchedArgs = withContext(Dispatchers.IO) {
                    java.net.URL(subUrl).readText().trim()
                }
                if (fetchedArgs.isNotBlank()) {
                    cmdStrOriginal = fetchedArgs
                    // Optionally save it back or just use it in memory
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch ByeDPI config from subscription", e)
            }
        }

        if (ru.toinet.android.util.Prefs.byedpiUpstreamEnabled) {
            val upstreamProvider = ru.toinet.android.util.Prefs.byedpiUpstreamProvider
            ru.toinet.android.util.UpstreamProxyCoordinator.ensureUpstreamStarted(this, upstreamProvider)
            val upstreamArg = "${ru.toinet.android.util.Prefs.byedpiUpstreamHost}:${ru.toinet.android.util.Prefs.byedpiUpstreamPort}"
            if (!cmdStrOriginal.contains("-C")) {
                cmdStrOriginal = "$cmdStrOriginal -C $upstreamArg".trim()
            }
        }

        val cmdStr = if (cmdStrOriginal.trim().equals("TOR", ignoreCase = true)) {
            "{\"\":\"TOR\"}"
        } else {
            cmdStrOriginal
        }

        val sharedPrefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
        val isCmd = sharedPrefs.getBoolean("byedpi_enable_cmd_settings", true)
        val isRouter = cmdStr.trim().startsWith("{") && cmdStr.trim().endsWith("}") && isCmd

        val preferences = if (isCmd) {
            ByeDpiProxyCmdPreferences(cmdStr)
        } else {
            ByeDpiProxyUIPreferences(sharedPrefs)
        }

        if (isRouter) {
            Log.i(TAG, "Starting router")
            val ip = if (ru.toinet.android.util.Prefs.openProxyOnAllInterfaces) "0.0.0.0" else "127.0.0.1"
            val port = preferences.port
            ru.toinet.android.byedpi.core.ByeDpiRouter.start(this, cmdStr, port, ip)

            proxyJob = lifecycleScope.launch(Dispatchers.IO) {
                while (isActive) {
                    kotlinx.coroutines.delay(1000)
                }
            }
            updateStatus(ServiceStatus.Connected)
        } else {
            proxy = ByeDpiProxy()

            proxyJob = lifecycleScope.launch(Dispatchers.IO) {
                withContext(Dispatchers.Main) {
                    updateStatus(ServiceStatus.Connected)
                }
                val code = proxy.startProxy(preferences)

                withContext(Dispatchers.Main) {
                    if (code != 0) {
                        Log.e(TAG, "Proxy stopped with code $code")
                        updateStatus(ServiceStatus.Failed)
                    } else {
                        updateStatus(ServiceStatus.Disconnected)
                    }
                }
            }
        }

        Log.i(TAG, "Proxy started")
    }

    private suspend fun stopProxy() {
        Log.i(TAG, "Stopping proxy")

        if (status == ServiceStatus.Disconnected) {
            Log.w(TAG, "Proxy already disconnected")
            return
        }

        ru.toinet.android.byedpi.core.ByeDpiRouter.stop(this)
        proxy.stopProxy()
        proxyJob?.cancel()
        proxyJob?.join()
        proxyJob = null

        Log.i(TAG, "Proxy stopped")
    }

    private fun getByeDpiPreferences(): ByeDpiProxyPreferences =
        ByeDpiProxyPreferences.fromSharedPreferences(androidx.preference.PreferenceManager.getDefaultSharedPreferences(this))

    private fun updateStatus(newStatus: ServiceStatus) {
        Log.d(TAG, "Proxy status changed from $status to $newStatus")

        status = newStatus

        ru.toinet.android.vpn.services.setStatus(
            when (newStatus) {
                ServiceStatus.Connected -> AppStatus.Running
                ServiceStatus.Disconnected,
                ServiceStatus.Failed -> {
                    proxyJob = null
                    AppStatus.Halted
                }
            },
            Mode.Proxy
        )

        if (!ru.toinet.android.util.Prefs.isGlobalVpnEnabled && !ru.toinet.android.util.Prefs.torEnabled) {
            val toinetStatus = when (newStatus) {
                ServiceStatus.Connected -> org.torproject.jni.TorService.STATUS_ON
                ServiceStatus.Disconnected, ServiceStatus.Failed -> org.torproject.jni.TorService.STATUS_OFF
            }
            val statusIntent = Intent(ru.toinet.android.service.ToInetConstants.LOCAL_ACTION_STATUS)
            statusIntent.putExtra(org.torproject.jni.TorService.EXTRA_STATUS, toinetStatus)
            statusIntent.setPackage(packageName)
            sendBroadcast(statusIntent)
        }

        val intent = Intent(
            when (newStatus) {
                ServiceStatus.Connected -> STARTED_BROADCAST
                ServiceStatus.Disconnected -> STOPPED_BROADCAST
                ServiceStatus.Failed -> FAILED_BROADCAST
            }
        )
        intent.putExtra(SENDER, Sender.Proxy.ordinal)
        sendBroadcast(intent)
    }

    private fun createNotification(): Notification =
        createConnectionNotification(
            this,
            NOTIFICATION_CHANNEL_ID,
            R.string.byedpi_notification_title,
            R.string.proxy_notification_content,
            ByeDpiProxyService::class.java,
        )
}



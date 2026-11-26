package ru.toinet.android.byedpi.services

import android.app.Notification
import android.content.Intent
import android.util.Log
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import ru.toinet.android.R
import ru.toinet.android.byedpi.core.*
import ru.toinet.android.byedpi.utility.*
import ru.toinet.android.util.NotificationLogger
import ru.toinet.android.util.Prefs
import ru.toinet.android.util.UpstreamProxyCoordinator
import ru.toinet.android.util.broadcastStandaloneStatus
import ru.toinet.android.vpn.data.*
import ru.toinet.android.vpn.utility.*
import java.net.InetSocketAddress
import java.net.Socket

class ByeDpiProxyService : LifecycleService() {
    private var proxy = ByeDpiProxy()
    private var proxyJob: Job? = null
    private val mutex = Mutex()
    @Volatile
    private var isForegroundService = false

    companion object {
        private val TAG: String = ByeDpiProxyService::class.java.simpleName
        private const val NOTIFICATION_CHANNEL_ID: String = "ByeDPI Proxy"

        const val ACTION_RESTART = "ru.toinet.android.byedpi.RESTART"
        @Volatile
        var testCmdArgsOverride: String? = null

        var currentRunningArgs: String? = null
            private set

        @Volatile
        var status: ServiceStatus = ServiceStatus.Disconnected
            private set

        @Volatile
        var isStarting: Boolean = false
            private set
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
        if (intent == null) {
            if (Prefs.isProviderActive("byedpi")) {
                Prefs.isVpnOrProxySessionActive = true
                isForegroundService = true
                startForeground()
                lifecycleScope.launch { start() }
                return START_STICKY
            } else {
                stopSelf()
                return START_NOT_STICKY
            }
        }

        val action = intent.action
        val fgRequested = intent.getBooleanExtra("is_foreground", false)
        if (fgRequested) {
            isForegroundService = true
        } else if (action == START_ACTION && !Prefs.isGlobalVpnEnabled) {
            isForegroundService = true
        }

        if ((action == START_ACTION || action == ACTION_RESTART || action == "ACTIVE" || action == STOP_ACTION) &&
            (fgRequested || ((action == START_ACTION || action == ACTION_RESTART) && isForegroundService))
        ) {
            startForeground()
        }

        return when (action) {
            START_ACTION -> {
                if (status == ServiceStatus.Failed) {
                    status = ServiceStatus.Disconnected
                }
                isStarting = true
                lifecycleScope.launch { start() }
                START_STICKY
            }

            STOP_ACTION -> {
                isStarting = false
                lifecycleScope.launch { stop() }
                START_NOT_STICKY
            }

            ACTION_RESTART -> {
                if (status == ServiceStatus.Failed) {
                    status = ServiceStatus.Disconnected
                }
                isStarting = true
                lifecycleScope.launch { restart() }
                START_STICKY
            }

            "ACTIVE" -> {
                if (status == ServiceStatus.Connected && (fgRequested || isForegroundService)) {
                    startForeground()
                }
                START_STICKY
            }

            else -> {
                Log.w(TAG, "Unknown action: $action")
                START_NOT_STICKY
            }
        }
    }

    private suspend fun restart() {
        mutex.withLock {
            try {
                isStarting = true
                withContext(Dispatchers.IO) {
                    stopProxyLocked()
                }
                updateStatus(ServiceStatus.Disconnected)
                delay(200)
                performStartLocked()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restart proxy", e)
                updateStatus(ServiceStatus.Failed)
                NotificationLogger.log(this, "byedpi", "Ошибка перезапуска ByeDPI: ${e.message}")
                stopAfterFailure()
            } finally {
                isStarting = false
            }
        }
    }

    private suspend fun start() {
        Log.i(TAG, "Starting")
        mutex.withLock {
            if (status == ServiceStatus.Connected) {
                isStarting = false
                Log.w(TAG, "Proxy already connected")
                if (isForegroundService) {
                    startForeground()
                }
                return
            }
            try {
                isStarting = true
                performStartLocked()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start proxy", e)
                updateStatus(ServiceStatus.Failed)
                NotificationLogger.log(this, "byedpi", "Ошибка запуска ByeDPI: ${e.message}")
                stopAfterFailure()
            } finally {
                isStarting = false
            }
        }
    }

    private suspend fun performStartLocked() {
        val isPrimary = !Prefs.isGlobalVpnEnabled && Prefs.isPrimaryNotificationService("byedpi")
        if (isPrimary && testCmdArgsOverride == null) {
            val statusIntent = Intent(ru.toinet.android.service.ToInetConstants.LOCAL_ACTION_STATUS).apply {
                putExtra(org.torproject.jni.TorService.EXTRA_STATUS, org.torproject.jni.TorService.STATUS_STARTING)
                setPackage(packageName)
            }
            sendBroadcast(statusIntent)
        }
        NotificationLogger.log(this, "byedpi", "Запуск ByeDPI Proxy...")
        if (isForegroundService) {
            startForeground()
        }
        withContext(Dispatchers.IO) {
            startProxyLocked()
        }
        updateStatus(ServiceStatus.Connected)
        NotificationLogger.log(this, "byedpi", "ByeDPI Proxy запущен")
    }

    private fun startForeground() {
        startSharedForeground(this, "byedpi") { createNotification() }
        isForegroundService = true
    }

    private suspend fun stop() {
        Log.i(TAG, "Stopping")
        mutex.withLock {
            withContext(Dispatchers.IO) {
                stopProxyLocked()
            }
            updateStatus(ServiceStatus.Disconnected)
        }
        val wasFg = isForegroundService
        isForegroundService = false
        stopSharedForeground(this, "byedpi", wasForeground = wasFg)
        stopSelf()
    }

    private suspend fun stopAfterFailure() {
        withContext(Dispatchers.IO) {
            stopProxyLocked()
        }
        val wasFg = isForegroundService
        isForegroundService = false
        stopSharedForeground(this, "byedpi", wasForeground = wasFg)
        stopSelf()
    }

    private suspend fun startProxyLocked() {
        Log.i(TAG, "Starting proxy")

        if (proxyJob != null) {
            Log.w(TAG, "Previous proxy job was not null, cleaning up first")
            stopProxyLocked()
        }

        val testCmd = testCmdArgsOverride
        var cmdStrOriginal = if (!testCmd.isNullOrBlank()) {
            testCmd
        } else {
            Prefs.getCurrentByedpiArgs(this).ifBlank { "--preset general" }
        }
        currentRunningArgs = cmdStrOriginal

        if (testCmd.isNullOrBlank()) {
            val subUrl = Prefs.byedpiSubscriptionUrl
            if (subUrl.isNotBlank()) {
                try {
                    Log.i(TAG, "Fetching ByeDPI config from $subUrl")
                    val fetchedArgs = java.net.URL(subUrl).readText().trim()
                    if (fetchedArgs.isNotBlank()) {
                        cmdStrOriginal = fetchedArgs
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to fetch ByeDPI config from subscription", e)
                }
            }

            if (Prefs.byedpiUpstreamEnabled) {
                val upstreamProvider = Prefs.byedpiUpstreamProvider
                UpstreamProxyCoordinator.ensureUpstreamStarted(this, upstreamProvider)
                val upstreamArg = "${Prefs.byedpiUpstreamHost}:${Prefs.byedpiUpstreamPort}"
                if (!cmdStrOriginal.contains("-C")) {
                    cmdStrOriginal = "$cmdStrOriginal -C $upstreamArg".trim()
                }
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
            val ip = if (Prefs.openProxyOnAllInterfaces) "0.0.0.0" else "127.0.0.1"
            val port = preferences.port
            val routerStarted = ByeDpiRouter.start(this, cmdStr, port, ip)
            if (!routerStarted) {
                throw IllegalStateException("ByeDpiRouter failed to bind port $port")
            }

            proxyJob = lifecycleScope.launch(Dispatchers.IO) {
                while (isActive) {
                    delay(1000)
                }
            }
        } else {
            proxy = ByeDpiProxy()
            val exitCodeDeferred = CompletableDeferred<Int>()
            val job = lifecycleScope.launch(Dispatchers.IO) {
                val code = try {
                    proxy.startProxy(preferences)
                } catch (e: Throwable) {
                    Log.e(TAG, "Proxy threw exception", e)
                    -1
                }
                exitCodeDeferred.complete(code)

                withContext(Dispatchers.Main) {
                    if (proxyJob === coroutineContext[Job]) {
                        if (code != 0) {
                            Log.e(TAG, "Proxy stopped with code $code")
                            updateStatus(ServiceStatus.Failed)
                        } else if (status != ServiceStatus.Disconnected) {
                            updateStatus(ServiceStatus.Disconnected)
                        }
                    }
                }
            }
            proxyJob = job

            val probeHost = resolveProbeHost(preferences)
            val probePort = preferences.port
            val waitStart = System.currentTimeMillis()
            var portReady = false
            while (System.currentTimeMillis() - waitStart < 2000L) {
                if (exitCodeDeferred.isCompleted) {
                    val code = exitCodeDeferred.await()
                    throw IllegalStateException("ByeDPI process exited early with code $code")
                }
                try {
                    Socket().use { s ->
                        s.connect(InetSocketAddress(probeHost, probePort), 100)
                        portReady = true
                    }
                } catch (_: Exception) {}
                if (portReady) break
                delay(50)
            }
            if (exitCodeDeferred.isCompleted) {
                val code = exitCodeDeferred.await()
                throw IllegalStateException("ByeDPI process exited with code $code")
            }
            if (!portReady) {
                Log.w(TAG, "ByeDPI native thread running after 2000ms, port $probeHost:$probePort probe incomplete")
            }
        }

        Log.i(TAG, "Proxy started")
    }

    private fun resolveProbeHost(preferences: ByeDpiProxyPreferences): String {
        val rawIp = when (preferences) {
            is ByeDpiProxyUIPreferences -> preferences.ip
            is ByeDpiProxyCmdPreferences -> {
                var found: String? = null
                var nextIsIp = false
                for (arg in preferences.args) {
                    if (nextIsIp) {
                        found = arg
                        break
                    }
                    if (arg == "-i" || arg == "--ip") {
                        nextIsIp = true
                    } else if (arg.startsWith("-i") && arg.length > 2) {
                        found = arg.substring(2)
                        break
                    } else if (arg.startsWith("--ip=")) {
                        found = arg.substring(5)
                        break
                    }
                }
                found ?: "127.0.0.1"
            }
        }
        return if (rawIp == "0.0.0.0" || rawIp == "::" || rawIp.isBlank()) "127.0.0.1" else rawIp
    }

    private suspend fun stopProxyLocked() {
        Log.i(TAG, "Stopping proxy")

        val jobToStop = proxyJob
        proxyJob = null

        ByeDpiRouter.stop(this)
        try {
            proxy.stopProxy()
        } catch (_: Exception) {}
        jobToStop?.cancel()
        withTimeoutOrNull(2000) { jobToStop?.join() }
        currentRunningArgs = null

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

        val isPrimary = !Prefs.isGlobalVpnEnabled && Prefs.isPrimaryNotificationService("byedpi")
        if (testCmdArgsOverride == null) {
            if (isPrimary && newStatus == ServiceStatus.Connected) {
                val statusIntent = Intent(ru.toinet.android.service.ToInetConstants.LOCAL_ACTION_STATUS).apply {
                    putExtra(org.torproject.jni.TorService.EXTRA_STATUS, org.torproject.jni.TorService.STATUS_ON)
                    setPackage(packageName)
                }
                sendBroadcast(statusIntent)
            } else if (newStatus != ServiceStatus.Connected && !Prefs.isGlobalVpnEnabled) {
                broadcastStandaloneStatus(this)
            }
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

    override fun onDestroy() {
        try {
            ByeDpiRouter.stop(this)
            proxy.stopProxy()
        } catch (_: Exception) {}
        proxyJob?.cancel()
        proxyJob = null
        currentRunningArgs = null
        if (status != ServiceStatus.Disconnected) {
            updateStatus(ServiceStatus.Disconnected)
        }
        val wasFg = isForegroundService
        isForegroundService = false
        stopSharedForeground(this, "byedpi", wasForeground = wasFg)
        super.onDestroy()
    }

    private fun createNotification(): Notification =
        createConnectionNotification(
            this,
            NOTIFICATION_CHANNEL_ID,
            R.string.byedpi_notification_title,
            R.string.proxy_notification_content,
            ByeDpiProxyService::class.java,
            serviceKey = "byedpi"
        )
}

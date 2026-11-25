package ru.toinet.android.tgws

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import ru.toinet.android.R
import ru.toinet.android.ToInetActivity
import ru.toinet.android.util.NotificationLogger
import ru.toinet.android.util.Prefs
import ru.toinet.android.util.broadcastStandaloneStatus
import ru.toinet.android.util.sendIntentToService
import ru.toinet.android.util.stopServiceCompat
import ru.toinet.android.vpn.utility.NOTIFICATION_GROUP_KEY
import ru.toinet.android.vpn.utility.ensureSharedNotificationChannels
import ru.toinet.android.vpn.utility.resolveNotificationChannelAndPriority
import ru.toinet.android.vpn.utility.startSharedForeground
import ru.toinet.android.vpn.utility.stopSharedForeground
import java.net.InetSocketAddress
import java.net.Socket

class TgwsService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mutex = Mutex()
    @Volatile
    private var tgwsCore: TgwsCore? = null
    @Volatile
    private var isForegroundService = false

    companion object {
        private const val TAG = "TgwsService"
        const val ACTION_START = "ru.toinet.android.tgws.START"
        const val ACTION_STOP = "ru.toinet.android.tgws.STOP"
        private const val NOTIFICATION_CHANNEL_ID = "tgws_channel"

        private val _logs = MutableSharedFlow<String>(extraBufferCapacity = 100)
        val logs = _logs.asSharedFlow()
        val logHistory: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf<String>())

        @Volatile
        var activeService: TgwsService? = null
            private set

        val isRunning: Boolean
            get() = activeService?.tgwsCore?.isServerRunning == true

        fun start(
            context: Context,
            host: String = Prefs.tgwsHost,
            port: Int = Prefs.tgwsPort,
            dcMappings: Map<Int, String> = Prefs.tgwsDcMappings,
            secret: String = Prefs.tgwsSecret,
            fakeTls: String = Prefs.tgwsFakeTls,
            useByeDpi: Boolean = false,
            cfWorkerDomains: List<String> = Prefs.tgwsCfWorkerDomains,
            cfProxyDomains: List<String> = Prefs.tgwsCfProxyDomains,
            fallbackCfProxy: Boolean = Prefs.tgwsFallbackCfProxy,
            cfProxyH2Media: Boolean = Prefs.tgwsCfProxyH2Media,
            frontingSni: String = Prefs.tgwsFrontingSni,
            disableSecure: Boolean = Prefs.tgwsDisableSecure,
            forceTestDc: Boolean = Prefs.tgwsForceTestDc,
            poolSize: Int = Prefs.tgwsPoolSize
        ) {
            val intent = Intent(context, TgwsService::class.java).apply {
                action = ACTION_START
                putExtra("is_foreground", true)
                putExtra("host", host)
                putExtra("port", port)
                val mappingList = dcMappings.map { "${it.key}:${it.value}" }.toTypedArray()
                putExtra("dcMappings", mappingList)
                putExtra("secret", secret)
                putExtra("fakeTls", fakeTls)
                putExtra("useByeDpi", useByeDpi)
                putExtra("cfWorkerDomains", cfWorkerDomains.toTypedArray())
                putExtra("cfProxyDomains", cfProxyDomains.toTypedArray())
                putExtra("fallbackCfProxy", fallbackCfProxy)
                putExtra("cfProxyH2Media", cfProxyH2Media)
                putExtra("frontingSni", frontingSni)
                putExtra("disableSecure", disableSecure)
                putExtra("forceTestDc", forceTestDc)
                putExtra("poolSize", poolSize)
            }
            context.sendIntentToService(intent, isForeground = true)
        }

        fun stop(context: Context) {
            val intent = Intent(context, TgwsService::class.java).apply {
                action = ACTION_STOP
            }
            context.stopServiceCompat(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        activeService = this
        ru.toinet.android.vpn.utility.registerNotificationChannel(
            this,
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.tgws_proxy_channel_name)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            if (Prefs.tgwsEnabled) {
                startForegroundNotification()
                scope.launch {
                    startCoreInternal(
                        host = Prefs.tgwsHost,
                        port = Prefs.tgwsPort,
                        useByeDpi = false,
                        dcMappings = Prefs.tgwsDcMappings,
                        secret = Prefs.tgwsSecret,
                        fakeTls = Prefs.tgwsFakeTls,
                        cfWorkerDomains = Prefs.tgwsCfWorkerDomains,
                        cfProxyDomains = Prefs.tgwsCfProxyDomains,
                        fallbackCfProxy = Prefs.tgwsFallbackCfProxy,
                        cfProxyH2Media = Prefs.tgwsCfProxyH2Media,
                        frontingSni = Prefs.tgwsFrontingSni,
                        disableSecure = Prefs.tgwsDisableSecure,
                        forceTestDc = Prefs.tgwsForceTestDc,
                        poolSize = Prefs.tgwsPoolSize
                    )
                }
                return START_STICKY
            } else {
                stopSelf()
                return START_NOT_STICKY
            }
        }

        val fgRequested = intent.getBooleanExtra("is_foreground", false)
        when (intent.action) {
            ACTION_STOP, ru.toinet.android.vpn.data.STOP_ACTION -> {
                if (fgRequested) startForegroundNotification()
                stopImmediate()
                return START_NOT_STICKY
            }
            "ACTIVE" -> {
                if (tgwsCore != null && (fgRequested || isForegroundService)) {
                    startForegroundNotification()
                }
                return START_STICKY
            }
            ACTION_START -> {
                if (fgRequested || !Prefs.isGlobalVpnEnabled) {
                    startForegroundNotification()
                }
                val host = intent.getStringExtra("host") ?: Prefs.tgwsHost
                val port = intent.getIntExtra("port", Prefs.tgwsPort)
                val useByeDpi = intent.getBooleanExtra("useByeDpi", false)
                val mappingArray = intent.getStringArrayExtra("dcMappings")
                val dcMappings = if (mappingArray != null && mappingArray.isNotEmpty()) {
                    mappingArray.mapNotNull {
                        val parts = it.split(":", limit = 2)
                        if (parts.size == 2) {
                            val dc = parts[0].trim().toIntOrNull()
                            if (dc != null) dc to parts[1].trim() else null
                        } else null
                    }.toMap()
                } else {
                    Prefs.tgwsDcMappings
                }
                val secret = intent.getStringExtra("secret") ?: Prefs.tgwsSecret
                val fakeTls = intent.getStringExtra("fakeTls") ?: Prefs.tgwsFakeTls
                val cfWorkerDomains = intent.getStringArrayExtra("cfWorkerDomains")?.toList() ?: Prefs.tgwsCfWorkerDomains
                val cfProxyDomains = intent.getStringArrayExtra("cfProxyDomains")?.toList() ?: Prefs.tgwsCfProxyDomains
                val fallbackCfProxy = intent.getBooleanExtra("fallbackCfProxy", Prefs.tgwsFallbackCfProxy)
                val cfProxyH2Media = intent.getBooleanExtra("cfProxyH2Media", Prefs.tgwsCfProxyH2Media)
                val frontingSni = intent.getStringExtra("frontingSni") ?: Prefs.tgwsFrontingSni
                val disableSecure = intent.getBooleanExtra("disableSecure", Prefs.tgwsDisableSecure)
                val forceTestDc = intent.getBooleanExtra("forceTestDc", Prefs.tgwsForceTestDc)
                val poolSize = intent.getIntExtra("poolSize", Prefs.tgwsPoolSize)

                scope.launch {
                    startCoreInternal(
                        host, port, useByeDpi, dcMappings, secret, fakeTls,
                        cfWorkerDomains, cfProxyDomains, fallbackCfProxy,
                        cfProxyH2Media, frontingSni, disableSecure, forceTestDc, poolSize
                    )
                }
                return START_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun startCoreInternal(
        host: String,
        port: Int,
        useByeDpi: Boolean,
        dcMappings: Map<Int, String>,
        secret: String,
        fakeTls: String,
        cfWorkerDomains: List<String>,
        cfProxyDomains: List<String>,
        fallbackCfProxy: Boolean,
        cfProxyH2Media: Boolean,
        frontingSni: String,
        disableSecure: Boolean,
        forceTestDc: Boolean,
        poolSize: Int
    ) {
        mutex.withLock {
            val previousPort = tgwsCore?.port
            tgwsCore?.stop()
            tgwsCore = null
            if (previousPort != null) {
                waitForPortRelease(previousPort)
            }

            val bindDeferred = CompletableDeferred<Boolean>()
            var createdCore: TgwsCore? = null
            val newCore = TgwsCore(
                host = host,
                port = port,
                dcMappings = dcMappings,
                secret = secret,
                fakeTlsDomain = fakeTls,
                useByeDpi = useByeDpi,
                disableWebSockets = false,
                cfWorkerDomains = cfWorkerDomains,
                cfProxyDomains = cfProxyDomains,
                fallbackCfProxy = fallbackCfProxy,
                cfProxyH2Media = cfProxyH2Media,
                frontingSni = frontingSni,
                disableSecure = disableSecure,
                forceTestDc = forceTestDc,
                poolSize = poolSize,
                onLog = { msg ->
                    synchronized(logHistory) {
                        if (logHistory.size >= 200) {
                            logHistory.removeAt(0)
                        }
                        logHistory.add(msg)
                    }
                    _logs.tryEmit(msg)
                    NotificationLogger.log(this, "tgws", msg)
                },
                onStateChanged = { running, error ->
                    if (running) {
                        bindDeferred.complete(true)
                    } else {
                        if (!bindDeferred.isCompleted) {
                            bindDeferred.complete(false)
                        }
                        Log.w(TAG, "TgwsCore stopped/failed: $error")
                        scope.launch {
                            mutex.withLock {
                                if (createdCore != null && tgwsCore === createdCore && !createdCore!!.isServerRunning) {
                                    Gatik.stop()
                                    broadcastStandaloneStatus(this@TgwsService)
                                }
                            }
                        }
                    }
                }
            )
            createdCore = newCore
            tgwsCore = newCore
            newCore.start()

            val boundOk = withTimeoutOrNull(2000L) { bindDeferred.await() } == true
            if (boundOk && newCore.isServerRunning) {
                Gatik.start(this, tgwsPort = port)
                broadcastStandaloneStatus(this)
            } else {
                Log.e(TAG, "TgwsCore failed to bind port $host:$port")
                NotificationLogger.log(this, "tgws", "Ошибка запуска TGWS Proxy на порту $port")
                Gatik.stop()
                tgwsCore?.stop()
                tgwsCore = null
                stopImmediate()
            }
        }
    }

    private fun waitForPortRelease(port: Int, timeoutMs: Long = 1000L) {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            var inUse = false
            try {
                Socket().use { s ->
                    s.connect(InetSocketAddress("127.0.0.1", port), 100)
                    inUse = true
                }
            } catch (_: Exception) {}
            if (!inUse) break
            try { Thread.sleep(50) } catch (_: InterruptedException) { break }
        }
    }

    fun stopImmediate() {
        Gatik.stop()
        tgwsCore?.stop()
        tgwsCore = null
        val wasFg = isForegroundService
        isForegroundService = false
        stopSharedForeground(this, "tgws", wasForeground = wasFg)
        if (activeService == this) {
            activeService = null
        }
        stopSelf()
        broadcastStandaloneStatus(this)
    }

    override fun onDestroy() {
        Gatik.stop()
        tgwsCore?.stop()
        tgwsCore = null
        val wasFg = isForegroundService
        isForegroundService = false
        stopSharedForeground(this, "tgws", wasForeground = wasFg)
        if (activeService == this) {
            activeService = null
        }
        scope.cancel()
        broadcastStandaloneStatus(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val notificationIntent = Intent(this, ToInetActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        ensureSharedNotificationChannels(this)
        val (actualId, priority) = resolveNotificationChannelAndPriority("tgws", NOTIFICATION_CHANNEL_ID)
        val isDetailed = Prefs.shouldShowDetailedNotification("tgws")

        val builder = NotificationCompat.Builder(this, actualId)
            .setSmallIcon(R.drawable.ic_send_airplane)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setGroup(NOTIFICATION_GROUP_KEY)
            .setContentIntent(pendingIntent)
            .setPriority(priority)

        if (!isDetailed) {
            builder.setContentTitle("ToInet")
                .setContentText(null)
                .setSubText(null)
        } else {
            builder.setContentTitle("TGWS Proxy")
                .setContentText("TGWS Proxy запущен на порту ${Prefs.tgwsPort}")
        }

        return builder.build()
    }

    private fun startForegroundNotification() {
        startSharedForeground(this, "tgws") { buildNotification() }
        isForegroundService = true
    }
}

package ru.toinet.android.tgws

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import ru.toinet.android.ToInetActivity
import ru.toinet.android.R
import ru.toinet.android.util.Prefs

class TgwsService : Service() {
    private var tgwsCore: TgwsCore? = null

    companion object {
        const val ACTION_START = "ru.toinet.android.tgws.START"
        const val ACTION_STOP = "ru.toinet.android.tgws.STOP"
        private const val NOTIFICATION_CHANNEL_ID = "tgws_channel"
        private const val NOTIFICATION_ID = 1480

        private val _logs = MutableSharedFlow<String>(extraBufferCapacity = 100)
        val logs = _logs.asSharedFlow()
        val logHistory: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf<String>())

        var isRunning = false
            private set

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
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {
                try {
                    context.startService(intent)
                } catch (_: Exception) {}
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, TgwsService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                context.startService(intent)
            } catch (_: Exception) {
                try {
                    context.stopService(intent)
                } catch (_: Exception) {}
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundNotification()

        val host = intent?.getStringExtra("host") ?: Prefs.tgwsHost
        val port = intent?.getIntExtra("port", Prefs.tgwsPort) ?: Prefs.tgwsPort
        val useByeDpi = intent?.getBooleanExtra("useByeDpi", false) ?: false
        val mappingArray = intent?.getStringArrayExtra("dcMappings")
        val dcMappings = if (mappingArray != null && mappingArray.isNotEmpty()) {
            mappingArray.associate {
                val parts = it.split(":")
                parts[0].toInt() to parts[1]
            }
        } else {
            Prefs.tgwsDcMappings
        }

        val secret = intent?.getStringExtra("secret") ?: Prefs.tgwsSecret
        val fakeTls = intent?.getStringExtra("fakeTls") ?: Prefs.tgwsFakeTls
        val cfWorkerDomains = intent?.getStringArrayExtra("cfWorkerDomains")?.toList() ?: Prefs.tgwsCfWorkerDomains
        val cfProxyDomains = intent?.getStringArrayExtra("cfProxyDomains")?.toList() ?: Prefs.tgwsCfProxyDomains
        val fallbackCfProxy = intent?.getBooleanExtra("fallbackCfProxy", Prefs.tgwsFallbackCfProxy) ?: Prefs.tgwsFallbackCfProxy
        val cfProxyH2Media = intent?.getBooleanExtra("cfProxyH2Media", Prefs.tgwsCfProxyH2Media) ?: Prefs.tgwsCfProxyH2Media
        val frontingSni = intent?.getStringExtra("frontingSni") ?: Prefs.tgwsFrontingSni
        val disableSecure = intent?.getBooleanExtra("disableSecure", Prefs.tgwsDisableSecure) ?: Prefs.tgwsDisableSecure
        val forceTestDc = intent?.getBooleanExtra("forceTestDc", Prefs.tgwsForceTestDc) ?: Prefs.tgwsForceTestDc
        val poolSize = intent?.getIntExtra("poolSize", Prefs.tgwsPoolSize) ?: Prefs.tgwsPoolSize

        tgwsCore?.stop()
        tgwsCore = TgwsCore(
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
            poolSize = poolSize
        ) { msg ->
            synchronized(logHistory) {
                if (logHistory.size >= 200) {
                    logHistory.removeAt(0)
                }
                logHistory.add(msg)
            }
            _logs.tryEmit(msg)
            ru.toinet.android.util.NotificationLogger.log(this, "tgws", msg)
        }
        tgwsCore?.start()
        isRunning = true

        return START_STICKY
    }

    override fun onDestroy() {
        tgwsCore?.stop()
        tgwsCore = null
        isRunning = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.tgws_proxy_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    private fun startForegroundNotification() {
        if (!Prefs.shouldServiceShowNotification("tgws") && Prefs.isGlobalVpnEnabled) {
            return
        }
        val notificationIntent = Intent(this, ToInetActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("TGWS Proxy")
            .setContentText("TGWS Proxy запущен на порту ${Prefs.tgwsPort}")
            .setSmallIcon(R.drawable.ic_send_airplane)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
}

package ru.toinet.android.tgws

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import ru.toinet.android.OrbotActivity
import ru.toinet.android.R

class TgwsService : Service() {
    private var tgwsCore: TgwsCore? = null

    companion object {
        const val ACTION_STOP = "ru.toinet.android.tgws.STOP"
        private val _logs = MutableSharedFlow<String>(extraBufferCapacity = 100)
        val logs = _logs.asSharedFlow()

        var isRunning = false
            private set

        fun start(context: Context, host: String, port: Int, dcMappings: Map<Int, String>, secret: String = "", fakeTls: String = "", useByeDpi: Boolean = false) {
            val intent = Intent(context, TgwsService::class.java).apply {
                putExtra("host", host)
                putExtra("port", port)
                val mappingList = dcMappings.map { "${it.key}:${it.value}" }.toTypedArray()
                putExtra("dcMappings", mappingList)
                putExtra("secret", secret)
                putExtra("fakeTls", fakeTls)
                putExtra("useByeDpi", useByeDpi)
            }
            context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TgwsService::class.java))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val host = intent?.getStringExtra("host") ?: "127.0.0.1"
        val port = intent?.getIntExtra("port", 1480) ?: 1480
        val useByeDpi = intent?.getBooleanExtra("useByeDpi", false) ?: false
        val mappingArray = intent?.getStringArrayExtra("dcMappings") ?: emptyArray()
        val dcMappings = mappingArray.associate {
            val parts = it.split(":")
            parts[0].toInt() to parts[1]
        }

        val secret = intent?.getStringExtra("secret") ?: ""
        val fakeTls = intent?.getStringExtra("fakeTls") ?: ""

        tgwsCore?.stop()
        tgwsCore = TgwsCore(host, port, dcMappings, secret, fakeTls, useByeDpi) { msg ->
            _logs.tryEmit(msg)
            ru.toinet.android.util.NotificationLogger.log(this, "tgws", msg)
        }
        tgwsCore?.start()
        isRunning = true

        return START_STICKY
    }

    override fun onDestroy() {
        tgwsCore?.stop()
        isRunning = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                "tgws_channel",
                getString(R.string.tgws_proxy_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }
}
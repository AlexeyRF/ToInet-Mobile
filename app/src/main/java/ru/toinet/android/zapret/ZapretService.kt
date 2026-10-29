package ru.toinet.android.zapret

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import ru.toinet.android.R
import ru.toinet.android.ToInetActivity
import ru.toinet.android.util.NotificationLogger
import ru.toinet.android.util.Prefs
import java.io.File
import java.io.FileOutputStream

class ZapretService : Service() {

    private var process: Process? = null

    companion object {
        private const val TAG = "ZapretService"
        private const val NOTIFICATION_CHANNEL_ID = "zapret_channel"
        private const val NOTIFICATION_ID = 1081

        const val ACTION_START = "ru.toinet.android.zapret.START"
        const val ACTION_STOP = "ru.toinet.android.zapret.STOP"

        private val _logs = MutableSharedFlow<String>(extraBufferCapacity = 100)
        val logs = _logs.asSharedFlow()

        var isRunning = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, ZapretService::class.java).apply {
                action = ACTION_START
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to startForegroundService, attempting fallback startService", e)
                try {
                    context.startService(intent)
                } catch (e2: Exception) {
                    Log.e(TAG, "Failed to start ZapretService", e2)
                }
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, ZapretService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop ZapretService", e)
            }
        }

        fun getExecutable(context: Context): String? {
            val libDir = File(context.applicationInfo.nativeLibraryDir)
            var executable = libDir.listFiles { f ->
                f.name == "libtpws.so" || (f.name.startsWith("libtpws") && f.name.endsWith(".so"))
            }?.firstOrNull()

            if (executable != null && executable.canExecute()) {
                return executable.absolutePath
            }

            try {
                val fallbackFile = File(context.filesDir, "libtpws.so")
                if (executable != null && executable.exists()) {
                    executable.inputStream().use { input ->
                        FileOutputStream(fallbackFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    fallbackFile.setExecutable(true, false)
                    return fallbackFile.absolutePath
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to copy tpws binary to filesDir", e)
            }

            return executable?.absolutePath
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ru.toinet.android.vpn.utility.registerNotificationChannel(this, NOTIFICATION_CHANNEL_ID, "Zapret Proxy Service")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForegroundNotification()
                startInternal()
                return START_STICKY
            }
            "ACTIVE" -> {
                if (isRunning) {
                    startForegroundNotification()
                }
                return START_STICKY
            }
            ACTION_STOP -> {
                stopInternal()
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

        ru.toinet.android.vpn.utility.registerNotificationChannel(this, NOTIFICATION_CHANNEL_ID, "Zapret Proxy Service")

        val (actualId, priority) = ru.toinet.android.vpn.utility.resolveNotificationChannelAndPriority("zapret", NOTIFICATION_CHANNEL_ID)
        val isDetailed = Prefs.shouldShowDetailedNotification("zapret")

        val builder = NotificationCompat.Builder(this, actualId)
            .setSmallIcon(R.drawable.ic_stat_tor)
            .setSilent(true)
            .setOngoing(true)
            .setGroup("toinet_services")
            .setContentIntent(pendingIntent)
            .setPriority(priority)

        if (!isDetailed) {
            builder.setContentTitle("ToInet")
                .setContentText(getString(R.string.zapret_notification_title))
                .setSubText(null)
        } else {
            builder.setContentTitle(getString(R.string.zapret_notification_title))
                .setContentText("SOCKS5: ${Prefs.zapretHost}:${Prefs.zapretPort}")
        }

        val notification = builder.build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startInternal() {
        stopInternal()

        val executablePath = getExecutable(this)
        if (executablePath == null) {
            val msg = "Ошибка: Исполняемый файл Zapret (libtpws.so) не найден."
            Log.e(TAG, msg)
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
            }
            stopSelf()
            return
        }

        val bindHost = Prefs.zapretHost
        val port = Prefs.zapretPort
        val cmdArgs = mutableListOf<String>()
        cmdArgs.add(executablePath)
        cmdArgs.add("--socks")
        cmdArgs.add("--bind-addr=$bindHost")
        cmdArgs.add("--port=$port")

        val rawArgs = Prefs.zapretArgs.trim()
        if (rawArgs.isNotEmpty()) {
            val parts = rawArgs.split("\\s+".toRegex()).filter { it.isNotEmpty() }
            for (part in parts) {
                if (!part.startsWith("--bind-addr") &&
                    !part.startsWith("--port") &&
                    part != "--socks") {
                    cmdArgs.add(part)
                }
            }
        }
        cmdArgs.add("--debug=1")

        try {
            val pb = ProcessBuilder(cmdArgs)
            pb.directory(filesDir)
            pb.redirectErrorStream(true)
            val proc = pb.start()
            process = proc
            isRunning = true
            Log.i(TAG, "Zapret started with args: $cmdArgs")

            Thread {
                try {
                    proc.inputStream.bufferedReader().use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            val l = line!!
                            Log.d(TAG, "Zapret: $l")
                            NotificationLogger.log(this@ZapretService, "zapret", l)
                            _logs.tryEmit(l)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error reading Zapret output", e)
                } finally {
                    isRunning = false
                }
            }.apply {
                isDaemon = true
                name = "Zapret-OutputReader"
                start()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Zapret process", e)
            isRunning = false
            stopSelf()
        }
    }

    private fun stopInternal() {
        try {
            process?.destroy()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to destroy Zapret process", e)
        }
        process = null
        isRunning = false
        Log.i(TAG, "Zapret stopped")
    }

    override fun onDestroy() {
        stopInternal()
        super.onDestroy()
    }
}

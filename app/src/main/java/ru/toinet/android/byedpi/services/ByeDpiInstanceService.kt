package ru.toinet.android.byedpi.services

import android.app.ActivityManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import ru.toinet.android.byedpi.core.ByeDpiProxy
import ru.toinet.android.byedpi.core.ByeDpiProxyCmdPreferences
import ru.toinet.android.vpn.utility.ACTION_REFRESH_FOREGROUND_NOTIFICATION
import ru.toinet.android.vpn.utility.SHARED_FOREGROUND_ID
import ru.toinet.android.vpn.utility.buildSilentFallbackNotification
import ru.toinet.android.vpn.utility.registerNotificationChannel
import kotlin.concurrent.thread

open class ByeDpiInstanceService : Service() {
    private var proxy: ByeDpiProxy? = null
    private var proxyThread: Thread? = null
    private var isForegroundService = false

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "byedpi_instance"
    }

    override fun onCreate() {
        super.onCreate()
        registerNotificationChannel(this, NOTIFICATION_CHANNEL_ID, "zDPI Router Instance")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val fgRequested = intent?.getBooleanExtra("is_foreground", false) ?: false

        if (fgRequested || action == "START") {
            startSilentForeground()
        }

        if (action == "START") {
            val cmd = intent.getStringExtra("CMD")
            if (cmd == null) {
                stopForegroundAndSelf()
                return START_NOT_STICKY
            }
            val port = intent.getIntExtra("PORT", 1081)

            proxy?.stopProxy()
            proxyThread?.interrupt()

            proxy = ByeDpiProxy()
            proxyThread = thread {
                val prefs = ByeDpiProxyCmdPreferences(cmd)
                val newArgs = mutableListOf<String>()
                var skipNext = false
                for (arg in prefs.args) {
                    if (skipNext) { skipNext = false; continue }
                    if (arg == "-p" || arg == "--port" || arg == "-i" || arg == "--ip") { skipNext = true; continue }
                    val isPortArg = (arg.startsWith("-p") && arg.length > 2 && arg[2].isDigit()) || arg.startsWith("--port=")
                    val isIpArg = (arg.startsWith("-i") && arg.length > 2 && (arg[2].isDigit() || arg[2] == ':')) || arg.startsWith("--ip=")
                    if (isPortArg || isIpArg) continue
                    newArgs.add(arg)
                }
                newArgs.add("-i"); newArgs.add("127.0.0.1")
                newArgs.add("-p"); newArgs.add(port.toString())

                proxy?.startProxy(ByeDpiProxyCmdPreferences(newArgs.toTypedArray()))
            }
            return START_NOT_STICKY
        } else if (action == "STOP") {
            proxy?.stopProxy()
            proxyThread?.interrupt()
            stopForegroundAndSelf()
        } else if (fgRequested) {
            stopForegroundAndSelf()
        }
        return START_NOT_STICKY
    }

    private fun startSilentForeground() {
        val notification = buildSilentFallbackNotification(this)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(SHARED_FOREGROUND_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(SHARED_FOREGROUND_ID, notification)
            }
            isForegroundService = true
        } catch (_: Exception) {
            try {
                startForeground(SHARED_FOREGROUND_ID, notification)
                isForegroundService = true
            } catch (_: Exception) {}
        }
        if (isParentForegroundRunning()) {
            sendBroadcast(Intent(ACTION_REFRESH_FOREGROUND_NOTIFICATION).setPackage(packageName))
        }
    }

    @Suppress("DEPRECATION")
    private fun isParentForegroundRunning(): Boolean {
        return try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
            am.getRunningServices(100).any {
                it.service.packageName == packageName &&
                    it.foreground &&
                    !it.service.className.startsWith("ru.toinet.android.byedpi.services.ByeDpiInstance")
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun stopForegroundAndSelf() {
        if (isForegroundService) {
            if (isParentForegroundRunning()) {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
                sendBroadcast(Intent(ACTION_REFRESH_FOREGROUND_NOTIFICATION).setPackage(packageName))
            } else {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            }
            isForegroundService = false
        }
        stopSelf()
    }

    override fun onDestroy() {
        if (isForegroundService) {
            if (isParentForegroundRunning()) {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
            } else {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            }
            isForegroundService = false
        }
        super.onDestroy()
        proxy?.stopProxy()
        proxyThread?.interrupt()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

class ByeDpiInstance1Service : ByeDpiInstanceService()
class ByeDpiInstance2Service : ByeDpiInstanceService()
class ByeDpiInstance3Service : ByeDpiInstanceService()
class ByeDpiInstance4Service : ByeDpiInstanceService()
class ByeDpiInstance5Service : ByeDpiInstanceService()

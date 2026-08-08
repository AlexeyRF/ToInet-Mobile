package ru.toinet.android.byedpi.services

import android.app.Service
import android.content.Intent
import android.os.IBinder
import ru.toinet.android.byedpi.core.ByeDpiProxy
import ru.toinet.android.byedpi.core.ByeDpiProxyCmdPreferences
import kotlin.concurrent.thread

open class ByeDpiInstanceService : Service() {
    private var proxy: ByeDpiProxy? = null
    private var proxyThread: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == "START") {
            val cmd = intent.getStringExtra("CMD") ?: return START_NOT_STICKY
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
                    if (arg == "-p" || arg == "-i") { skipNext = true; continue }
                    if (arg.startsWith("-p") || arg.startsWith("--port=") || arg.startsWith("-i") || arg.startsWith("--ip=")) continue
                    newArgs.add(arg)
                }
                newArgs.add("-i"); newArgs.add("127.0.0.1")
                newArgs.add("-p"); newArgs.add(port.toString())
                
                proxy?.startProxy(ByeDpiProxyCmdPreferences(newArgs.toTypedArray()))
            }
        } else if (action == "STOP") {
            proxy?.stopProxy()
            proxyThread?.interrupt()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
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

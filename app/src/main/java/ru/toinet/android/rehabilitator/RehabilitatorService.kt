package ru.toinet.android.rehabilitator

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import ru.toinet.android.byedpi.core.ByeDpiProxy
import ru.toinet.android.byedpi.core.ByeDpiProxyPreferences
import ru.toinet.android.byedpi.core.ByeDpiProxyCmdPreferences
import ru.toinet.android.util.Prefs
import java.net.InetSocketAddress
import android.util.Log

class RehabilitatorService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var proxy: ByeDpiProxy? = null
    private var socksServer: Socks5Server? = null
    private var byedpiJob: Job? = null

    companion object {
        const val ACTION_START = "ru.toinet.android.rehabilitator.START"
        const val ACTION_STOP = "ru.toinet.android.rehabilitator.STOP"
        private const val TAG = "RehabilitatorService"

        fun start(context: Context) {
            val intent = Intent(context, RehabilitatorService::class.java).apply {
                action = ACTION_START
            }
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, RehabilitatorService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startInternal()
            ACTION_STOP -> {
                stopInternal()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startInternal() {
        stopInternal()

        val byedpiPort = 10810
        val preferences = ByeDpiProxyCmdPreferences("-i 127.0.0.1 -p $byedpiPort " + Prefs.rehabilitatorArgs)

        proxy = ByeDpiProxy()
        byedpiJob = scope.launch {
            try {
                proxy?.startProxy(preferences)
            } catch (e: Exception) {
                Log.e(TAG, "ByeDPI failed", e)
            }
        }

        val upstreamAddr = InetSocketAddress(Prefs.rehabilitatorUpstreamHost, Prefs.rehabilitatorUpstreamPort)
        val listenAddr = InetSocketAddress(Prefs.rehabilitatorHost, 1788)
        val byedpiAddr = InetSocketAddress("127.0.0.1", byedpiPort)

        socksServer = Socks5Server(
            listenAddr = listenAddr,
            byedpiAddr = byedpiAddr,
            upstreamAddr = upstreamAddr,
            username = Prefs.rehabilitatorUsername.takeIf { it.isNotEmpty() },
            password = Prefs.rehabilitatorPassword.takeIf { it.isNotEmpty() }
        )
        socksServer?.start(scope)
    }

    private fun stopInternal() {
        socksServer?.stop()
        socksServer = null

        scope.launch {
            proxy?.stopProxy()
            byedpiJob?.join()
            proxy = null
        }
    }

    override fun onDestroy() {
        stopInternal()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

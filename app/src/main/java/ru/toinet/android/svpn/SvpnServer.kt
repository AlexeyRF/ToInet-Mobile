package ru.toinet.android.svpn

import android.content.Context
import android.net.VpnService
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import ru.toinet.android.util.NotificationLogger
import ru.toinet.android.util.Prefs

class SvpnServer(
    private val context: Context,
    private val scope: CoroutineScope,
    private val vpnService: VpnService? = if (context is VpnService) context else null
) {
    companion object {
        private const val TAG = "SvpnServer"
        @Volatile
        var isRunning = false
            private set
        @Volatile
        var activeInstance: SvpnServer? = null
            private set
    }

    @Volatile
    private var tunnel: SshTunnel? = null

    @Volatile
    private var internalError: String? = null

    @Volatile
    private var isStopped: Boolean = false

    @Volatile
    var isServerRunning: Boolean = false
        private set

    var onConnectionStateChanged: ((connected: Boolean, error: String?) -> Unit)? = null

    val lastErrorMessage: String?
        get() = internalError ?: tunnel?.lastError

    fun start(): Boolean {
        stop()
        synchronized(this) {
            isStopped = false
        }
        activeInstance = this
        internalError = null

        val host = Prefs.svpnHost.trim()
        if (host.isEmpty()) {
            val msg = "SVPN: SSH хост не настроен. Укажите параметры в настройках."
            Log.e(TAG, msg)
            NotificationLogger.log(context, "svpn", msg)
            internalError = msg
            onConnectionStateChanged?.invoke(false, msg)
            return false
        }

        val port = Prefs.svpnPort
        val user = Prefs.svpnUser.ifBlank { "root" }
        val authType = Prefs.svpnAuthType
        val password = if (authType == "password") Prefs.svpnPassword else null
        val privateKey = if (authType == "key") Prefs.svpnPrivateKey else null
        val passphrase = if (authType == "key") Prefs.svpnKeyPassphrase else null
        val bindHost = if (Prefs.openProxyOnAllInterfaces) "0.0.0.0" else "127.0.0.1"
        val localPort = Prefs.svpnLocalPort

        Log.i(TAG, "Starting SVPN SSH tunnel to $user@$host:$port, local SOCKS5 on $bindHost:$localPort")
        NotificationLogger.log(context, "svpn", "Подключение к SSH серверу $host:$port...")

        val newTunnel = SshTunnel(
            host = host,
            port = port,
            user = user,
            password = password,
            privateKey = privateKey,
            passphrase = passphrase,
            bindHost = bindHost,
            localPort = localPort
        )

        tunnel = newTunnel

        newTunnel.start(vpnService, object : SshTunnel.TunnelListener {
            override fun onConnected() {
                if (isStopped || tunnel !== newTunnel) {
                    newTunnel.stop()
                    return
                }
                isServerRunning = true
                isRunning = true
                internalError = null
                Log.i(TAG, "SVPN Connected")
                NotificationLogger.log(context, "svpn", "SVPN подключен (порт $localPort)")
                onConnectionStateChanged?.invoke(true, null)
            }

            override fun onDisconnected() {
                isServerRunning = false
                if (activeInstance == this@SvpnServer) {
                    isRunning = false
                }
                if (isStopped) return
                Log.w(TAG, "SVPN Disconnected")
                NotificationLogger.log(context, "svpn", "SVPN отключен")
                onConnectionStateChanged?.invoke(false, "Disconnected")
            }

            override fun onError(message: String, t: Throwable?) {
                isServerRunning = false
                if (activeInstance == this@SvpnServer) {
                    isRunning = false
                }
                internalError = message
                if (isStopped) return
                Log.e(TAG, "SVPN Error: $message", t)
                NotificationLogger.log(context, "svpn", "Ошибка SVPN: $message")
                onConnectionStateChanged?.invoke(false, message)
            }
        })
        return true
    }

    /**
     * Starts the SVPN SSH tunnel and synchronously blocks until connected or failed/timed out.
     */
    fun startAndWait(timeoutMs: Long = 12000L): Boolean {
        if (!start() || internalError != null) {
            return false
        }
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (isStopped) return false
            if (isServerRunning && tunnel?.isProxyListening == true) {
                return true
            }
            if (internalError != null || tunnel?.lastError != null) {
                return false
            }
            try {
                Thread.sleep(100)
            } catch (_: InterruptedException) {
                break
            }
        }
        val success = !isStopped && isServerRunning && tunnel?.isProxyListening == true
        if (!success && internalError == null) {
            internalError = "SVPN: таймаут подключения к SSH серверу"
        }
        return success
    }

    /**
     * Starts the SVPN SSH tunnel and suspends cleanly until connected, cancelled, or timed out.
     */
    suspend fun startAndWaitSuspend(timeoutMs: Long = 12000L): Boolean {
        if (!start() || internalError != null) {
            return false
        }
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs && currentCoroutineContext().isActive) {
            if (isStopped) return false
            if (isServerRunning && tunnel?.isProxyListening == true) {
                return true
            }
            if (internalError != null || tunnel?.lastError != null) {
                return false
            }
            delay(100)
        }
        val cancelled = !currentCoroutineContext().isActive
        val success = !cancelled && !isStopped && isServerRunning && tunnel?.isProxyListening == true
        if (!success) {
            if (cancelled || isStopped) {
                stop()
            } else if (internalError == null) {
                internalError = "SVPN: таймаут подключения к SSH серверу"
            }
        }
        return success
    }

    @Synchronized
    fun stop() {
        isStopped = true
        isServerRunning = false
        if (activeInstance == this) {
            isRunning = false
            activeInstance = null
        }
        try {
            tunnel?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping SVPN tunnel", e)
        }
        tunnel = null
        internalError = null
        Log.i(TAG, "SVPN stopped")
    }
}

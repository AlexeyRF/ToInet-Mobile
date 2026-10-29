package ru.toinet.android.svpn

import android.content.Context
import android.net.VpnService
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import ru.toinet.android.util.NotificationLogger
import ru.toinet.android.util.Prefs

class SvpnServer(
    private val context: Context,
    private val scope: CoroutineScope,
    private val vpnService: VpnService? = if (context is VpnService) context else null
) {
    companion object {
        private const val TAG = "SvpnServer"
        var isRunning = false
            private set
    }

    private var tunnel: SshTunnel? = null

    fun start() {
        stop()

        val host = Prefs.svpnHost.trim()
        if (host.isEmpty()) {
            val msg = "SVPN: SSH хост не настроен. Укажите параметры в настройках."
            Log.e(TAG, msg)
            NotificationLogger.log(context, "svpn", msg)
            return
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
                isRunning = true
                Log.i(TAG, "SVPN Connected")
                NotificationLogger.log(context, "svpn", "SVPN подключен (порт $localPort)")
            }

            override fun onDisconnected() {
                isRunning = false
                Log.w(TAG, "SVPN Disconnected")
                NotificationLogger.log(context, "svpn", "SVPN отключен")
            }

            override fun onError(message: String, t: Throwable?) {
                isRunning = false
                Log.e(TAG, "SVPN Error: $message", t)
                NotificationLogger.log(context, "svpn", "Ошибка SVPN: $message")
            }
        })
    }

    fun stop() {
        try {
            tunnel?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping SVPN tunnel", e)
        }
        tunnel = null
        isRunning = false
        Log.i(TAG, "SVPN stopped")
    }
}

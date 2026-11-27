package ru.toinet.android.svpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.InetAddresses
import android.net.VpnService
import android.util.Log
import java.net.InetAddress

class SshTunnel(
    private val host: String,
    private val port: Int = 22,
    private val user: String = "root",
    private val password: String? = null,
    private val privateKey: String? = null,
    private val passphrase: String? = null,
    private val bindHost: String = "127.0.0.1",
    private val localPort: Int = 1770,
    private val connectTimeoutMs: Int = 25000
) {
    companion object {
        private const val TAG = "SvpnSshTunnel"

        init {
            System.loadLibrary("svpn")
        }

        @JvmStatic
        private external fun nativeTestConnection(
            host: String,
            resolvedHint: String?,
            port: Int,
            user: String,
            password: String?,
            privateKey: String?,
            passphrase: String?,
            timeoutMs: Int,
            vpnService: VpnService?
        ): String?

        fun testConnection(
            host: String,
            port: Int,
            user: String,
            password: String?,
            privateKey: String?,
            passphrase: String?,
            timeoutMs: Int = 10000,
            vpnService: VpnService? = null
        ): Pair<Boolean, String> {
            val cleanHost = host.trim()
            val resolvedHint = resolveHostHintIfDomain(vpnService, cleanHost)
            val err = nativeTestConnection(
                host = cleanHost,
                resolvedHint = resolvedHint,
                port = port,
                user = user,
                password = password,
                privateKey = privateKey?.trim(),
                passphrase = passphrase,
                timeoutMs = timeoutMs,
                vpnService = vpnService
            )
            return if (err == null) {
                Pair(true, "Успешно: подключение установлено")
            } else {
                Log.w(TAG, "Test connection failed: $err")
                Pair(false, "Ошибка: $err")
            }
        }

        private fun resolveHostHintIfDomain(vpnService: VpnService?, rawHost: String): String? {
            val clean = if (rawHost.startsWith("[") && rawHost.endsWith("]") && rawHost.length > 2) {
                rawHost.substring(1, rawHost.length - 1)
            } else {
                rawHost
            }
            if (clean.isEmpty() || InetAddresses.isNumericAddress(clean)) {
                return null
            }
            if (vpnService != null) {
                try {
                    val cm = vpnService.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                    val activeNet = cm?.activeNetwork
                    if (activeNet != null) {
                        val addrs = activeNet.getAllByName(clean)
                        if (addrs.isNotEmpty()) return addrs[0].hostAddress
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed resolving $clean via activeNetwork: ${e.message}")
                }
            }
            return try {
                InetAddress.getByName(clean).hostAddress
            } catch (_: Exception) {
                null
            }
        }
    }

    interface TunnelListener {
        fun onConnected()
        fun onDisconnected()
        fun onError(message: String, t: Throwable?)
    }

    private var nativeHandle: Long = 0L
    @Volatile
    private var vpnServiceRef: VpnService? = null
    @Volatile
    private var listenerRef: TunnelListener? = null
    @Volatile
    private var isRunning = false

    @Volatile
    var lastError: String? = null
        private set

    val isProxyListening: Boolean
        get() = synchronized(this) {
            nativeHandle != 0L && nativeIsProxyListening(nativeHandle)
        }

    private external fun nativeCreate(): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeStart(
        handle: Long,
        host: String,
        resolvedHint: String?,
        port: Int,
        user: String,
        password: String?,
        privateKey: String?,
        passphrase: String?,
        bindHost: String,
        localPort: Int,
        timeoutMs: Int,
        callbackObj: Any
    ): Boolean
    private external fun nativeStop(handle: Long)
    private external fun nativeIsConnected(handle: Long): Boolean
    private external fun nativeIsProxyListening(handle: Long): Boolean
    private external fun nativeLastError(handle: Long): String?

    @Synchronized
    fun start(vpnService: VpnService?, listener: TunnelListener?) {
        if (isRunning) return
        isRunning = true
        lastError = null
        vpnServiceRef = vpnService
        listenerRef = listener

        if (nativeHandle == 0L) {
            nativeHandle = nativeCreate()
        }

        val cleanHost = host.trim()
        val resolvedHint = resolveHostHintIfDomain(vpnService, cleanHost)
        Log.d(TAG, "Starting native C++ SSH direct-tcpip tunnel for $user@$cleanHost:$port -> $bindHost:$localPort")

        val started = nativeStart(
            handle = nativeHandle,
            host = cleanHost,
            resolvedHint = resolvedHint,
            port = port,
            user = user,
            password = password,
            privateKey = privateKey?.trim(),
            passphrase = passphrase,
            bindHost = bindHost,
            localPort = localPort,
            timeoutMs = connectTimeoutMs,
            callbackObj = this
        )

        if (!started) {
            isRunning = false
            val msg = nativeLastError(nativeHandle) ?: "Failed to start native SSH tunnel"
            lastError = msg
            listener?.onError(msg, null)
        }
    }

    @Synchronized
    fun stop() {
        isRunning = false
        listenerRef = null
        if (nativeHandle != 0L) {
            nativeStop(nativeHandle)
            nativeDestroy(nativeHandle)
            nativeHandle = 0L
        }
        vpnServiceRef = null
    }

    @Synchronized
    fun isConnected(): Boolean {
        return isRunning && nativeHandle != 0L && nativeIsConnected(nativeHandle)
    }

    @Suppress("unused")
    private fun onNativeProtectSocket(fd: Int): Boolean {
        val vpn = vpnServiceRef ?: return true
        return try {
            val parcelFd = android.os.ParcelFileDescriptor.adoptFd(fd)
            try {
                vpn.protect(parcelFd.fd)
            } finally {
                // Detach so closing ParcelFileDescriptor does NOT close the C++ socket fd
                parcelFd.detachFd()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed protecting socket fd=$fd: ${e.message}")
            false
        }
    }

    @Suppress("unused")
    private fun onNativeConnected() {
        Log.i(TAG, "Native SSH direct-tcpip tunnel connected on $bindHost:$localPort")
        listenerRef?.onConnected()
    }

    @Suppress("unused")
    private fun onNativeDisconnected() {
        if (isRunning) {
            isRunning = false
            Log.w(TAG, "Native SSH session disconnected unexpectedly")
            listenerRef?.onDisconnected()
        }
    }

    @Suppress("unused")
    private fun onNativeError(message: String) {
        lastError = message
        if (isRunning) {
            isRunning = false
            Log.e(TAG, "Native SSH tunnel error: $message")
            listenerRef?.onError(message, null)
        }
    }
}

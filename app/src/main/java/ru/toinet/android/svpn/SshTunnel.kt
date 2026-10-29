package ru.toinet.android.svpn

import android.net.VpnService
import android.util.Log
import com.jcraft.jsch.*
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Properties

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
            var session: Session? = null
            return try {
                val jsch = JSch()
                if (!privateKey.isNullOrBlank()) {
                    val keyBytes = privateKey.trim().toByteArray(StandardCharsets.UTF_8)
                    val passBytes = if (!passphrase.isNullOrEmpty()) passphrase.toByteArray(StandardCharsets.UTF_8) else null
                    jsch.addIdentity("test_identity", keyBytes, null, passBytes)
                }

                session = jsch.getSession(user, host, port)
                if (!password.isNullOrEmpty()) {
                    session.setPassword(password)
                }
                session.userInfo = SshUserInfo(password, passphrase)

                val config = Properties().apply {
                    put("StrictHostKeyChecking", "no")
                    put("PreferredAuthentications", "publickey,keyboard-interactive,password")
                }
                session.setConfig(config)
                session.timeout = timeoutMs

                session.setSocketFactory(object : SocketFactory {
                    override fun createSocket(targetHost: String, targetPort: Int): Socket {
                        val socket = Socket()
                        vpnService?.protect(socket)
                        socket.tcpNoDelay = true
                        socket.keepAlive = true
                        socket.connect(InetSocketAddress(targetHost, targetPort), timeoutMs)
                        return socket
                    }
                    override fun getInputStream(socket: Socket): InputStream = socket.getInputStream()
                    override fun getOutputStream(socket: Socket): OutputStream = socket.getOutputStream()
                })

                session.connect(timeoutMs)
                Pair(true, "Успешно: подключение установлено")
            } catch (e: Exception) {
                Log.w(TAG, "Test connection failed", e)
                val msg = e.localizedMessage ?: e.message ?: e.toString()
                Pair(false, "Ошибка: $msg")
            } finally {
                try {
                    session?.disconnect()
                } catch (_: Exception) {}
            }
        }
    }

    interface TunnelListener {
        fun onConnected()
        fun onDisconnected()
        fun onError(message: String, t: Throwable?)
    }

    private var session: Session? = null
    private var proxyServer: SocksProxyServer? = null
    @Volatile
    private var isRunning = false
    private var workerThread: Thread? = null

    @Synchronized
    fun start(vpnService: VpnService?, listener: TunnelListener?) {
        if (isRunning) return
        isRunning = true

        workerThread = Thread({
            try {
                val jsch = JSch()

                // Load private key identity if provided
                if (!privateKey.isNullOrBlank()) {
                    val prvKeyBytes = privateKey.trim().toByteArray(StandardCharsets.UTF_8)
                    val passBytes = if (!passphrase.isNullOrEmpty()) passphrase.toByteArray(StandardCharsets.UTF_8) else null
                    jsch.addIdentity("custom_identity", prvKeyBytes, null, passBytes)
                }

                Log.d(TAG, "Creating SSH session for $user@$host:$port")
                val sess = jsch.getSession(user, host, port)

                if (!password.isNullOrEmpty()) {
                    sess.setPassword(password)
                }

                sess.userInfo = SshUserInfo(password, passphrase)

                val config = Properties().apply {
                    put("StrictHostKeyChecking", "no")
                    put("PreferredAuthentications", "publickey,keyboard-interactive,password")
                }
                sess.setConfig(config)
                sess.timeout = 0

                // Protect socket from VPN looping if running under VpnService
                sess.setSocketFactory(object : SocketFactory {
                    override fun createSocket(targetHost: String, targetPort: Int): Socket {
                        val socket = Socket()
                        if (vpnService != null) {
                            val protectedOk = vpnService.protect(socket)
                            Log.d(TAG, "VPN protect socket result: $protectedOk")
                        }
                        socket.tcpNoDelay = true
                        socket.keepAlive = true
                        socket.connect(InetSocketAddress(targetHost, targetPort), connectTimeoutMs)
                        return socket
                    }

                    override fun getInputStream(socket: Socket): InputStream = socket.getInputStream()
                    override fun getOutputStream(socket: Socket): OutputStream = socket.getOutputStream()
                })

                Log.d(TAG, "Connecting to SSH server...")
                sess.connect(connectTimeoutMs)

                Log.d(TAG, "Starting local SOCKS5 proxy on $bindHost:$localPort")
                val proxy = SocksProxyServer(sess, bindHost, localPort)
                proxy.start()

                synchronized(this@SshTunnel) {
                    session = sess
                    proxyServer = proxy
                }

                Log.i(TAG, "SSH Tunnel connected and SOCKS proxy active on $bindHost:$localPort")
                listener?.onConnected()

                // Keep-alive loop while running (send explicit keepalive every 25 seconds)
                var lastKeepAlive = System.currentTimeMillis()
                while (isRunning && sess.isConnected) {
                    try {
                        Thread.sleep(1000)
                        val now = System.currentTimeMillis()
                        if (now - lastKeepAlive >= 25000) {
                            lastKeepAlive = now
                            sess.sendKeepAliveMsg()
                        }
                    } catch (_: InterruptedException) {
                        break
                    } catch (_: Exception) {}
                }

                if (isRunning) {
                    Log.w(TAG, "SSH session disconnected unexpectedly")
                    stop()
                    listener?.onDisconnected()
                }
            } catch (e: Throwable) {
                Log.e(TAG, "SSH Tunnel error", e)
                stop()
                val msg = e.localizedMessage ?: e.message ?: e.toString()
                listener?.onError(msg, e)
            }
        }, "SvpnTunnelThread")

        workerThread?.start()
    }

    @Synchronized
    fun stop() {
        isRunning = false
        proxyServer?.stop()
        proxyServer = null

        try {
            session?.disconnect()
        } catch (_: Exception) {}
        session = null

        workerThread?.interrupt()
        workerThread = null
    }

    @Synchronized
    fun isConnected(): Boolean {
        return isRunning && session != null && session!!.isConnected
    }

    private class SshUserInfo(
        private val password: String?,
        private val passphrase: String?
    ) : UserInfo, UIKeyboardInteractive {
        override fun getPassphrase(): String = passphrase ?: ""
        override fun getPassword(): String = password ?: ""
        override fun promptPassword(message: String?): Boolean = true
        override fun promptPassphrase(message: String?): Boolean = true
        override fun promptYesNo(message: String?): Boolean = true
        override fun showMessage(message: String?) {
            Log.d(TAG, "UserInfo message: $message")
        }
        override fun promptKeyboardInteractive(
            destination: String?,
            name: String?,
            instruction: String?,
            prompt: Array<out String>?,
            echo: BooleanArray?
        ): Array<String> {
            if (prompt.isNullOrEmpty()) return emptyArray()
            return Array(prompt.size) { password ?: "" }
        }
    }
}

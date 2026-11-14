package ru.toinet.android.dns

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import kotlinx.coroutines.*
import java.io.InputStream
import java.io.OutputStream
import java.net.*
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.coroutines.CoroutineContext

class DnsProxyServer(private val context: Context) : CoroutineScope {
    private val TAG = "DnsProxyServer"
    
    private val job = Job()
    override val coroutineContext: CoroutineContext = Dispatchers.IO + job

    private var serverSocket: DatagramSocket? = null
    var localPort: Int = 0
        private set
        
    private var isRunning = false

    fun start(config: DnsConfig) {
        if (isRunning) return
        isRunning = true
        
        serverSocket = DatagramSocket(0)
        localPort = serverSocket!!.localPort
        
        Log.i(TAG, "DNS Proxy Server started on port $localPort with config: $config")

        launch {
            val buffer = ByteArray(4096)
            while (isRunning) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    serverSocket?.receive(packet) ?: break
                    
                    val requestBytes = packet.data.copyOf(packet.length)
                    val clientAddress = packet.address
                    val clientPort = packet.port
                    
                    launch {
                        handleDnsRequest(requestBytes, clientAddress, clientPort, config)
                    }
                } catch (e: Exception) {
                    if (isRunning) Log.e(TAG, "Error receiving DNS packet", e)
                }
            }
        }
    }

    fun stop() {
        isRunning = false
        serverSocket?.close()
        job.cancelChildren()
        Log.i(TAG, "DNS Proxy Server stopped")
    }

    private suspend fun handleDnsRequest(requestBytes: ByteArray, clientAddr: InetAddress, clientPort: Int, config: DnsConfig) {
        try {
            val responseBytes = when (config.protocol) {
                DnsProtocol.DOH -> resolveDoH(requestBytes, config)
                DnsProtocol.DOT -> resolveTcp(requestBytes, config, true)
                DnsProtocol.UDP -> {
                    if (config.proxyMode == DnsProxyMode.CUSTOM) {
                        // Fallback to TCP if custom SOCKS proxy is used, because UDP over SOCKS5 is complex
                        resolveTcp(requestBytes, config, false)
                    } else {
                        resolveUdp(requestBytes, config)
                    }
                }
            }

            if (responseBytes != null && responseBytes.isNotEmpty() && isRunning) {
                val responsePacket = DatagramPacket(responseBytes, responseBytes.size, clientAddr, clientPort)
                serverSocket?.send(responsePacket)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving DNS request", e)
        }
    }

    private fun getActiveNetwork(): Network? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.activeNetwork
    }

    private fun getProxy(config: DnsConfig): Proxy? {
        return if (config.proxyMode == DnsProxyMode.CUSTOM) {
            Proxy(Proxy.Type.SOCKS, InetSocketAddress(config.customProxyHost, config.customProxyPort))
        } else {
            null
        }
    }

    private fun resolveDoH(requestBytes: ByteArray, config: DnsConfig): ByteArray? {
        val url = URL(config.dohUrl)
        val proxy = getProxy(config)
        val network = getActiveNetwork()

        val rawConn = if (proxy != null) {
            url.openConnection(proxy)
        } else if (config.proxyMode == DnsProxyMode.DIRECT && network != null) {
            network.openConnection(url)
        } else {
            url.openConnection()
        }
        val conn = rawConn as? HttpURLConnection ?: return null

        return try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/dns-message")
            conn.setRequestProperty("Accept", "application/dns-message")
            conn.doOutput = true
            conn.connectTimeout = 5000
            conn.readTimeout = 5000

            conn.outputStream.use { it.write(requestBytes) }

            if (conn.responseCode == 200) {
                conn.inputStream.use { it.readBytes() }
            } else {
                Log.w(TAG, "DoH request failed with code: ${conn.responseCode}")
                conn.errorStream?.close()
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "DoH resolution error", e)
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun resolveTcp(requestBytes: ByteArray, config: DnsConfig, useTls: Boolean): ByteArray? {
        val proxy = getProxy(config)
        val network = getActiveNetwork()

        var socket = if (proxy != null) {
            Socket(proxy)
        } else if (config.proxyMode == DnsProxyMode.DIRECT && network != null) {
            network.socketFactory.createSocket()
        } else {
            Socket()
        }

        socket.connect(InetSocketAddress(config.host, config.port), 5000)
        socket.soTimeout = 5000

        if (useTls) {
            val sslSocket = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(socket, config.host, config.port, true) as SSLSocket
            sslSocket.startHandshake()
            socket = sslSocket
        }

        socket.use { s ->
            val out = s.getOutputStream()
            val lenBytes = byteArrayOf((requestBytes.size shr 8).toByte(), requestBytes.size.toByte())
            out.write(lenBytes)
            out.write(requestBytes)
            out.flush()

            val input = s.getInputStream()
            val resLenBytes = ByteArray(2)
            var read = 0
            while (read < 2) {
                val r = input.read(resLenBytes, read, 2 - read)
                if (r == -1) return null
                read += r
            }

            val resLen = ((resLenBytes[0].toInt() and 0xFF) shl 8) or (resLenBytes[1].toInt() and 0xFF)
            val resBytes = ByteArray(resLen)
            read = 0
            while (read < resLen) {
                val r = input.read(resBytes, read, resLen - read)
                if (r == -1) return null
                read += r
            }

            return resBytes
        }
    }

    private fun resolveUdp(requestBytes: ByteArray, config: DnsConfig): ByteArray? {
        val network = getActiveNetwork()
        val socket = if (config.proxyMode == DnsProxyMode.DIRECT && network != null) {
            val s = DatagramSocket()
            network.bindSocket(s)
            s
        } else {
            DatagramSocket()
        }

        socket.use { s ->
            s.soTimeout = 5000
            val outPacket = DatagramPacket(requestBytes, requestBytes.size, InetAddress.getByName(config.host), config.port)
            s.send(outPacket)

            val buffer = ByteArray(4096)
            val inPacket = DatagramPacket(buffer, buffer.size)
            s.receive(inPacket)

            return inPacket.data.copyOf(inPacket.length)
        }
    }
}

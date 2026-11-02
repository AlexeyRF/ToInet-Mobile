package ru.toinet.android.svpn

import android.util.Log
import com.jcraft.jsch.ChannelDirectTCPIP
import com.jcraft.jsch.Session
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * High-performance local SOCKS5 proxy server that forwards incoming TCP connections
 * through JSch ChannelDirectTCPIP (SSH dynamic port forwarding) with robust bidirectional streaming.
 */
class SocksProxyServer(
    private val session: Session,
    private val bindHost: String = "127.0.0.1",
    private val port: Int = 1770
) {
    companion object {
        private const val TAG = "SvpnSocksProxy"
    }

    private var serverSocket: ServerSocket? = null
    @Volatile
    private var isRunning = false
    private var executor: ExecutorService? = null

    val isListening: Boolean
        get() = isRunning && serverSocket != null && !(serverSocket?.isClosed ?: true)

    @Synchronized
    fun start() {
        if (isRunning) return
        isRunning = true
        executor = Executors.newCachedThreadPool()

        val bindAddr = try {
            InetAddress.getByName(bindHost)
        } catch (_: Exception) {
            InetAddress.getByName("127.0.0.1")
        }

        serverSocket = ServerSocket(port, 100, bindAddr).apply {
            reuseAddress = true
        }
        Log.i(TAG, "SVPN SOCKS5 Proxy Server listening on $bindHost:$port")

        Thread({
            while (isRunning && serverSocket != null && !serverSocket!!.isClosed) {
                try {
                    val clientSocket = serverSocket!!.accept()
                    clientSocket.tcpNoDelay = true
                    executor?.execute {
                        handleClient(clientSocket)
                    }
                } catch (e: IOException) {
                    if (!isRunning) break
                    Log.w(TAG, "Error accepting client: ${e.message}")
                }
            }
        }, "SvpnSocksAcceptThread").start()
    }

    @Synchronized
    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (_: IOException) {}
        serverSocket = null

        executor?.shutdownNow()
        executor = null
    }

    private fun handleClient(socket: Socket) {
        var channel: ChannelDirectTCPIP? = null
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = 30000
            val `in` = socket.getInputStream()
            val out = socket.getOutputStream()

            // 1. SOCKS5 Greeting Handshake
            val version = `in`.read()
            if (version != 5) {
                socket.close()
                return
            }

            val nMethods = `in`.read()
            if (nMethods <= 0) {
                socket.close()
                return
            }

            val methods = ByteArray(nMethods)
            readFully(`in`, methods)

            // Respond: version 5, method 0 (NO AUTH REQUIRED)
            out.write(byteArrayOf(0x05, 0x00))
            out.flush()

            // 2. SOCKS5 Request
            val ver = `in`.read()
            val cmd = `in`.read()
            val rsv = `in`.read()
            val atyp = `in`.read()

            if (ver != 5 || cmd != 1) { // Only CMD=1 (CONNECT) supported
                out.write(byteArrayOf(0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0)) // Command not supported
                out.flush()
                socket.close()
                return
            }

            val targetHost: String = when (atyp) {
                1 -> { // IPv4
                    val ipBytes = ByteArray(4)
                    readFully(`in`, ipBytes)
                    InetAddress.getByAddress(ipBytes).hostAddress ?: run {
                        socket.close()
                        return
                    }
                }
                3 -> { // Domain name
                    val domainLen = `in`.read()
                    if (domainLen <= 0) {
                        socket.close()
                        return
                    }
                    val domainBytes = ByteArray(domainLen)
                    readFully(`in`, domainBytes)
                    String(domainBytes, StandardCharsets.UTF_8)
                }
                4 -> { // IPv6
                    val ip6Bytes = ByteArray(16)
                    readFully(`in`, ip6Bytes)
                    InetAddress.getByAddress(ip6Bytes).hostAddress ?: run {
                        socket.close()
                        return
                    }
                }
                else -> {
                    out.write(byteArrayOf(0x05, 0x08, 0x00, 0x01, 0, 0, 0, 0, 0, 0)) // Address type not supported
                    out.flush()
                    socket.close()
                    return
                }
            }

            val portBytes = ByteArray(2)
            readFully(`in`, portBytes)
            val targetPort = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)

            if (!session.isConnected) {
                Log.w(TAG, "SSH session not connected, dropping request to $targetHost:$targetPort")
                out.write(byteArrayOf(0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                out.flush()
                socket.close()
                return
            }

            // 3. Open SSH Direct TCPIP Channel
            channel = session.openChannel("direct-tcpip") as ChannelDirectTCPIP
            channel.setHost(targetHost)
            channel.setPort(targetPort)
            channel.setInputStream(`in`)
            channel.connect(10000)

            // 4. Send SOCKS5 Success Response before routing channel output
            out.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0x7F, 0x00, 0x00, 0x01, 0x00, 0x00))
            out.flush()

            channel.setOutputStream(out)

            // 5. Keep connection alive while channel is running
            while (isRunning && session.isConnected && !channel.isClosed && !socket.isClosed) {
                try {
                    Thread.sleep(500)
                } catch (_: InterruptedException) {
                    break
                }
            }

        } catch (_: Exception) {
            // Normal on close
        } finally {
            try {
                channel?.disconnect()
            } catch (_: Exception) {}
            try {
                socket.close()
            } catch (_: Exception) {}
        }
    }

    @Throws(IOException::class)
    private fun readFully(`in`: InputStream, buffer: ByteArray) {
        var bytesRead = 0
        while (bytesRead < buffer.size) {
            val read = `in`.read(buffer, bytesRead, buffer.size - bytesRead)
            if (read == -1) {
                throw IOException("Unexpected EOF during SOCKS5 negotiation")
            }
            bytesRead += read
        }
    }
}

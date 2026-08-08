package ru.toinet.android.operaproxy

import android.util.Log
import kotlinx.coroutines.*
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

class SocksToHttpProxy(
    private val listenPort: Int,
    private val httpProxyHost: String,
    private val httpProxyPort: Int
) {
    companion object {
        private const val TAG = "SocksToHttpProxy"
    }

    private var serverSocket: ServerSocket? = null
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (job != null) return
        job = scope.launch(Dispatchers.IO) {
            try {
                serverSocket = ServerSocket(listenPort, 128, InetAddress.getByName("127.0.0.1"))
                Log.i(TAG, "SocksToHttpProxy listening on 127.0.0.1:$listenPort bridging to $httpProxyHost:$httpProxyPort")

                while (isActive) {
                    val client = serverSocket!!.accept()
                    launch { handleConnection(client) }
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    Log.e(TAG, "SocksToHttpProxy error", e)
                }
            } finally {
                stop()
            }
        }
    }

    fun stop() {
        try {
            serverSocket?.close()
        } catch (e: Exception) {}
        job?.cancel()
        job = null
        Log.i(TAG, "SocksToHttpProxy stopped.")
    }

    private suspend fun handleConnection(clientSock: Socket) = withContext(Dispatchers.IO) {
        try {
            clientSock.soTimeout = 30000
            val clientIn = clientSock.getInputStream()
            val clientOut = clientSock.getOutputStream()

            // 1. SOCKS5 Greeting
            val header = ByteArray(2)
            if (readFully(clientIn, header)) return@withContext
            if (header[0] != 5.toByte()) return@withContext
            val nmethods = header[1].toInt()
            val methods = ByteArray(nmethods)
            if (readFully(clientIn, methods)) return@withContext

            clientOut.write(byteArrayOf(5, 0))
            clientOut.flush()

            // 2. SOCKS5 Request
            val req = ByteArray(4)
            if (readFully(clientIn, req)) return@withContext
            if (req[0] != 5.toByte() || req[1] != 1.toByte()) {
                clientOut.write(byteArrayOf(5, 7, 0, 1, 0, 0, 0, 0, 0, 0))
                return@withContext
            }
            val atyp = req[3].toInt()
            val hostStr: String
            when (atyp) {
                1 -> {
                    val addrBytes = ByteArray(4)
                    if (readFully(clientIn, addrBytes)) return@withContext
                    hostStr = InetAddress.getByAddress(addrBytes).hostAddress ?: ""
                }
                3 -> {
                    val dlen = clientIn.read()
                    if (dlen <= 0) return@withContext
                    val domBytes = ByteArray(dlen)
                    if (readFully(clientIn, domBytes)) return@withContext
                    hostStr = String(domBytes)
                }
                4 -> {
                    val addrBytes = ByteArray(16)
                    if (readFully(clientIn, addrBytes)) return@withContext
                    hostStr = InetAddress.getByAddress(addrBytes).hostAddress ?: ""
                }
                else -> {
                    clientOut.write(byteArrayOf(5, 8, 0, 1, 0, 0, 0, 0, 0, 0))
                    return@withContext
                }
            }
            val portBytes = ByteArray(2)
            if (readFully(clientIn, portBytes)) return@withContext
            val targetPort = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)

            // 3. Connect to HTTP Proxy
            val remoteSock = Socket()
            try {
                remoteSock.connect(InetSocketAddress(httpProxyHost, httpProxyPort), 10000)
                remoteSock.soTimeout = 0
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect to Opera Proxy at $httpProxyHost:$httpProxyPort: ${e.message}")
                clientOut.write(byteArrayOf(5, 5, 0, 1, 0, 0, 0, 0, 0, 0))
                return@withContext
            }

            // 4. Send CONNECT request to HTTP Proxy
            val out = remoteSock.getOutputStream()
            out.write("CONNECT $hostStr:$targetPort HTTP/1.1\r\nHost: $hostStr:$targetPort\r\n\r\n".toByteArray())
            out.flush()

            // 5. Read HTTP Proxy response
            val input = remoteSock.getInputStream()
            val sb = StringBuilder()
            while (true) {
                val b = input.read()
                if (b == -1) {
                    Log.e(TAG, "Opera Proxy closed connection prematurely. Response so far: $sb")
                    clientOut.write(byteArrayOf(5, 5, 0, 1, 0, 0, 0, 0, 0, 0))
                    return@withContext
                }
                sb.append(b.toChar())
                if (sb.endsWith("\r\n\r\n")) break
            }
            Log.d(TAG, "Opera Proxy response: ${sb.toString().trim()}")
            if (!sb.toString().contains(" 200 ")) {
                Log.e(TAG, "Opera Proxy rejected CONNECT to $hostStr:$targetPort. Response: $sb")
                clientOut.write(byteArrayOf(5, 5, 0, 1, 0, 0, 0, 0, 0, 0))
                return@withContext
            }

            // 6. Send SOCKS5 success to client
            clientOut.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
            clientOut.flush()

            // 7. Pipe
            clientSock.soTimeout = 0
            val p1 = launch { pipe(clientIn, out) }
            val p2 = launch { pipe(input, clientOut) }
            joinAll(p1, p2)

        } catch (e: Exception) {
            // Ignored
        } finally {
            try { clientSock.close() } catch (e: Exception) {}
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var read = 0
        while (read < buf.size) {
            val n = input.read(buf, read, buf.size - read)
            if (n == -1) return true
            read += n
        }
        return false
    }

    private fun pipe(input: InputStream, output: OutputStream) {
        val buf = ByteArray(65536)
        try {
            while (true) {
                val n = input.read(buf)
                if (n == -1) break
                output.write(buf, 0, n)
                output.flush()
            }
        } catch (e: Exception) {
        } finally {
            try { input.close() } catch (e: Exception) {}
            try { output.close() } catch (e: Exception) {}
        }
    }
}

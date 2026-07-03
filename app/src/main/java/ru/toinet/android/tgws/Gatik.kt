package ru.toinet.android.tgws

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.*
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer

object Gatik {
    private const val TAG = "GatikGateway"
    private const val LISTEN_PORT = 1777
    private const val TOR_PORT = 5242
    private const val TGWS_PORT = 1480
    private const val UPLOAD_THRESHOLD = 1024

    private var serverSocket: java.net.ServerSocket? = null
    private var job: Job? = null
    private var globalUploadModeUntil = 0L

    fun start(context: Context) {
        /* Gatik is disabled because it does not work.
        if (job != null) return

        job = CoroutineScope(Dispatchers.IO).launch {
            try {
                serverSocket = java.net.ServerSocket(LISTEN_PORT, 50, java.net.InetAddress.getByName("127.0.0.1"))
                Log.i(TAG, "Gatik started on port $LISTEN_PORT")

                while (isActive) {
                    val clientSocket = serverSocket?.accept() ?: break
                    launch {
                        handleClient(clientSocket, context)
                    }
                }
            } catch (e: Exception) {
                if (isActive) {
                    Log.e(TAG, "Gatik Server error", e)
                }
            }
        }
        */
    }

    fun stop() {
        /* Gatik is disabled because it does not work.
        job?.cancel()
        job = null
        try {
            serverSocket?.close()
        } catch (e: Exception) {}
        serverSocket = null
        Log.i(TAG, "Gatik stopped")
        */
    }

    private fun isMobileData(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private suspend fun handleClient(clientSocket: Socket, context: Context) {
        var upSocket: Socket? = null
        try {
            withContext(Dispatchers.IO) {
                val input = clientSocket.getInputStream()
                val output = clientSocket.getOutputStream()

                // 1. Read SOCKS5 greeting
                val greeting = ByteArray(2)
                readExactly(input, greeting)
                if (greeting[0] != 0x05.toByte()) {
                    return@withContext
                }
                val nmethods = greeting[1].toInt()
                val methods = ByteArray(nmethods)
                readExactly(input, methods)

                // 2. Reply NO AUTH
                output.write(byteArrayOf(0x05, 0x00))
                output.flush()

                // 3. Read CONNECT request
                val reqHeader = ByteArray(4)
                readExactly(input, reqHeader)
                if (reqHeader[1] != 0x01.toByte()) {
                    return@withContext // Only CONNECT supported
                }

                val atyp = reqHeader[3].toInt()
                val dstAddrBytes = when (atyp) {
                    1 -> {
                        val ip = ByteArray(4)
                        readExactly(input, ip)
                        ip
                    }
                    3 -> {
                        val len = input.read()
                        val domain = ByteArray(len)
                        readExactly(input, domain)
                        byteArrayOf(len.toByte()) + domain
                    }
                    4 -> {
                        val ip6 = ByteArray(16)
                        readExactly(input, ip6)
                        ip6
                    }
                    else -> return@withContext
                }

                val portBytes = ByteArray(2)
                readExactly(input, portBytes)

                // 4. Reply SUCCESS to trick client into sending first payload
                val reply = byteArrayOf(0x05, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
                output.write(reply)
                output.flush()

                // 5. Read first chunk
                val firstChunk = ByteArray(16384)
                var bytesRead = 0
                val startTime = System.currentTimeMillis()
                
                // Read until we reach threshold or timeout (500ms)
                clientSocket.soTimeout = 500
                try {
                    while (bytesRead < UPLOAD_THRESHOLD) {
                        val r = input.read(firstChunk, bytesRead, firstChunk.size - bytesRead)
                        if (r <= 0) break
                        bytesRead += r
                        if (System.currentTimeMillis() - startTime > 500) break
                    }
                } catch (e: java.net.SocketTimeoutException) {
                    // Timeout is fine
                }
                clientSocket.soTimeout = 0 // Reset timeout

                if (bytesRead <= 0) {
                    return@withContext
                }

                val firstChunkData = firstChunk.copyOf(bytesRead)

                // 6. Routing magic
                var isUpload = bytesRead > UPLOAD_THRESHOLD
                val now = System.currentTimeMillis()
                if (now < globalUploadModeUntil) {
                    isUpload = true
                }
                
                // Override for mobile data -> Always TOR
                val forceTor = isMobileData(context)
                if (forceTor) {
                    isUpload = false
                }

                val upstreamPort = if (isUpload) TGWS_PORT else TOR_PORT
                
                // 7. Connect to Upstream SOCKS5
                upSocket = Socket()
                upSocket.connect(InetSocketAddress("127.0.0.1", upstreamPort), 5000)
                val upInput = upSocket.getInputStream()
                val upOutput = upSocket.getOutputStream()

                // SOCKS5 greeting to upstream
                upOutput.write(byteArrayOf(0x05, 0x01, 0x00))
                upOutput.flush()
                val upGreeting = ByteArray(2)
                readExactly(upInput, upGreeting)
                
                // CONNECT to upstream
                val upReq = ByteBuffer.allocate(4 + dstAddrBytes.size + 2)
                upReq.put(0x05).put(0x01).put(0x00).put(atyp.toByte())
                upReq.put(dstAddrBytes)
                upReq.put(portBytes)
                upOutput.write(upReq.array())
                upOutput.flush()

                val upConnResp = ByteArray(4)
                readExactly(upInput, upConnResp)
                val bndAtyp = upConnResp[3].toInt()
                when (bndAtyp) {
                    1 -> readExactly(upInput, ByteArray(6))
                    3 -> readExactly(upInput, ByteArray(upInput.read() + 2))
                    4 -> readExactly(upInput, ByteArray(18))
                }

                // 8. Send first chunk
                upOutput.write(firstChunkData)
                upOutput.flush()

                // 9. Start bidirectional forwarding
                val t1 = async { forward(input, upOutput, isUpload, "up", bytesRead) }
                val t2 = async { forward(upInput, output, isUpload, "down", 0) }

                // Wait for any to finish
                try {
                    awaitAll(t1, t2)
                } catch (e: Exception) {
                    t1.cancel()
                    t2.cancel()
                }
            }
        } catch (e: Exception) {
            // Ignore socket closed
        } finally {
            try { clientSocket.close() } catch (e: Exception) {}
            try { upSocket?.close() } catch (e: Exception) {}
        }
    }

    private fun readExactly(input: InputStream, buffer: ByteArray) {
        var bytesRead = 0
        while (bytesRead < buffer.size) {
            val r = input.read(buffer, bytesRead, buffer.size - bytesRead)
            if (r < 0) throw java.io.EOFException()
            bytesRead += r
        }
    }

    private suspend fun forward(input: InputStream, output: OutputStream, isUpload: Boolean, direction: String, initialBytes: Int) {
        withContext(Dispatchers.IO) {
            var bytesTransferred = initialBytes
            val buffer = ByteArray(16384)
            try {
                while (isActive) {
                    val r = input.read(buffer)
                    if (r <= 0) break
                    output.write(buffer, 0, r)
                    output.flush()
                    bytesTransferred += r

                    if (direction == "up") {
                        if (!isUpload && bytesTransferred > 65536) {
                            // Heavy upload detected on TOR line! Break connection to force client to retry (and switch to TGWS)
                            globalUploadModeUntil = System.currentTimeMillis() + 15000
                            break
                        }
                        if (isUpload && bytesTransferred > 65536) {
                            globalUploadModeUntil = maxOf(globalUploadModeUntil, System.currentTimeMillis() + 15000)
                        }
                    }
                }
            } catch (e: Exception) {}
            finally {
                try { output.close() } catch (e: Exception) {}
                try { input.close() } catch (e: Exception) {}
            }
        }
    }
}

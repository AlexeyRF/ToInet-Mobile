package ru.toinet.android.tgws

import android.util.Log
import kotlinx.coroutines.*
import java.io.*
import java.net.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.SecureRandom

class TgwsCore(
    private val host: String,
    private val port: Int,
    private val dcMappings: Map<Int, String>,
    private val useByeDpi: Boolean = false,
    private val onLog: (String) -> Unit
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverSocket: ServerSocket? = null
    private val TAG = "TgwsCore"

    private val TG_RANGES = listOf(
        Pair(ipToLong("185.76.151.0"), ipToLong("185.76.151.255")),
        Pair(ipToLong("149.154.160.0"), ipToLong("149.154.175.255")),
        Pair(ipToLong("91.105.192.0"), ipToLong("91.105.193.255")),
        Pair(ipToLong("91.108.0.0"), ipToLong("91.108.255.255"))
    )

    private val IP_TO_DC = mapOf(
        "149.154.175.50" to 1, "149.154.175.51" to 1, "149.154.175.54" to 1,
        "149.154.167.41" to 2, "149.154.167.50" to 2, "149.154.167.51" to 2, "149.154.167.220" to 2,
        "149.154.175.100" to 3, "149.154.175.101" to 3,
        "149.154.167.91" to 4, "149.154.167.92" to 4,
        "91.108.56.100" to 5, "91.108.56.126" to 5, "91.108.56.101" to 5, "91.108.56.116" to 5,
        "91.105.192.100" to 203
    )

    fun start() {
        scope.launch {
            try {
                serverSocket = ServerSocket(port, 50, InetAddress.getByName(host))
                log("Server started on $host:$port")
                while (isActive) {
                    val client = try {
                        serverSocket?.accept()
                    } catch (e: Exception) {
                        null
                    } ?: break
                    launch { handleClient(client) }
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    log("Server error: ${e.message}")
                }
            }
        }
    }

    fun stop() {
        scope.cancel()
        serverSocket?.close()
        log("Server stopped")
    }

    private fun log(msg: String) {
        Log.i(TAG, msg)
        onLog(msg)
    }

    private suspend fun handleClient(client: Socket) = withContext(Dispatchers.IO) {
        val label = "${client.inetAddress.hostAddress}:${client.port}"
        try {
            val input = client.getInputStream()
            val output = client.getOutputStream()

            // SOCKS5 greeting
            val ver = input.read()
            if (ver != 5) {
                client.close()
                return@withContext
            }
            val nMethods = input.read()
            val methods = ByteArray(nMethods)
            input.read(methods)
            output.write(byteArrayOf(0x05, 0x00))

            // SOCKS5 request
            val req = ByteArray(4)
            input.read(req)
            val cmd = req[1].toInt()
            val atyp = req[3].toInt()

            val dstHost = when (atyp) {
                1 -> { // IPv4
                    val addr = ByteArray(4)
                    input.read(addr)
                    InetAddress.getByAddress(addr).hostAddress
                }
                3 -> { // Domain
                    val len = input.read()
                    val addr = ByteArray(len)
                    input.read(addr)
                    String(addr)
                }
                4 -> { // IPv6
                    val addr = ByteArray(16)
                    input.read(addr)
                    InetAddress.getByAddress(addr).hostAddress
                }
                else -> {
                    output.write(socks5Reply(0x08))
                    client.close()
                    return@withContext
                }
            }
            val dstPort = ByteBuffer.wrap(byteArrayOf(0, 0, input.read().toByte(), input.read().toByte())).apply { order(ByteOrder.BIG_ENDIAN) }.getInt(0)

            if (!isTelegramIp(dstHost)) {
                log("[$label] Passthrough -> $dstHost:$dstPort")
                handlePassthrough(client, dstHost, dstPort)
                return@withContext
            }

            // Telegram DC
            output.write(socks5Reply(0x00))
            
            val init = ByteArray(64)
            val read = input.read(init)
            if (read < 64) {
                client.close()
                return@withContext
            }

            if (isHttpTransport(init)) {
                log("[$label] HTTP rejected")
                client.close()
                return@withContext
            }

            val (dc, isMedia) = dcFromInit(init) ?: Pair(IP_TO_DC[dstHost], false)
            
            if (dc == null || !dcMappings.containsKey(dc)) {
                log("[$label] Unknown DC$dc -> fallback")
                handleTcpFallback(client, dstHost, dstPort, init)
                return@withContext
            }

            val targetIp = dcMappings[dc]!!
            val domains = wsDomains(dc, isMedia)
            
            var wsSocket: Socket? = null
            var wsDomain: String? = null
            
            for (domain in domains) {
                try {
                    wsSocket = connectWebSocket(targetIp, domain)
                    wsDomain = domain
                    break
                } catch (e: Exception) {
                    log("[$label] WS connect failed to $domain: ${e.message}")
                }
            }

            if (wsSocket == null) {
                log("[$label] All WS failed -> fallback")
                handleTcpFallback(client, dstHost, dstPort, init)
                return@withContext
            }

            log("[$label] DC$dc -> $wsDomain via $targetIp")
            bridgeWs(client, wsSocket, init)

        } catch (e: Exception) {
            if (e !is CancellationException) {
                log("[$label] Error: ${e.message}")
            }
            try { client.close() } catch (ex: Exception) {}
        }
    }

    private fun socks5Reply(status: Int): ByteArray {
        return byteArrayOf(0x05, status.toByte(), 0x00, 0x01, 0, 0, 0, 0, 0, 0)
    }

    private fun isTelegramIp(ip: String): Boolean {
        return try {
            val n = ipToLong(ip)
            TG_RANGES.any { (lo, hi) -> n in lo..hi }
        } catch (e: Exception) {
            false
        }
    }

    private fun ipToLong(ip: String): Long {
        val parts = ip.split(".").map { it.toLong() }
        return (parts[0] shl 24) or (parts[1] shl 16) or (parts[2] shl 8) or parts[3]
    }

    private fun isHttpTransport(data: ByteArray): Boolean {
        val s = String(data.take(8).toByteArray())
        return s.startsWith("POST ") || s.startsWith("GET ") || s.startsWith("HEAD ") || s.startsWith("OPTIONS")
    }

    private fun dcFromInit(data: ByteArray): Pair<Int, Boolean>? {
        try {
            val key = data.sliceArray(8 until 40)
            val iv = data.sliceArray(40 until 56)
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            val keystream = cipher.doFinal(ByteArray(64))
            val plain = ByteArray(8)
            for (i in 0 until 8) {
                plain[i] = (data[56 + i].toInt() xor keystream[56 + i].toInt()).toByte()
            }
            val proto = ByteBuffer.wrap(plain.sliceArray(0..3)).order(ByteOrder.LITTLE_ENDIAN).int
            val dcRaw = ByteBuffer.wrap(plain.sliceArray(4..5)).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
            
            if (proto.toUInt() == 0xEFEFEFEFu.toUInt() || proto.toUInt() == 0xEEEEEEEEu.toUInt() || proto.toUInt() == 0xDDDDDDDDu.toUInt()) {
                val dc = Math.abs(dcRaw)
                if (dc in 1..1000) return Pair(dc, dcRaw < 0)
            }
        } catch (e: Exception) {
            Log.d(TAG, "DC extraction failed: ${e.message}")
        }
        return null
    }

    private fun wsDomains(dc: Int, isMedia: Boolean): List<String> {
        val base = if (dc > 5) "telegram.org" else "web.telegram.org"
        return if (isMedia) listOf("kws$dc-1.$base", "kws$dc.$base")
        else listOf("kws$dc.$base", "kws$dc-1.$base")
    }

    private fun createSocket(host: String, port: Int): Socket {
        return if (useByeDpi) {
            val proxy = java.net.Proxy(java.net.Proxy.Type.SOCKS, java.net.InetSocketAddress("127.0.0.1", 1080))
            val socket = Socket(proxy)
            socket.connect(java.net.InetSocketAddress(host, port), 10000)
            socket
        } else {
            Socket(host, port)
        }
    }

    private suspend fun handlePassthrough(client: Socket, host: String, port: Int) = withContext(Dispatchers.IO) {
        try {
            val remote = createSocket(host, port)
            client.getOutputStream().write(socks5Reply(0x00))
            bridgeTcp(client, remote)
        } catch (e: Exception) {
            try { client.getOutputStream().write(socks5Reply(0x05)) } catch (ex: Exception) {}
            client.close()
        }
    }

    private suspend fun handleTcpFallback(client: Socket, host: String, port: Int, init: ByteArray) = withContext(Dispatchers.IO) {
        try {
            val remote = createSocket(host, port)
            remote.getOutputStream().write(init)
            bridgeTcp(client, remote)
        } catch (e: Exception) {
            client.close()
        }
    }

    private suspend fun bridgeTcp(s1: Socket, s2: Socket) = coroutineScope {
        val job1 = launch { pipe(s1.getInputStream(), s2.getOutputStream()) }
        val job2 = launch { pipe(s2.getInputStream(), s1.getOutputStream()) }
        joinAll(job1, job2)
        try { s1.close() } catch (e: Exception) {}
        try { s2.close() } catch (e: Exception) {}
    }

    private suspend fun pipe(input: InputStream, output: OutputStream) = withContext(Dispatchers.IO) {
        val buf = ByteArray(65536)
        try {
            while (true) {
                val n = input.read(buf)
                if (n == -1) break
                output.write(buf, 0, n)
                output.flush()
            }
        } catch (e: Exception) {}
    }

    private fun connectWebSocket(ip: String, domain: String): Socket {
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
        }), SecureRandom())

        val socket = createSocket(ip, 443)
        val sslSocket = sslContext.socketFactory.createSocket(socket, domain, 443, true) as javax.net.ssl.SSLSocket
        sslSocket.startHandshake()

        val wsKey = Base64.getEncoder().encodeToString(SecureRandom().generateSeed(16))
        val req = "GET /apiws HTTP/1.1\r\n" +
                "Host: $domain\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Key: $wsKey\r\n" +
                "Sec-WebSocket-Version: 13\r\n" +
                "Sec-WebSocket-Protocol: binary\r\n" +
                "Origin: https://web.telegram.org\r\n" +
                "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36\r\n" +
                "\r\n"
        
        val out = sslSocket.outputStream
        out.write(req.toByteArray())
        out.flush()

        val reader = BufferedReader(InputStreamReader(sslSocket.inputStream))
        val firstLine = reader.readLine()
        if (firstLine == null || !firstLine.contains("101")) {
            sslSocket.close()
            throw IOException("Handshake failed: $firstLine")
        }
        while (true) {
            val line = reader.readLine()
            if (line.isNullOrEmpty()) break
        }
        return sslSocket
    }

    private suspend fun bridgeWs(client: Socket, wsSocket: Socket, init: ByteArray) = coroutineScope {
        val clientIn = client.getInputStream()
        val clientOut = client.getOutputStream()
        val wsIn = wsSocket.getInputStream()
        val wsOut = wsSocket.getOutputStream()

        // Send init
        sendWsFrame(wsOut, init)

        val job1 = launch {
            val buf = ByteArray(65536)
            try {
                while (true) {
                    val n = clientIn.read(buf)
                    if (n == -1) break
                    sendWsFrame(wsOut, buf.sliceArray(0 until n))
                }
            } catch (e: Exception) {}
        }

        val job2 = launch {
            try {
                while (true) {
                    val data = readWsFrame(wsIn) ?: break
                    clientOut.write(data)
                    clientOut.flush()
                }
            } catch (e: Exception) {}
        }

        joinAll(job1, job2)
        try { client.close() } catch (e: Exception) {}
        try { wsSocket.close() } catch (e: Exception) {}
    }

    private fun sendWsFrame(out: OutputStream, data: ByteArray) {
        val header = mutableListOf<Byte>()
        header.add((0x80 or 0x02).toByte()) // FIN + Binary
        val len = data.size
        val maskKey = ByteArray(4).apply { SecureRandom().nextBytes(this) }
        
        if (len < 126) {
            header.add((0x80 or len).toByte())
        } else if (len < 65536) {
            header.add((0x80 or 126).toByte())
            header.add(((len shr 8) and 0xFF).toByte())
            header.add((len and 0xFF).toByte())
        } else {
            header.add((0x80 or 127).toByte())
            for (i in 7 downTo 0) {
                header.add(((len.toLong() shr (i * 8)) and 0xFF).toByte())
            }
        }
        header.addAll(maskKey.toList())
        
        val masked = ByteArray(data.size)
        for (i in data.indices) {
            masked[i] = (data[i].toInt() xor maskKey[i % 4].toInt()).toByte()
        }
        
        out.write(header.toByteArray())
        out.write(masked)
        out.flush()
    }

    private fun readWsFrame(input: InputStream): ByteArray? {
        val b1 = input.read()
        if (b1 == -1) return null
        val opcode = b1 and 0x0F
        val b2 = input.read()
        if (b2 == -1) return null
        val isMasked = (b2 and 0x80) != 0
        var len = (b2 and 0x7F).toLong()

        if (len == 126L) {
            len = ((input.read() shl 8) or input.read()).toLong()
        } else if (len == 127L) {
            len = 0
            for (i in 0 until 8) {
                len = (len shl 8) or input.read().toLong()
            }
        }

        val maskKey = if (isMasked) {
            val key = ByteArray(4)
            input.read(key)
            key
        } else null

        val payload = ByteArray(len.toInt())
        var read = 0
        while (read < len) {
            val n = input.read(payload, read, (len - read).toInt())
            if (n == -1) break
            read += n
        }

        if (maskKey != null) {
            for (i in payload.indices) {
                payload[i] = (payload[i].toInt() xor maskKey[i % 4].toInt()).toByte()
            }
        }

        return when (opcode) {
            0x01, 0x02 -> payload
            0x08 -> null // Close
            0x09 -> { // Ping
                // Should send Pong but keeping it simple
                readWsFrame(input)
            }
            else -> readWsFrame(input)
        }
    }
}
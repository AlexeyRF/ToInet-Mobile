package ru.toinet.android.tgws

import android.util.Base64
import kotlinx.coroutines.*
import ru.toinet.android.util.Prefs
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.io.IOException

class TgwsCore(
    val host: String,
    val port: Int,
    val dcMappings: Map<Int, String>,
    val secret: String,
    val fakeTlsDomain: String,
    val useByeDpi: Boolean,
    val disableWebSockets: Boolean,
    val cfWorkerDomains: List<String>,
    val cfProxyDomains: List<String>,
    val onLog: ((String) -> Unit)? = null
) {
    private var serverSocket: ServerSocket? = null
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val secretBytes: ByteArray by lazy {
        try {
            val hex = if (secret.startsWith("dd") || secret.startsWith("ee")) {
                secret.substring(2).take(32)
            } else {
                secret.take(32)
            }
            if (hex.length == 32) hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray() else ByteArray(0)
        } catch (e: Exception) { ByteArray(0) }
    }

    fun start() {
        if (job != null) return
        job = scope.launch {
            try {
                serverSocket = ServerSocket(port, 128, java.net.InetAddress.getByName(host))
                log("Listening on $host:$port")
                while (isActive) {
                    val client = serverSocket!!.accept()
                    launch { handleClient(client) }
                }
            } catch (e: Exception) {
                if (e !is CancellationException) log("Server error: ${e.message}")
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        try { serverSocket?.close() } catch (e: Exception) {}
        log("Stopped")
    }

    private fun log(msg: String) {
        onLog?.invoke(msg)
    }

    private suspend fun handleClient(client: Socket) = withContext(Dispatchers.IO) {
        try {
            client.soTimeout = 10000
            val input = client.getInputStream()
            val output = client.getOutputStream()

            val firstByte = ByteArray(1)
            if (input.read(firstByte) < 1) return@withContext

            if (firstByte[0] == 0x05.toByte()) {
                handleSocks5(client, input, output)
                return@withContext
            }

            // MTProto Direct / FakeTLS
            val payload = readInitialPayload(firstByte[0], input) ?: return@withContext
            handleMtproto(client, input, output, payload)
        } catch (e: Exception) {
            log("Client error: ${e.message}")
        } finally {
            try { client.close() } catch (e: Exception) {}
        }
    }

    private suspend fun handleSocks5(client: Socket, input: InputStream, output: OutputStream) {
        // Handshake
        val nmethods = input.read()
        if (nmethods <= 0) return
        val methods = ByteArray(nmethods)
        if (readFully(input, methods)) return
        output.write(byteArrayOf(5, 0))
        output.flush()

        // Request
        val req = ByteArray(4)
        if (readFully(input, req)) return
        if (req[1] != 1.toByte()) {
            output.write(byteArrayOf(5, 7, 0, 1, 0, 0, 0, 0, 0, 0))
            return
        }

        val targetIp: String
        when (req[3].toInt()) {
            1 -> {
                val ipBytes = ByteArray(4)
                if (readFully(input, ipBytes)) return
                targetIp = java.net.InetAddress.getByAddress(ipBytes).hostAddress ?: ""
            }
            3 -> {
                val len = input.read()
                if (len <= 0) return
                val domBytes = ByteArray(len)
                if (readFully(input, domBytes)) return
                targetIp = String(domBytes)
            }
            4 -> {
                val ipBytes = ByteArray(16)
                if (readFully(input, ipBytes)) return
                targetIp = java.net.InetAddress.getByAddress(ipBytes).hostAddress ?: ""
            }
            else -> {
                output.write(byteArrayOf(5, 8, 0, 1, 0, 0, 0, 0, 0, 0))
                return
            }
        }
        val portBytes = ByteArray(2)
        if (readFully(input, portBytes)) return
        val targetPort = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)

        output.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
        output.flush()

        // Extract DC ID from IP
        val dcId = dcMappings.entries.find { it.value == targetIp }?.key
        
        connectAndBridge(client, input, output, dcId, false, targetIp, targetPort, null)
    }

    private suspend fun readInitialPayload(firstByte: Byte, input: InputStream): ByteArray? {
        if (firstByte == 0x16.toByte()) {
            // FakeTLS
            val hdrRest = ByteArray(4)
            if (readFully(input, hdrRest)) return null
            val recordLen = ((hdrRest[2].toInt() and 0xFF) shl 8) or (hdrRest[3].toInt() and 0xFF)
            val recordBody = ByteArray(recordLen)
            var read = 0
            while (read < recordLen) {
                val n = input.read(recordBody, read, recordLen - read)
                if (n == -1) return null
                read += n
            }
            return byteArrayOf(firstByte) + hdrRest + recordBody
        } else {
            // Obfs
            val payload = ByteArray(64)
            payload[0] = firstByte
            var read = 1
            while (read < 64) {
                val n = input.read(payload, read, 64 - read)
                if (n == -1) return null
                read += n
            }
            return payload
        }
    }

    private suspend fun handleMtproto(client: Socket, input: InputStream, output: OutputStream, payload: ByteArray) {
        if (secretBytes.isEmpty()) {
            log("No secret configured, dropping direct MTProto")
            return
        }

        var decryptedPayload = payload
        var dcId: Int? = null
        var isMedia = false



        if (payload[0] == 0x16.toByte()) {
            // Verify FakeTLS
            val clientRandom = payload.sliceArray(11 until 43)
            val zeroed = payload.clone()
            for (i in 0 until 32) zeroed[11 + i] = 0
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(secretBytes, "HmacSHA256"))
            val expected = mac.doFinal(zeroed)

            if (!expected.sliceArray(0..27).contentEquals(clientRandom.sliceArray(0..27))) {
                log("FakeTLS verification failed")
                return
            }

            val sessionId = if (payload.size >= 76) payload.sliceArray(44 until 76) else ByteArray(32)
            
            // Build ServerHello
            val sh = buildFakeTlsServerHello(clientRandom, sessionId)
            output.write(sh)
            output.flush()

            // Read inner obfuscated payload
            decryptedPayload = ByteArray(64)
            if (readFully(input, decryptedPayload)) return
        }

        // Decrypt obfs payload
        val decKey = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(secretBytes, "HmacSHA256"))
        }.doFinal(decryptedPayload.sliceArray(8..39) + secretBytes)
        
        val decIv = decryptedPayload.sliceArray(40..55)
        val decryptor = Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(decKey, "AES"), IvParameterSpec(decIv))
        }
        val decrypted = decryptor.update(decryptedPayload)

        val dcIdx = ByteBuffer.wrap(decrypted.sliceArray(60..61)).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
        dcId = Math.abs(dcIdx)
        isMedia = dcIdx < 0

        val targetIp = dcMappings[dcId] ?: "149.154.167.220"
        
        connectAndBridge(client, input, output, dcId, isMedia, targetIp, 443, decryptedPayload)
    }

    private fun buildFakeTlsServerHello(clientRandom: ByteArray, sessionId: ByteArray): ByteArray {
        val sh = byteArrayOf(
            0x16, 0x03, 0x03, 0x00, 0x7a, 0x02, 0x00, 0x00, 0x76, 0x03, 0x03
        ) + ByteArray(32) + byteArrayOf(0x20) + sessionId + byteArrayOf(
            0x13, 0x01, 0x00, 0x00, 0x2e, 0x00, 0x33, 0x00, 0x24, 0x00, 0x1d, 0x00, 0x20
        ) + SecureRandom().generateSeed(32) + byteArrayOf(0x00, 0x2b, 0x00, 0x02, 0x03, 0x04)

        val ccs = byteArrayOf(0x14, 0x03, 0x03, 0x00, 0x01, 0x01)
        val encryptedSize = 2000
        val appRecord = byteArrayOf(0x17, 0x03, 0x03) + 
            ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(encryptedSize.toShort()).array() + 
            SecureRandom().generateSeed(encryptedSize)

        val response = sh + ccs + appRecord
        val mac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(secretBytes, "HmacSHA256"))
        }
        mac.update(clientRandom)
        val serverRandom = mac.doFinal(response)
        
        System.arraycopy(serverRandom, 0, response, 11, 32)
        return response
    }

    private suspend fun connectAndBridge(
        client: Socket, clientIn: InputStream, clientOut: OutputStream,
        dcId: Int?, isMedia: Boolean, targetIp: String, targetPort: Int,
        initialPayload: ByteArray?
    ) {
        val domains = mutableListOf<String>()
        if (dcId != null && !disableWebSockets) {
            val prefix = if (isMedia) "kwsmedia" else "kws"
            cfWorkerDomains.forEach { domains.add("$prefix$dcId.$it") }
            cfProxyDomains.forEach { domains.add("$prefix$dcId.$it") }
            domains.add("$prefix$dcId.web.telegram.org")
        }

        var wsSocket: Socket? = null
        for (domain in domains) {
            try {
                wsSocket = connectWebSocket(domain)
                log("Connected to WS: $domain")
                break
            } catch (e: Exception) {
                log("WS failed for $domain: ${e.message}")
            }
        }

        if (wsSocket != null) {
            bridgeWs(client, clientIn, clientOut, wsSocket, initialPayload)
        } else {
            // TCP Fallback
            try {
                val remote = createSocket(targetIp, targetPort)
                log("Fallback connected to TCP $targetIp:$targetPort")
                if (initialPayload != null) remote.getOutputStream().write(initialPayload)
                val p1 = scope.launch(Dispatchers.IO) { pipe(clientIn, remote.getOutputStream()) }
                val p2 = scope.launch(Dispatchers.IO) { pipe(remote.getInputStream(), clientOut) }
                joinAll(p1, p2)
            } catch (e: Exception) {
                log("TCP Fallback failed: ${e.message}")
            }
        }
    }

    private fun createSocket(host: String, port: Int): Socket {
        return if (useByeDpi) {
            val provider = Prefs.vpnProvider
            val targetPort = when (provider) {
                "byedpi" -> 1080
                "tor" -> 5242
                "tgws" -> 1480
                "rehab" -> 1788
                "turnproxy" -> Prefs.turnProxyLocalPort
                "fakevpn" -> 1790
                "operaproxy" -> Prefs.operaProxyBindAddress.split(":").last().toIntOrNull() ?: 1888
                "custom" -> Prefs.customSocksPort
                else -> 1080
            }
            val targetHost = when (provider) {
                "operaproxy" -> Prefs.operaProxyBindAddress.split(":").first()
                "custom" -> Prefs.customSocksIp
                else -> "127.0.0.1"
            }
            if (targetPort == this.port && targetHost == "127.0.0.1") {
                val socket = Socket()
                socket.connect(InetSocketAddress(host, port), 5000)
                socket.soTimeout = 0
                return socket
            }
            
            if (provider == "operaproxy") {
                val socket = Socket()
                socket.connect(InetSocketAddress(targetHost, targetPort), 5000)
                val out = socket.getOutputStream()
                out.write("CONNECT $host:$port HTTP/1.1\r\nHost: $host:$port\r\n\r\n".toByteArray())
                out.flush()
                val input = socket.getInputStream()
                val sb = StringBuilder()
                while (true) {
                    val b = input.read()
                    if (b == -1) throw IOException("Proxy closed")
                    sb.append(b.toChar())
                    if (sb.endsWith("\r\n\r\n")) break
                }
                if (!sb.toString().contains(" 200 ")) throw IOException("Proxy failed")
                socket.soTimeout = 0
                socket
            } else {
                val proxy = java.net.Proxy(java.net.Proxy.Type.SOCKS, InetSocketAddress(targetHost, targetPort))
                val socket = Socket(proxy)
                socket.connect(InetSocketAddress(host, port), 10000)
                socket.soTimeout = 0
                socket
            }
        } else {
            val socket = Socket()
            socket.connect(InetSocketAddress(host, port), 5000)
            socket.soTimeout = 0
            socket
        }
    }

    private fun connectWebSocket(domain: String): Socket {
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
        }), SecureRandom())

        val socket = createSocket(domain, 443)
        val sslSocket = sslContext.socketFactory.createSocket(socket, domain, 443, true) as javax.net.ssl.SSLSocket
        sslSocket.startHandshake()

        val wsKey = Base64.encodeToString(SecureRandom().generateSeed(16), Base64.NO_WRAP)
        val req = "GET /apiws HTTP/1.1\r\n" +
                "Host: $domain\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Key: $wsKey\r\n" +
                "Sec-WebSocket-Version: 13\r\n" +
                "Sec-WebSocket-Protocol: binary\r\n" +
                "Origin: https://web.telegram.org\r\n" +
                "User-Agent: Mozilla/5.0\r\n\r\n"
        
        val out = sslSocket.outputStream
        out.write(req.toByteArray())
        out.flush()

        val input = sslSocket.inputStream
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) throw IOException("WS closed")
            sb.append(b.toChar())
            if (sb.endsWith("\r\n\r\n")) break
        }
        if (!sb.toString().contains("101 Switching Protocols")) {
            throw IOException("WS handshake failed")
        }
        sslSocket.soTimeout = 0
        return sslSocket
    }

    private suspend fun bridgeWs(client: Socket, clientIn: InputStream, clientOut: OutputStream, wsSocket: Socket, initPayload: ByteArray?) {
        val wsIn = wsSocket.getInputStream()
        val wsOut = wsSocket.getOutputStream()

        if (initPayload != null) sendWsFrame(wsOut, initPayload)

        val p1 = scope.launch(Dispatchers.IO) {
            val buf = ByteArray(65536)
            try {
                while (true) {
                    val n = clientIn.read(buf)
                    if (n == -1) break
                    sendWsFrame(wsOut, buf.sliceArray(0 until n))
                }
            } catch (e: Exception) {}
            finally { try { client.close(); wsSocket.close() } catch (e: Exception) {} }
        }

        val p2 = scope.launch(Dispatchers.IO) {
            try {
                while (true) {
                    val data = readWsFrame(wsIn, wsOut) ?: break
                    clientOut.write(data)
                    clientOut.flush()
                }
            } catch (e: Exception) {}
            finally { try { client.close(); wsSocket.close() } catch (e: Exception) {} }
        }

        joinAll(p1, p2)
    }

    private fun sendWsFrame(out: OutputStream, data: ByteArray, opcode: Int = 0x02) {
        val mask = SecureRandom().generateSeed(4)
        val header = mutableListOf<Byte>()
        header.add((0x80 or opcode).toByte())
        
        if (data.size <= 125) {
            header.add((data.size or 0x80).toByte())
        } else if (data.size <= 65535) {
            header.add((126 or 0x80).toByte())
            header.add((data.size ushr 8).toByte())
            header.add((data.size and 0xFF).toByte())
        } else {
            header.add((127 or 0x80).toByte())
            for (i in 7 downTo 0) {
                header.add(((data.size.toLong() ushr (i * 8)) and 0xFF).toByte())
            }
        }
        
        header.addAll(mask.toList())
        
        val maskedData = ByteArray(data.size)
        for (i in data.indices) {
            maskedData[i] = (data[i].toInt() xor mask[i % 4].toInt()).toByte()
        }
        
        synchronized(out) {
            out.write(header.toByteArray() + maskedData)
            out.flush()
        }
    }

    private fun readWsFrame(input: InputStream, wsOut: OutputStream): ByteArray? {
        while (true) {
            val b0 = input.read()
            if (b0 == -1) return null
            val b1 = input.read()
            if (b1 == -1) return null

            val opcode = b0 and 0x0F
            var len = (b1 and 0x7F).toLong()
            if (len == 126L) {
                val ext = ByteArray(2)
                if (readFully(input, ext)) return null
                len = ((ext[0].toInt() and 0xFF) shl 8 or (ext[1].toInt() and 0xFF)).toLong()
            } else if (len == 127L) {
                val ext = ByteArray(8)
                if (readFully(input, ext)) return null
                len = 0
                for (i in 0..7) {
                    len = (len shl 8) or (ext[i].toLong() and 0xFF)
                }
            }

            val data = ByteArray(len.toInt())
            if (readFully(input, data)) return null

            when (opcode) {
                0x08 -> return null // Close
                0x09 -> {
                    // Ping -> Pong
                    try { sendWsFrame(wsOut, data, 0x0A) } catch (e: Exception) {}
                    continue
                }
                0x0A -> continue // Pong
                0x01, 0x02 -> return data // Text or Binary
                else -> continue
            }
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
        } catch (e: Exception) {}
        finally {
            try { input.close() } catch (e: Exception) {}
            try { output.close() } catch (e: Exception) {}
        }
    }
}

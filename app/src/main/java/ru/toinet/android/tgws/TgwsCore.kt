package ru.toinet.android.tgws

import android.util.Log
import kotlinx.coroutines.*
import java.io.*
import java.net.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class TgwsCore(
    private val host: String,
    private val port: Int,
    private val dcMappings: Map<Int, String>,
    private val secret: String = "",
    private val fakeTlsDomain: String = "",
    private val useByeDpi: Boolean = false,
    private val disableWebSockets: Boolean = false,
    private val cfWorkerDomains: List<String> = emptyList(),
    private val onLog: (String) -> Unit
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverSocket: ServerSocket? = null
    private val TAG = "TgwsCore"

    private var actualFakeTlsDomain = fakeTlsDomain

    init {
        if (secret.startsWith("ee") && secret.length > 34 && fakeTlsDomain.isEmpty()) {
            val domainHex = secret.substring(34)
            try {
                actualFakeTlsDomain = String(domainHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
            } catch (e: Exception) {
            }
        }
    }

    private val secretBytes: ByteArray by lazy {
        if (secret.isNotBlank()) {
            val hex = if (secret.startsWith("dd") || secret.startsWith("ee")) {
                secret.substring(2).take(32)
            } else {
                secret.take(32)
            }
            if (hex.length == 32) {
                hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            } else {
                ByteArray(0)
            }
        } else {
            ByteArray(0)
        }
    }

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

            val firstByteInt = input.read()
            if (firstByteInt == -1) {
                client.close()
                return@withContext
            }
            val firstByte = firstByteInt.toByte()

            if (firstByte == 0x05.toByte()) {
                // SOCKS5 branch
                val nMethods = input.read()
                val methods = ByteArray(nMethods)
                readFully(input, methods)
                output.write(byteArrayOf(0x05, 0x00))

                val req = ByteArray(4)
                readFully(input, req)
                val atyp = req[3].toInt()

                val dstHost = when (atyp) {
                    1 -> {
                        val addr = ByteArray(4)
                        readFully(input, addr)
                        InetAddress.getByAddress(addr).hostAddress
                    }
                    3 -> {
                        val len = input.read()
                        val addr = ByteArray(len)
                        readFully(input, addr)
                        String(addr)
                    }
                    4 -> {
                        val addr = ByteArray(16)
                        readFully(input, addr)
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

                output.write(socks5Reply(0x00))
                
                val payloadFirstByteInt = input.read()
                if (payloadFirstByteInt != -1) {
                    handleMtprotoPayload(payloadFirstByteInt.toByte(), client, input, output, label, dstHost, dstPort)
                } else {
                    client.close()
                }
            } else {
                handleMtprotoPayload(firstByte, client, input, output, label, "", 0)
            }
        } catch (e: Exception) {
            if (e !is CancellationException) {
                log("[$label] Error: ${e.message}")
            }
            try { client.close() } catch (ex: Exception) {}
        }
    }

    private suspend fun handleMtprotoPayload(firstByte: Byte, client: Socket, input: InputStream, output: OutputStream, label: String, dstHost: String, dstPort: Int) = withContext(Dispatchers.IO) {
        val initRest = ByteArray(63)
        if (!readFully(input, initRest)) {
            client.close()
            return@withContext
        }
        val init = byteArrayOf(firstByte) + initRest
        val (dc, isMedia) = dcFromInit(init) ?: Pair(IP_TO_DC[dstHost], false)
        connectAndBridge(client, init, dc, isMedia, dstHost, dstPort, label, input, output, null, false, null)
    }

    private suspend fun connectAndBridge(
        client: Socket, init: ByteArray, dc: Int?, isMedia: Boolean, 
        dstHost: String, dstPort: Int, label: String,
        cltIn: InputStream, cltOut: OutputStream,
        ctx: CryptoCtx?, isFakeTls: Boolean,
        splitter: MsgSplitter? = null
    ) = withContext(Dispatchers.IO) {
        val targetIp = if (dc != null) dcMappings[dc] else null
        if (targetIp == null && dstHost.isEmpty()) {
            log("[$label] Unknown DC$dc and no dstHost -> drop")
            client.close()
            return@withContext
        }

        val finalHost = targetIp ?: dstHost
        val finalPort = if (targetIp != null) 443 else dstPort

        if (disableWebSockets) {
            log("[$label] WebSockets disabled -> TCP fallback to $finalHost:$finalPort")
            handleTcpFallback(client, finalHost, finalPort, init, ctx, cltIn, cltOut, isFakeTls)
            return@withContext
        }

        val domains = if (dc != null) wsDomains(dc, isMedia) else emptyList()
        var wsSocket: Socket? = null
        var wsDomain: String? = null
        
        for (domain in domains) {
            try {
                wsSocket = connectWebSocket(targetIp!!, domain)
                wsDomain = domain
                break
            } catch (e: Exception) {
                log("[$label] WS connect failed to $domain: ${e.message}")
            }
        }

        if (wsSocket == null && cfWorkerDomains.isNotEmpty()) {
            for (cfDomain in cfWorkerDomains) {
                try {
                    val query = "dst=$finalHost&dc=$dc"
                    wsSocket = connectWebSocket(cfDomain, cfDomain, "/apiws?$query")
                    wsDomain = cfDomain
                    log("[$label] WS connected via CF worker $cfDomain")
                    break
                } catch (e: Exception) {
                    log("[$label] CF worker connect failed: ${e.message}")
                }
            }
        }

        if (wsSocket == null) {
            log("[$label] All WS failed -> fallback")
            handleTcpFallback(client, finalHost, finalPort, init, ctx, cltIn, cltOut, isFakeTls)
            return@withContext
        }

        log("[$label] DC$dc -> $wsDomain via $targetIp")
        if (ctx != null) {
            bridgeWsReencrypt(client, cltIn, cltOut, wsSocket, init, ctx, isFakeTls, splitter)
        } else {
            bridgeWs(client, cltIn, cltOut, wsSocket, init, splitter)
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var read = 0
        while (read < buf.size) {
            val n = input.read(buf, read, buf.size - read)
            if (n == -1) return false
            read += n
        }
        return true
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

    data class TryHandshakeResult(val dcId: Int, val isMedia: Boolean, val protoTag: ByteArray, val clientDecPrekeyIv: ByteArray)

    private fun tryHandshake(handshake: ByteArray, secret: ByteArray): TryHandshakeResult? {
        try {
            val decPrekeyAndIv = handshake.sliceArray(8 until 56)
            val decPrekey = decPrekeyAndIv.sliceArray(0 until 32)
            val decIv = decPrekeyAndIv.sliceArray(32 until 48)

            val md = MessageDigest.getInstance("SHA-256")
            md.update(decPrekey)
            md.update(secret)
            val decKey = md.digest()

            val decryptor = Cipher.getInstance("AES/CTR/NoPadding")
            decryptor.init(Cipher.ENCRYPT_MODE, SecretKeySpec(decKey, "AES"), IvParameterSpec(decIv))

            val decrypted = decryptor.update(handshake)
            val protoTag = decrypted.sliceArray(56 until 60)

            val protoInt = ByteBuffer.wrap(protoTag).order(ByteOrder.LITTLE_ENDIAN).int
            if (protoInt != 0xEFEFEFEF.toInt() && protoInt != 0xEEEEEEEE.toInt() && protoInt != 0xDDDDDDDD.toInt()) {
                return null
            }

            val dcIdx = ByteBuffer.wrap(decrypted.sliceArray(60 until 62)).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
            val dcId = Math.abs(dcIdx)
            val isMedia = dcIdx < 0

            return TryHandshakeResult(dcId, isMedia, protoTag, decPrekeyAndIv)
        } catch (e: Exception) {
            return null
        }
    }

    private fun generateRelayInit(protoTag: ByteArray, dcIdx: Int): ByteArray {
        val rnd = ByteArray(64)
        val reservedFirst = byteArrayOf(0xef.toByte(), 0xee.toByte(), 0xdd.toByte())
        while (true) {
            SecureRandom().nextBytes(rnd)
            if (rnd[0] in reservedFirst) continue
            val first4 = ByteBuffer.wrap(rnd, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (first4 == 0xEFEFEFEF.toInt() || first4 == 0xEEEEEEEE.toInt() || first4 == 0xDDDDDDDD.toInt()) continue
            val second4 = ByteBuffer.wrap(rnd, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (second4 == 0) continue
            break
        }

        val encKey = rnd.sliceArray(8 until 40)
        val encIv = rnd.sliceArray(40 until 56)

        val encryptor = Cipher.getInstance("AES/CTR/NoPadding")
        encryptor.init(Cipher.ENCRYPT_MODE, SecretKeySpec(encKey, "AES"), IvParameterSpec(encIv))

        val encryptedFull = encryptor.doFinal(rnd)

        val keystreamTail = ByteArray(8)
        for (i in 0 until 8) {
            keystreamTail[i] = (encryptedFull[56 + i].toInt() xor rnd[56 + i].toInt()).toByte()
        }

        val dcBytes = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(dcIdx.toShort()).array()
        val tailPlain = protoTag + dcBytes + byteArrayOf(SecureRandom().nextInt().toByte(), SecureRandom().nextInt().toByte())

        val encryptedTail = ByteArray(8)
        for (i in 0 until 8) {
            encryptedTail[i] = (tailPlain[i].toInt() xor keystreamTail[i].toInt()).toByte()
        }

        System.arraycopy(encryptedTail, 0, rnd, 56, 8)
        return rnd
    }

    class CryptoCtx(
        val clientDec: Cipher,
        val clientEnc: Cipher,
        val tgEnc: Cipher,
        val tgDec: Cipher
    )

    private fun buildCryptoCtx(clientDecPrekeyIv: ByteArray, secret: ByteArray, relayInit: ByteArray): CryptoCtx {
        val cltDecPrekey = clientDecPrekeyIv.sliceArray(0 until 32)
        val cltDecIv = clientDecPrekeyIv.sliceArray(32 until 48)

        val md = MessageDigest.getInstance("SHA-256")
        md.update(cltDecPrekey)
        md.update(secret)
        val cltDecKey = md.digest()

        val cltEncPrekeyIv = clientDecPrekeyIv.reversedArray()
        val cltEncPrekey = cltEncPrekeyIv.sliceArray(0 until 32)
        val cltEncIv = cltEncPrekeyIv.sliceArray(32 until 48)

        md.reset()
        md.update(cltEncPrekey)
        md.update(secret)
        val cltEncKey = md.digest()

        val clientDec = Cipher.getInstance("AES/CTR/NoPadding")
        clientDec.init(Cipher.ENCRYPT_MODE, SecretKeySpec(cltDecKey, "AES"), IvParameterSpec(cltDecIv))
        clientDec.update(ByteArray(64))

        val clientEnc = Cipher.getInstance("AES/CTR/NoPadding")
        clientEnc.init(Cipher.ENCRYPT_MODE, SecretKeySpec(cltEncKey, "AES"), IvParameterSpec(cltEncIv))

        val relayEncKey = relayInit.sliceArray(8 until 40)
        val relayEncIv = relayInit.sliceArray(40 until 56)

        val relayDecPrekeyIv = relayInit.sliceArray(8 until 56).reversedArray()
        val relayDecKey = relayDecPrekeyIv.sliceArray(0 until 32)
        val relayDecIv = relayDecPrekeyIv.sliceArray(32 until 48)

        val tgEnc = Cipher.getInstance("AES/CTR/NoPadding")
        tgEnc.init(Cipher.ENCRYPT_MODE, SecretKeySpec(relayEncKey, "AES"), IvParameterSpec(relayEncIv))
        tgEnc.update(ByteArray(64))

        val tgDec = Cipher.getInstance("AES/CTR/NoPadding")
        tgDec.init(Cipher.ENCRYPT_MODE, SecretKeySpec(relayDecKey, "AES"), IvParameterSpec(relayDecIv))

        return CryptoCtx(clientDec, clientEnc, tgEnc, tgDec)
    }

    private fun verifyClientHello(data: ByteArray, secret: ByteArray): Triple<ByteArray, ByteArray, Long>? {
        if (data.size < 43) return null
        if (data[0] != 0x16.toByte() || data[5] != 0x01.toByte()) return null

        val clientRandomOffset = 11
        val clientRandom = data.sliceArray(clientRandomOffset until clientRandomOffset + 32)
        val zeroed = data.clone()
        for (i in 0 until 32) zeroed[clientRandomOffset + i] = 0

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        val expected = mac.doFinal(zeroed)

        for (i in 0 until 28) {
            if (expected[i] != clientRandom[i]) return null
        }

        val tsXor = ByteArray(4)
        for (i in 0 until 4) tsXor[i] = (clientRandom[28 + i].toInt() xor expected[28 + i].toInt()).toByte()
        val ts = (ByteBuffer.wrap(tsXor).order(ByteOrder.LITTLE_ENDIAN).int.toLong()) and 0xFFFFFFFFL

        val now = System.currentTimeMillis() / 1000
        if (Math.abs(now - ts) > 120) return null

        var sessionId = ByteArray(32)
        val sessionIdOffset = 44
        if (data.size >= sessionIdOffset + 32 && data[43] == 0x20.toByte()) {
            sessionId = data.sliceArray(sessionIdOffset until sessionIdOffset + 32)
        }

        return Triple(clientRandom, sessionId, ts)
    }

    private fun buildServerHello(secret: ByteArray, clientRandom: ByteArray, sessionId: ByteArray): ByteArray {
        val shTemplate = byteArrayOf(
            0x16, 0x03, 0x03, 0x00, 0x7a,
            0x02, 0x00, 0x00, 0x76,
            0x03, 0x03
        ) + ByteArray(32) + byteArrayOf(
            0x20
        ) + ByteArray(32) + byteArrayOf(
            0x13, 0x01, 0x00,
            0x00, 0x2e,
            0x00, 0x33, 0x00, 0x24, 0x00, 0x1d, 0x00, 0x20
        ) + ByteArray(32) + byteArrayOf(
            0x00, 0x2b, 0x00, 0x02, 0x03, 0x04
        )
        val sh = shTemplate.clone()
        System.arraycopy(sessionId, 0, sh, 44, 32)
        val pubKey = ByteArray(32).apply { SecureRandom().nextBytes(this) }
        System.arraycopy(pubKey, 0, sh, 89, 32)

        val ccs = byteArrayOf(0x14, 0x03, 0x03, 0x00, 0x01, 0x01)
        val encryptedSize = 1900 + SecureRandom().nextInt(201)
        val encryptedData = ByteArray(encryptedSize).apply { SecureRandom().nextBytes(this) }
        val appRecord = byteArrayOf(0x17, 0x03, 0x03, (encryptedSize shr 8).toByte(), encryptedSize.toByte()) + encryptedData

        val response = sh + ccs + appRecord

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        mac.update(clientRandom)
        val serverRandom = mac.doFinal(response)

        System.arraycopy(serverRandom, 0, response, 11, 32)
        return response
    }

    class FakeTlsInputStream(private val inner: InputStream) : InputStream() {
        private val buf = ByteArray(65536)
        private var bufLen = 0
        private var bufPos = 0

        override fun read(): Int {
            if (bufPos >= bufLen) {
                if (!fill()) return -1
            }
            return buf[bufPos++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (bufPos >= bufLen) {
                if (!fill()) return -1
            }
            val r = Math.min(len, bufLen - bufPos)
            System.arraycopy(buf, bufPos, b, off, r)
            bufPos += r
            return r
        }

        private fun fill(): Boolean {
            bufPos = 0
            bufLen = 0
            while (true) {
                val hdr = ByteArray(5)
                var read = 0
                while (read < 5) {
                    val n = inner.read(hdr, read, 5 - read)
                    if (n == -1) return false
                    read += n
                }
                val rtype = hdr[0].toInt() and 0xFF
                val recLen = ((hdr[3].toInt() and 0xFF) shl 8) or (hdr[4].toInt() and 0xFF)

                if (rtype == 0x14) { // CCS
                    if (recLen > 0) {
                        val ccs = ByteArray(recLen)
                        read = 0
                        while (read < recLen) {
                            val n = inner.read(ccs, read, recLen - read)
                            if (n == -1) return false
                            read += n
                        }
                    }
                    continue
                }
                if (rtype != 0x17) {
                    return false
                }
                val payload = ByteArray(recLen)
                read = 0
                while (read < recLen) {
                    val n = inner.read(payload, read, recLen - read)
                    if (n == -1) return false
                    read += n
                }
                System.arraycopy(payload, 0, buf, 0, Math.min(payload.size, buf.size))
                bufLen = payload.size
                return true
            }
        }
    }

    private fun writeFakeTlsAppRecords(out: OutputStream, data: ByteArray) {
        var offset = 0
        while (offset < data.size) {
            val chunkLen = Math.min(16384, data.size - offset)
            val header = byteArrayOf(0x17, 0x03, 0x03, (chunkLen shr 8).toByte(), chunkLen.toByte())
            out.write(header)
            out.write(data, offset, chunkLen)
            offset += chunkLen
        }
        out.flush()
    }

    private fun wsDomains(dc: Int, isMedia: Boolean): List<String> {
        val base = if (dc > 5) "telegram.org" else "web.telegram.org"
        return if (isMedia) listOf("kws$dc-1.$base", "kws$dc.$base")
        else listOf("kws$dc.$base", "kws$dc-1.$base")
    }

    private fun createSocket(host: String, port: Int): Socket {
        return if (useByeDpi) {
            val provider = ru.toinet.android.util.Prefs.vpnProvider
            val targetPort = when (provider) {
                "byedpi" -> 1080
                "tor" -> 5242
                "tgws" -> 1480
                "rehab" -> 1788
                "turnproxy" -> ru.toinet.android.util.Prefs.turnProxyLocalPort
                "fakevpn" -> 1790
                "operaproxy" -> ru.toinet.android.util.Prefs.operaProxyBindAddress.split(":").last().toIntOrNull() ?: 1888
                "custom" -> ru.toinet.android.util.Prefs.customSocksPort
                else -> 1080
            }
            val targetHost = when (provider) {
                "operaproxy" -> ru.toinet.android.util.Prefs.operaProxyBindAddress.split(":").first()
                "custom" -> ru.toinet.android.util.Prefs.customSocksIp
                else -> "127.0.0.1"
            }
            if (targetPort == this.port && targetHost == "127.0.0.1") {
                val socket = Socket()
                socket.connect(InetSocketAddress(host, port), 5000)
                socket.soTimeout = 10000
                return socket
            }
            
            val proxy = java.net.Proxy(java.net.Proxy.Type.SOCKS, java.net.InetSocketAddress(targetHost, targetPort))
            val socket = Socket(proxy)
            socket.connect(java.net.InetSocketAddress(host, port), 10000)
            socket.soTimeout = 10000
            socket
        } else {
            val socket = Socket()
            socket.connect(java.net.InetSocketAddress(host, port), 5000)
            socket.soTimeout = 10000
            socket
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

    private suspend fun handleTcpFallback(client: Socket, host: String, port: Int, init: ByteArray, ctx: CryptoCtx?, cltIn: InputStream, cltOut: OutputStream, isFakeTls: Boolean) = withContext(Dispatchers.IO) {
        try {
            val remote = createSocket(host, port)
            if (ctx != null) {
                bridgeTcpReencrypt(client, cltIn, cltOut, remote, init, ctx, isFakeTls)
            } else {
                remote.getOutputStream().write(init)
                bridgeTcp(client, remote)
            }
        } catch (e: Exception) {
            client.close()
        }
    }

    private suspend fun bridgeTcp(s1: Socket, s2: Socket) = coroutineScope {
        val job1 = launch { 
            try { pipe(s1.getInputStream(), s2.getOutputStream()) } 
            finally { try { s1.close() } catch (e: Exception) {}; try { s2.close() } catch (e: Exception) {} } 
        }
        val job2 = launch { 
            try { pipe(s2.getInputStream(), s1.getOutputStream()) } 
            finally { try { s1.close() } catch (e: Exception) {}; try { s2.close() } catch (e: Exception) {} } 
        }
        joinAll(job1, job2)
    }
    
    private suspend fun bridgeTcpReencrypt(client: Socket, clientIn: InputStream, clientOut: OutputStream, remote: Socket, init: ByteArray, ctx: CryptoCtx, isFakeTls: Boolean) = coroutineScope {
        val rOut = remote.getOutputStream()
        val rIn = remote.getInputStream()
        val cOut = BufferedOutputStream(clientOut, 65536)

        rOut.write(init)
        rOut.flush()

        val job1 = launch {
            val buf = ByteArray(65536)
            try {
                while (true) {
                    val n = clientIn.read(buf)
                    if (n == -1) break
                    val plain = ctx.clientDec.update(buf, 0, n)
                    if (plain != null && plain.isNotEmpty()) {
                        val encrypted = ctx.tgEnc.update(plain)
                        if (encrypted != null && encrypted.isNotEmpty()) {
                            rOut.write(encrypted)
                            rOut.flush()
                        }
                    }
                }
            } catch (e: Exception) {}
            finally { try { client.close() } catch (e: Exception) {}; try { remote.close() } catch (e: Exception) {} }
        }

        val job2 = launch {
            val buf = ByteArray(65536)
            try {
                while (true) {
                    val n = rIn.read(buf)
                    if (n == -1) break
                    val plain = ctx.tgDec.update(buf, 0, n)
                    if (plain != null && plain.isNotEmpty()) {
                        val encrypted = ctx.clientEnc.update(plain)
                        if (encrypted != null && encrypted.isNotEmpty()) {
                            if (isFakeTls) {
                                writeFakeTlsAppRecords(cOut, encrypted)
                            } else {
                                cOut.write(encrypted)
                                cOut.flush()
                            }
                        }
                    }
                }
            } catch (e: Exception) {}
            finally { try { client.close() } catch (e: Exception) {}; try { remote.close() } catch (e: Exception) {} }
        }

        joinAll(job1, job2)
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

    private fun connectWebSocket(ip: String, domain: String, path: String = "/apiws"): Socket {
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
        val req = "GET $path HTTP/1.1\r\n" +
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

        val input = sslSocket.inputStream
        val headerBuf = ByteArray(8192)
        var pos = 0
        var headerEnd = -1
        while (pos < headerBuf.size) {
            val b = input.read()
            if (b == -1) {
                sslSocket.close()
                throw IOException("Connection closed during WS handshake")
            }
            headerBuf[pos++] = b.toByte()
            if (pos >= 4 && 
                headerBuf[pos-4] == '\r'.toByte() && 
                headerBuf[pos-3] == '\n'.toByte() && 
                headerBuf[pos-2] == '\r'.toByte() && 
                headerBuf[pos-1] == '\n'.toByte()) {
                headerEnd = pos
                break
            }
        }
        if (headerEnd == -1) {
            sslSocket.close()
            throw IOException("WS handshake headers too large")
        }

        val headersStr = String(headerBuf, 0, headerEnd)
        if (!headersStr.contains("101")) {
            sslSocket.close()
            throw IOException("Handshake failed")
        }

        sslSocket.soTimeout = 0 // Reset timeout for long-lived connection
        return sslSocket
    }

    private suspend fun bridgeWs(client: Socket, clientIn: InputStream, clientOut: OutputStream, wsSocket: Socket, init: ByteArray, splitter: MsgSplitter? = null) = coroutineScope {
        val wsIn = wsSocket.getInputStream()
        val wsOut = wsSocket.getOutputStream()
        val cOut = BufferedOutputStream(clientOut, 65536)

        sendWsFrame(wsOut, init)

        val job1 = launch {
            val buf = ByteArray(65536)
            try {
                while (true) {
                    val n = clientIn.read(buf)
                    if (n == -1) {
                        val tail = splitter?.flush()
                        if (!tail.isNullOrEmpty()) {
                            sendWsFrame(wsOut, tail[0])
                        }
                        break
                    }
                    val chunk = buf.sliceArray(0 until n)
                    if (splitter != null) {
                        val parts = splitter.split(chunk)
                        for (part in parts) {
                            sendWsFrame(wsOut, part)
                        }
                    } else {
                        sendWsFrame(wsOut, chunk)
                    }
                }
            } catch (e: Exception) {}
            finally { try { client.close() } catch (e: Exception) {}; try { wsSocket.close() } catch (e: Exception) {} }
        }

        val job2 = launch {
            try {
                while (true) {
                    val data = readWsFrame(wsIn, wsOut) ?: break
                    cOut.write(data)
                    cOut.flush()
                }
            } catch (e: Exception) {}
            finally { try { client.close() } catch (e: Exception) {}; try { wsSocket.close() } catch (e: Exception) {} }
        }

        joinAll(job1, job2)
    }

    private suspend fun bridgeWsReencrypt(client: Socket, clientIn: InputStream, clientOut: OutputStream, wsSocket: Socket, init: ByteArray, ctx: CryptoCtx, isFakeTls: Boolean, splitter: MsgSplitter? = null) = coroutineScope {
        val wsIn = wsSocket.getInputStream()
        val wsOut = wsSocket.getOutputStream()
        val cOut = BufferedOutputStream(clientOut, 65536)

        sendWsFrame(wsOut, init)

        val job1 = launch {
            val buf = ByteArray(65536)
            try {
                while (true) {
                    val n = clientIn.read(buf)
                    if (n == -1) {
                        val tail = splitter?.flush()
                        if (!tail.isNullOrEmpty()) {
                            sendWsFrame(wsOut, tail[0])
                        }
                        break
                    }
                    val plain = ctx.clientDec.update(buf, 0, n)
                    if (plain != null && plain.isNotEmpty()) {
                        val encrypted = ctx.tgEnc.update(plain)
                        if (encrypted != null && encrypted.isNotEmpty()) {
                            if (splitter != null) {
                                val parts = splitter.split(encrypted)
                                for (part in parts) {
                                    sendWsFrame(wsOut, part)
                                }
                            } else {
                                sendWsFrame(wsOut, encrypted)
                            }
                        }
                    }
                }
            } catch (e: Exception) {}
            finally { try { client.close() } catch (e: Exception) {}; try { wsSocket.close() } catch (e: Exception) {} }
        }

        val job2 = launch {
            try {
                while (true) {
                    val data = readWsFrame(wsIn, wsOut) ?: break
                    val plain = ctx.tgDec.update(data)
                    if (plain != null && plain.isNotEmpty()) {
                        val encrypted = ctx.clientEnc.update(plain)
                        if (encrypted != null && encrypted.isNotEmpty()) {
                            if (isFakeTls) {
                                writeFakeTlsAppRecords(cOut, encrypted)
                            } else {
                                cOut.write(encrypted)
                                cOut.flush()
                            }
                        }
                    }
                }
            } catch (e: Exception) {}
            finally { try { client.close() } catch (e: Exception) {}; try { wsSocket.close() } catch (e: Exception) {} }
        }

        joinAll(job1, job2)
    }

    private fun sendWsFrame(out: OutputStream, data: ByteArray, opcode: Int = 0x02) {
        val len = data.size
        var headerLen = 2
        if (len < 126) {
        } else if (len < 65536) {
            headerLen += 2
        } else {
            headerLen += 8
        }
        headerLen += 4 // maskKey
        
        val frame = ByteArray(headerLen + len)
        frame[0] = (0x80 or opcode).toByte()
        
        val maskKey = ByteArray(4)
        SecureRandom().nextBytes(maskKey)
        
        var offset = 1
        if (len < 126) {
            frame[offset++] = (0x80 or len).toByte()
        } else if (len < 65536) {
            frame[offset++] = (0x80 or 126).toByte()
            frame[offset++] = (len shr 8).toByte()
            frame[offset++] = len.toByte()
        } else {
            frame[offset++] = (0x80 or 127).toByte()
            for (i in 7 downTo 0) {
                frame[offset++] = ((len.toLong() shr (i * 8)) and 0xFF).toByte()
            }
        }
        
        System.arraycopy(maskKey, 0, frame, offset, 4)
        offset += 4
        
        for (i in 0 until len) {
            frame[offset + i] = (data[i].toInt() xor maskKey[i % 4].toInt()).toByte()
        }
        
        out.write(frame)
        out.flush()
    }

    private fun readWsFrame(input: InputStream, output: OutputStream): ByteArray? {
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
            var read = 0
            while (read < 4) {
                val n = input.read(key, read, 4 - read)
                if (n == -1) return null
                read += n
            }
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
            0x00, 0x01, 0x02 -> payload
            0x08 -> null // Close
            0x09 -> { // Ping
                try { sendWsFrame(output, payload, 0x0A) } catch (e: Exception) {}
                readWsFrame(input, output)
            }
            0x0A -> { // Pong
                readWsFrame(input, output)
            }
            else -> readWsFrame(input, output)
        }
    }
}
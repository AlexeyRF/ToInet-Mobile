package ru.toinet.android.tgws

import kotlinx.coroutines.*
import ru.toinet.android.util.Prefs
import java.io.*
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class TgwsCore(
    val host: String,
    val port: Int,
    val dcMappings: Map<Int, String> = mapOf(2 to "149.154.167.220", 4 to "149.154.167.220"),
    val secret: String = "",
    val fakeTlsDomain: String = "",
    val useByeDpi: Boolean = false,
    val disableWebSockets: Boolean = false,
    val cfWorkerDomains: List<String> = emptyList(),
    val cfProxyDomains: List<String> = emptyList(),
    val fallbackCfProxy: Boolean = true,
    val cfProxyH2Media: Boolean = true,
    val frontingSni: String = "",
    val disableSecure: Boolean = false,
    val forceTestDc: Boolean = false,
    val poolSize: Int = 4,
    val bufferSize: Int = 256 * 1024,
    val onLog: ((String) -> Unit)? = null
) {
    private var serverSocket: ServerSocket? = null
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val balancer = TgwsBalancer()
    private val pool = TgwsPool(
        poolSize = poolSize,
        bufferSize = bufferSize,
        frontingSni = frontingSni,
        disableSecure = disableSecure,
        scope = scope
    )

    private val tcpFailures = ConcurrentHashMap<Pair<String, Int>, Int>()
    private val tcpRetryAfter = ConcurrentHashMap<Pair<String, Int>, Long>()

    var actualFakeTlsDomain: String = fakeTlsDomain
        private set

    val secretBytes: ByteArray by lazy {
        try {
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
        } catch (_: Exception) {
            ByteArray(0)
        }
    }

    init {
        if (secret.startsWith("ee") && secret.length > 34 && fakeTlsDomain.isBlank()) {
            val domainHex = secret.substring(34)
            try {
                actualFakeTlsDomain = domainHex.chunked(2)
                    .map { it.toInt(16).toByte() }
                    .toByteArray()
                    .toString(Charsets.UTF_8)
            } catch (_: Exception) {}
        }

        if (cfProxyDomains.isNotEmpty()) {
            balancer.updateDomainsList(cfProxyDomains)
        } else {
            balancer.startAutoRefresh(scope)
        }
    }

    fun start() {
        if (job != null) return
        job = scope.launch {
            try {
                serverSocket = ServerSocket(port, 128, InetAddress.getByName(host))
                log("TGWS Proxy listening on $host:$port (FakeTLS: '${actualFakeTlsDomain}', PoolSize: $poolSize)")
                pool.warmup(dcMappings, forceTestDc)
                while (isActive) {
                    val client = try {
                        serverSocket?.accept()
                    } catch (_: Exception) {
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
        job?.cancel()
        job = null
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        balancer.stopAutoRefresh()
        pool.close()
        log("TGWS Proxy stopped")
    }

    private fun log(msg: String) {
        onLog?.invoke(msg)
    }

    private suspend fun handleClient(client: Socket) = withContext(Dispatchers.IO) {
        val label = "${client.inetAddress.hostAddress}:${client.port}"
        try {
            client.tcpNoDelay = true
            try {
                client.receiveBufferSize = bufferSize
                client.sendBufferSize = bufferSize
            } catch (_: Exception) {}
            client.soTimeout = 10000

            val input = client.getInputStream()
            val output = client.getOutputStream()

            val firstByteInt = input.read()
            if (firstByteInt == -1) {
                client.close()
                return@withContext
            }
            val firstByte = firstByteInt.toByte()

            if (firstByte == 0x05.toByte()) {
                handleSocks5(client, input, output, label)
                return@withContext
            }

            handleMtprotoDirect(firstByte, client, input, output, label)
        } catch (e: Exception) {
            if (e !is CancellationException) {
                log("[$label] Error: ${e.message}")
            }
        } finally {
            try {
                client.close()
            } catch (_: Exception) {}
        }
    }

    private suspend fun handleSocks5(
        client: Socket,
        input: InputStream,
        output: OutputStream,
        label: String
    ) = withContext(Dispatchers.IO) {
        // 1. SOCKS5 Handshake
        val nMethods = input.read()
        if (nMethods <= 0) {
            client.close()
            return@withContext
        }
        val methods = ByteArray(nMethods)
        if (!readFully(input, methods)) {
            client.close()
            return@withContext
        }
        output.write(byteArrayOf(0x05, 0x00))
        output.flush()

        // 2. Read Request
        val req = ByteArray(4)
        if (!readFully(input, req)) {
            client.close()
            return@withContext
        }
        if (req[1] != 1.toByte()) { // CMD 1 = CONNECT
            output.write(byteArrayOf(0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            output.flush()
            client.close()
            return@withContext
        }

        val dstHost: String = when (req[3].toInt()) {
            1 -> {
                val ipBytes = ByteArray(4)
                if (!readFully(input, ipBytes)) { client.close(); return@withContext }
                InetAddress.getByAddress(ipBytes).hostAddress ?: ""
            }
            3 -> {
                val len = input.read()
                if (len <= 0) { client.close(); return@withContext }
                val domBytes = ByteArray(len)
                if (!readFully(input, domBytes)) { client.close(); return@withContext }
                String(domBytes, Charsets.US_ASCII)
            }
            4 -> {
                val ipBytes = ByteArray(16)
                if (!readFully(input, ipBytes)) { client.close(); return@withContext }
                InetAddress.getByAddress(ipBytes).hostAddress ?: ""
            }
            else -> {
                output.write(byteArrayOf(0x05, 0x08, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                output.flush()
                client.close()
                return@withContext
            }
        }

        val portBytes = ByteArray(2)
        if (!readFully(input, portBytes)) {
            client.close()
            return@withContext
        }
        val dstPort = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)

        val isTg = TgwsConfig.isTelegramIp(dstHost) || dstHost.contains("telegram") || TgwsConfig.IP_TO_DC.containsKey(dstHost)

        if (!isTg) {
            // SOCKS5 Passthrough for non-Telegram traffic
            log("[$label] SOCKS5 passthrough -> $dstHost:$dstPort")
            try {
                val remote = createSocket(dstHost, dstPort)
                output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                output.flush()
                client.soTimeout = 0
                remote.soTimeout = 0
                bridgeRawTcp(client, remote)
            } catch (e: Exception) {
                try {
                    output.write(byteArrayOf(0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                    output.flush()
                } catch (_: Exception) {}
                client.close()
            }
            return@withContext
        }

        // Destination is Telegram!
        output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
        output.flush()

        val payloadFirstByteInt = input.read()
        if (payloadFirstByteInt == -1) {
            client.close()
            return@withContext
        }
        val payloadFirstByte = payloadFirstByteInt.toByte()

        val dcHint = TgwsConfig.IP_TO_DC[dstHost] ?: 2
        handleMtprotoSocks(payloadFirstByte, client, input, output, label, dstHost, dstPort, dcHint)
    }

    private suspend fun handleMtprotoDirect(
        firstByte: Byte,
        client: Socket,
        input: InputStream,
        output: OutputStream,
        label: String
    ) = withContext(Dispatchers.IO) {
        if (actualFakeTlsDomain.isNotBlank()) {
            if (firstByte != 0x16.toByte()) {
                log("[$label] Non-TLS byte 0x%02X -> HTTP 301 Redirect".format(firstByte))
                val redirect = ("HTTP/1.1 301 Moved Permanently\r\n" +
                        "Location: https://$actualFakeTlsDomain/\r\n" +
                        "Content-Length: 0\r\n" +
                        "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII)
                output.write(redirect)
                output.flush()
                client.close()
                return@withContext
            }

            // Fake TLS handshake
            val hdrRest = ByteArray(4)
            if (!readFully(input, hdrRest)) { client.close(); return@withContext }
            val tlsHeader = byteArrayOf(firstByte) + hdrRest
            val recordLen = ((hdrRest[2].toInt() and 0xFF) shl 8) or (hdrRest[3].toInt() and 0xFF)
            val recordBody = ByteArray(recordLen)
            if (!readFully(input, recordBody)) { client.close(); return@withContext }
            val clientHello = tlsHeader + recordBody

            val tlsResult = if (secretBytes.isNotEmpty()) {
                TgwsCrypto.verifyClientHello(clientHello, secretBytes)
            } else null

            if (tlsResult == null) {
                log("[$label] Fake TLS verification failed -> proxying to masking domain $actualFakeTlsDomain")
                proxyToMaskingDomain(clientHello, input, output, actualFakeTlsDomain)
                return@withContext
            }

            log("[$label] Fake TLS handshake verified (ts=${tlsResult.timestamp})")
            val serverHello = TgwsCrypto.buildServerHello(secretBytes, tlsResult.clientRandom, tlsResult.sessionId)
            output.write(serverHello)
            output.flush()

            val tlsStream = TgwsCrypto.FakeTlsInputStream(input)
            val obfsHandshake = ByteArray(64)
            if (!readFully(tlsStream, obfsHandshake)) { client.close(); return@withContext }

            val hsResult = TgwsCrypto.tryHandshake(obfsHandshake, secretBytes)
            if (hsResult == null) {
                log("[$label] Bad MTProto handshake inside FakeTLS")
                client.close()
                return@withContext
            }

            val dcIdx = if (hsResult.isMedia) -hsResult.dcId else hsResult.dcId
            val relayInit = TgwsCrypto.generateRelayInit(hsResult.protoTag, dcIdx)
            val ctx = TgwsCrypto.buildCryptoCtx(hsResult.clientDecPrekeyIv, secretBytes, relayInit)
            val splitter = try {
                val protoInt = ByteBuffer.wrap(hsResult.protoTag).order(ByteOrder.LITTLE_ENDIAN).int
                MsgSplitter(protoInt)
            } catch (_: Exception) {
                null
            }

            executeBridge(client, tlsStream, output, hsResult.dcId, hsResult.isMedia, relayInit, ctx, isFakeTls = true, splitter = splitter, label = label)
            return@withContext
        }

        // Standard Obfuscated MTProto (no FakeTLS)
        val rest = ByteArray(63)
        if (!readFully(input, rest)) { client.close(); return@withContext }
        val handshake = byteArrayOf(firstByte) + rest

        if (secretBytes.isEmpty()) {
            log("[$label] Dropping direct MTProto (no secret configured)")
            client.close()
            return@withContext
        }

        val hsResult = TgwsCrypto.tryHandshake(handshake, secretBytes)
        if (hsResult == null) {
            log("[$label] Bad MTProto handshake (wrong secret or proto)")
            client.close()
            return@withContext
        }

        val dcIdx = if (hsResult.isMedia) -hsResult.dcId else hsResult.dcId
        val relayInit = TgwsCrypto.generateRelayInit(hsResult.protoTag, dcIdx)
        val ctx = TgwsCrypto.buildCryptoCtx(hsResult.clientDecPrekeyIv, secretBytes, relayInit)
        val splitter = try {
            val protoInt = ByteBuffer.wrap(hsResult.protoTag).order(ByteOrder.LITTLE_ENDIAN).int
            MsgSplitter(protoInt)
        } catch (_: Exception) {
            null
        }

        executeBridge(client, input, output, hsResult.dcId, hsResult.isMedia, relayInit, ctx, isFakeTls = false, splitter = splitter, label = label)
    }

    private suspend fun handleMtprotoSocks(
        firstByte: Byte,
        client: Socket,
        input: InputStream,
        output: OutputStream,
        label: String,
        dstHost: String,
        dstPort: Int,
        dcHint: Int
    ) = withContext(Dispatchers.IO) {
        val rest = ByteArray(63)
        if (!readFully(input, rest)) { client.close(); return@withContext }
        val handshake = byteArrayOf(firstByte) + rest

        val hsResult = if (secretBytes.isNotEmpty()) TgwsCrypto.tryHandshake(handshake, secretBytes) else null
        if (hsResult != null) {
            val dcIdx = if (hsResult.isMedia) -hsResult.dcId else hsResult.dcId
            val relayInit = TgwsCrypto.generateRelayInit(hsResult.protoTag, dcIdx)
            val ctx = TgwsCrypto.buildCryptoCtx(hsResult.clientDecPrekeyIv, secretBytes, relayInit)
            val splitter = try {
                val protoInt = ByteBuffer.wrap(hsResult.protoTag).order(ByteOrder.LITTLE_ENDIAN).int
                MsgSplitter(protoInt)
            } catch (_: Exception) {
                null
            }
            executeBridge(client, input, output, hsResult.dcId, hsResult.isMedia, relayInit, ctx, isFakeTls = false, splitter = splitter, label = label)
        } else {
            // Raw SOCKS MTProto without our secret: try to extract DC or use hint
            val dc = dcHint
            val isMedia = false
            executeBridgeRaw(client, input, output, dc, isMedia, handshake, label, dstHost, dstPort)
        }
    }

    private suspend fun executeBridge(
        client: Socket,
        clientIn: InputStream,
        clientOut: OutputStream,
        dc: Int,
        isMedia: Boolean,
        relayInit: ByteArray,
        ctx: CryptoCtx,
        isFakeTls: Boolean,
        splitter: MsgSplitter?,
        label: String
    ) = withContext(Dispatchers.IO) {
        val targetIp = dcMappings[dc] ?: TgwsConfig.DC_DEFAULT_IPS[dc] ?: "149.154.167.220"
        val mediaTag = if (isMedia) " media" else ""

        if (!disableWebSockets) {
            // 1. Try WS Pool
            val ws = pool.getWs(dc, isMedia, forceTestDc, targetIp, TgwsConfig.wsDomains(dc, isMedia))
            if (ws != null) {
                log("[$label] DC$dc$mediaTag -> WS Pool hit")
                ws.send(relayInit)
                bridgeWsReencrypt(client, clientIn, clientOut, ws, ctx, isFakeTls, splitter, label, dc, isMedia)
                return@withContext
            }

            // 2. Try CF Worker Fallback
            if (cfWorkerDomains.isNotEmpty()) {
                val workerPooled = pool.getCfWorker(dc, targetIp, cfWorkerDomains)
                if (workerPooled != null) {
                    val (workerWs, workerDomain) = workerPooled
                    log("[$label] DC$dc$mediaTag -> CF Worker pool hit via $workerDomain")
                    workerWs.send(relayInit)
                    bridgeWsReencrypt(client, clientIn, clientOut, workerWs, ctx, isFakeTls, splitter, label, dc, isMedia)
                    return@withContext
                }

                val query = "dst=" + URLEncoder.encode(targetIp, "UTF-8") + "&dc=$dc"
                val path = "/apiws?$query"
                for (workerDomain in cfWorkerDomains.shuffled()) {
                    try {
                        val workerWs = RawWebSocket.connect(
                            host = workerDomain,
                            domain = workerDomain,
                            timeoutMs = 8000,
                            path = path,
                            secure = !disableSecure,
                            bufferSize = bufferSize
                        )
                        log("[$label] DC$dc$mediaTag -> CF Worker connected via $workerDomain")
                        workerWs.send(relayInit)
                        bridgeWsReencrypt(client, clientIn, clientOut, workerWs, ctx, isFakeTls, splitter, label, dc, isMedia)
                        return@withContext
                    } catch (e: Exception) {
                        log("[$label] CF Worker $workerDomain failed: ${e.message}")
                    }
                }
            }

            // 3. Try CF Proxy Fallback
            if (fallbackCfProxy && !forceTestDc) {
                log("[$label] DC$dc$mediaTag -> trying CF proxy balancer")
                for (baseDomain in balancer.getDomainsForDc(dc)) {
                    val domain = "kws$dc.$baseDomain"
                    try {
                        val cfWs = RawWebSocket.connect(
                            host = domain,
                            domain = domain,
                            timeoutMs = 8000,
                            secure = !disableSecure,
                            bufferSize = bufferSize
                        )
                        balancer.updateDomainForDc(dc, baseDomain)
                        log("[$label] DC$dc$mediaTag -> CF Proxy connected via $domain")
                        cfWs.send(relayInit)
                        bridgeWsReencrypt(client, clientIn, clientOut, cfWs, ctx, isFakeTls, splitter, label, dc, isMedia)
                        return@withContext
                    } catch (_: Exception) {}
                }
            }
        }

        // 4. Try Tor Fallback (if Tor is enabled, SOCKS5 to port 9853)
        if (Prefs.torEnabled) {
            log("[$label] DC$dc$mediaTag -> trying TOR fallback (127.0.0.1:9853)")
            try {
                val torSocket = Socket("127.0.0.1", 9853)
                torSocket.soTimeout = 5000
                val torOut = torSocket.getOutputStream()
                val torIn = torSocket.getInputStream()

                // SOCKS5 handshake
                torOut.write(byteArrayOf(0x05, 0x01, 0x00))
                torOut.flush()
                val torAuth = ByteArray(2)
                if (readFully(torIn, torAuth) && torAuth[1] == 0x00.toByte()) {
                    // Connect to target IP
                    val ipBytes = InetAddress.getByName(targetIp).address
                    val req = byteArrayOf(0x05, 0x01, 0x00, 0x01) + ipBytes + byteArrayOf(0x01, 0xBB.toByte())
                    torOut.write(req)
                    torOut.flush()
                    val rep = ByteArray(10)
                    if (readFully(torIn, rep) && rep[1] == 0x00.toByte()) {
                        log("[$label] DC$dc$mediaTag -> TOR fallback successful")
                        torSocket.soTimeout = 0
                        torOut.write(relayInit)
                        torOut.flush()
                        bridgeTcpReencrypt(client, clientIn, clientOut, torSocket, ctx, isFakeTls, label)
                        return@withContext
                    }
                }
                torSocket.close()
            } catch (e: Exception) {
                log("[$label] TOR fallback failed: ${e.message}")
            }
        }

        // 5. Direct TCP Fallback
        log("[$label] DC$dc$mediaTag -> Direct TCP fallback to $targetIp:443")
        try {
            val remote = createSocket(targetIp, 443)
            remote.soTimeout = 0
            val rOut = remote.getOutputStream()
            rOut.write(relayInit)
            rOut.flush()
            bridgeTcpReencrypt(client, clientIn, clientOut, remote, ctx, isFakeTls, label)
        } catch (e: Exception) {
            log("[$label] TCP fallback failed: ${e.message}")
            client.close()
        }
    }

    private suspend fun executeBridgeRaw(
        client: Socket,
        clientIn: InputStream,
        clientOut: OutputStream,
        dc: Int,
        isMedia: Boolean,
        handshake: ByteArray,
        label: String,
        dstHost: String,
        dstPort: Int
    ) = withContext(Dispatchers.IO) {
        val targetIp = dcMappings[dc] ?: (if (dstHost.isNotBlank()) dstHost else "149.154.167.220")
        val targetPort = if (dstPort > 0) dstPort else 443

        if (!disableWebSockets) {
            val ws = pool.getWs(dc, isMedia, forceTestDc, targetIp, TgwsConfig.wsDomains(dc, isMedia))
            if (ws != null) {
                log("[$label] DC$dc -> WS Pool hit (raw)")
                ws.send(handshake)
                bridgeWsRaw(client, clientIn, clientOut, ws, label)
                return@withContext
            }
        }

        // Direct TCP
        try {
            val remote = createSocket(targetIp, targetPort)
            remote.soTimeout = 0
            val rOut = remote.getOutputStream()
            rOut.write(handshake)
            rOut.flush()
            bridgeRawTcp(client, remote)
        } catch (e: Exception) {
            log("[$label] Raw TCP failed: ${e.message}")
            client.close()
        }
    }

    private suspend fun bridgeWsReencrypt(
        client: Socket,
        clientIn: InputStream,
        clientOut: OutputStream,
        ws: RawWebSocket,
        ctx: CryptoCtx,
        isFakeTls: Boolean,
        splitter: MsgSplitter?,
        label: String,
        dc: Int,
        isMedia: Boolean
    ) = coroutineScope {
        client.soTimeout = 0
        val dcTag = "DC$dc${if (isMedia) "m" else ""}"
        val cOut = BufferedOutputStream(clientOut, 65536)

        val jobClientToWs = launch(Dispatchers.IO) {
            val buf = ByteArray(65536)
            try {
                while (isActive && !ws.isClosed) {
                    val n = clientIn.read(buf)
                    if (n == -1) {
                        val tail = splitter?.flush()
                        if (!tail.isNullOrEmpty()) {
                            ws.send(tail[0])
                        }
                        break
                    }
                    val plain = ctx.clientDec.update(buf, 0, n)
                    if (plain != null && plain.isNotEmpty()) {
                        val chunk = ctx.tgEnc.update(plain)
                        if (chunk != null && chunk.isNotEmpty()) {
                            if (splitter != null) {
                                val parts = splitter.split(chunk, plain)
                                if (parts.size > 1) {
                                    ws.sendBatch(parts)
                                } else if (parts.isNotEmpty()) {
                                    ws.send(parts[0])
                                }
                            } else {
                                ws.send(chunk)
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                ws.close()
                try { client.close() } catch (_: Exception) {}
            }
        }

        val jobWsToClient = launch(Dispatchers.IO) {
            try {
                while (isActive && !ws.isClosed) {
                    val data = ws.recv() ?: break
                    val plain = ctx.tgDec.update(data)
                    if (plain != null && plain.isNotEmpty()) {
                        val encrypted = ctx.clientEnc.update(plain)
                        if (encrypted != null && encrypted.isNotEmpty()) {
                            if (isFakeTls) {
                                TgwsCrypto.writeFakeTlsAppRecords(cOut, encrypted)
                            } else {
                                cOut.write(encrypted)
                                cOut.flush()
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                ws.close()
                try { client.close() } catch (_: Exception) {}
            }
        }

        joinAll(jobClientToWs, jobWsToClient)
    }

    private suspend fun bridgeTcpReencrypt(
        client: Socket,
        clientIn: InputStream,
        clientOut: OutputStream,
        remote: Socket,
        ctx: CryptoCtx,
        isFakeTls: Boolean,
        label: String
    ) = coroutineScope {
        client.soTimeout = 0
        remote.soTimeout = 0
        val rOut = BufferedOutputStream(remote.getOutputStream(), 65536)
        val rIn = remote.getInputStream()
        val cOut = BufferedOutputStream(clientOut, 65536)

        val job1 = launch(Dispatchers.IO) {
            val buf = ByteArray(65536)
            try {
                while (isActive) {
                    val n = clientIn.read(buf)
                    if (n == -1) break
                    val plain = ctx.clientDec.update(buf, 0, n)
                    if (plain != null && plain.isNotEmpty()) {
                        val enc = ctx.tgEnc.update(plain)
                        if (enc != null && enc.isNotEmpty()) {
                            rOut.write(enc)
                            rOut.flush()
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                try { client.close() } catch (_: Exception) {}
                try { remote.close() } catch (_: Exception) {}
            }
        }

        val job2 = launch(Dispatchers.IO) {
            val buf = ByteArray(65536)
            try {
                while (isActive) {
                    val n = rIn.read(buf)
                    if (n == -1) break
                    val plain = ctx.tgDec.update(buf, 0, n)
                    if (plain != null && plain.isNotEmpty()) {
                        val enc = ctx.clientEnc.update(plain)
                        if (enc != null && enc.isNotEmpty()) {
                            if (isFakeTls) {
                                TgwsCrypto.writeFakeTlsAppRecords(cOut, enc)
                            } else {
                                cOut.write(enc)
                                cOut.flush()
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                try { client.close() } catch (_: Exception) {}
                try { remote.close() } catch (_: Exception) {}
            }
        }

        joinAll(job1, job2)
    }

    private suspend fun bridgeWsRaw(
        client: Socket,
        clientIn: InputStream,
        clientOut: OutputStream,
        ws: RawWebSocket,
        label: String
    ) = coroutineScope {
        client.soTimeout = 0
        val cOut = BufferedOutputStream(clientOut, 65536)

        val j1 = launch(Dispatchers.IO) {
            val buf = ByteArray(65536)
            try {
                while (isActive && !ws.isClosed) {
                    val n = clientIn.read(buf)
                    if (n == -1) break
                    ws.send(buf.copyOfRange(0, n))
                }
            } catch (_: Exception) {
            } finally {
                ws.close()
                try { client.close() } catch (_: Exception) {}
            }
        }

        val j2 = launch(Dispatchers.IO) {
            try {
                while (isActive && !ws.isClosed) {
                    val data = ws.recv() ?: break
                    cOut.write(data)
                    cOut.flush()
                }
            } catch (_: Exception) {
            } finally {
                ws.close()
                try { client.close() } catch (_: Exception) {}
            }
        }

        joinAll(j1, j2)
    }

    private suspend fun bridgeRawTcp(s1: Socket, s2: Socket) = coroutineScope {
        s1.soTimeout = 0
        s2.soTimeout = 0

        val j1 = launch(Dispatchers.IO) {
            val buf = ByteArray(65536)
            val in1 = s1.getInputStream()
            val out2 = s2.getOutputStream()
            try {
                while (isActive) {
                    val n = in1.read(buf)
                    if (n == -1) break
                    out2.write(buf, 0, n)
                    out2.flush()
                }
            } catch (_: Exception) {
            } finally {
                try { s1.close() } catch (_: Exception) {}
                try { s2.close() } catch (_: Exception) {}
            }
        }

        val j2 = launch(Dispatchers.IO) {
            val buf = ByteArray(65536)
            val in2 = s2.getInputStream()
            val out1 = s1.getOutputStream()
            try {
                while (isActive) {
                    val n = in2.read(buf)
                    if (n == -1) break
                    out1.write(buf, 0, n)
                    out1.flush()
                }
            } catch (_: Exception) {
            } finally {
                try { s1.close() } catch (_: Exception) {}
                try { s2.close() } catch (_: Exception) {}
            }
        }

        joinAll(j1, j2)
    }

    private suspend fun proxyToMaskingDomain(
        clientHello: ByteArray,
        clientIn: InputStream,
        clientOut: OutputStream,
        maskingDomain: String
    ) = withContext(Dispatchers.IO) {
        try {
            val remote = Socket()
            remote.connect(InetSocketAddress(maskingDomain, 443), 8000)
            remote.soTimeout = 0
            val rOut = remote.getOutputStream()
            val rIn = remote.getInputStream()

            rOut.write(clientHello)
            rOut.flush()

            val j1 = scope.launch(Dispatchers.IO) {
                val buf = ByteArray(32768)
                try {
                    while (isActive) {
                        val n = clientIn.read(buf)
                        if (n == -1) break
                        rOut.write(buf, 0, n)
                        rOut.flush()
                    }
                } catch (_: Exception) {}
            }

            val j2 = scope.launch(Dispatchers.IO) {
                val buf = ByteArray(32768)
                try {
                    while (isActive) {
                        val n = rIn.read(buf)
                        if (n == -1) break
                        clientOut.write(buf, 0, n)
                        clientOut.flush()
                    }
                } catch (_: Exception) {}
            }

            joinAll(j1, j2)
        } catch (_: Exception) {}
    }

    private fun createSocket(host: String, port: Int): Socket {
        return if (useByeDpi) {
            val provider = Prefs.vpnProvider
            val targetPort = when (provider) {
                "byedpi" -> 1780
                "tor" -> 9853
                "operaproxy" -> Prefs.operaProxyBindAddress.split(":").lastOrNull()?.toIntOrNull() ?: 1785
                "easyvpn" -> Prefs.easyVpnProxyPort
                "svpn", "ssh" -> Prefs.svpnLocalPort
                else -> 1780
            }
            val targetHost = when (provider) {
                "operaproxy" -> Prefs.operaProxyBindAddress.split(":").first()
                else -> "127.0.0.1"
            }
            if (targetPort == this.port && targetHost == "127.0.0.1") {
                val socket = Socket()
                socket.connect(InetSocketAddress(host, port), 8000)
                socket.soTimeout = 0
                return socket
            }

            val proxy = java.net.Proxy(java.net.Proxy.Type.SOCKS, InetSocketAddress(targetHost, targetPort))
            val socket = Socket(proxy)
            socket.connect(InetSocketAddress.createUnresolved(host, port), 10000)
            socket.soTimeout = 0
            socket
        } else {
            val socket = Socket()
            socket.connect(InetSocketAddress(host, port), 8000)
            socket.soTimeout = 0
            socket
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
}

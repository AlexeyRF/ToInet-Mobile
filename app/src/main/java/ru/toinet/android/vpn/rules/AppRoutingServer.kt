package ru.toinet.android.vpn.rules

import android.content.Context
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import ru.toinet.android.util.Prefs
import ru.toinet.android.util.UpstreamProxyCoordinator
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

class AppRoutingServer(
    private val vpnService: VpnService,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "AppRoutingServer"
        const val ROUTER_PORT = 10850
    }

    private var serverSocket: ServerSocket? = null
    private val isRunning = AtomicBoolean(false)
    private var serverScope: CoroutineScope? = null

    fun start() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.w(TAG, "AppRoutingServer is only supported on Android Q (API 29) or higher")
            return
        }
        if (isRunning.getAndSet(true)) return

        val sScope = CoroutineScope(Dispatchers.IO + SupervisorJob(scope.coroutineContext[Job]))
        serverScope = sScope

        sScope.launch {
            try {
                val s = ServerSocket()
                s.reuseAddress = true
                s.bind(InetSocketAddress("127.0.0.1", ROUTER_PORT))
                serverSocket = s
                Log.i(TAG, "AppRoutingServer started on 127.0.0.1:$ROUTER_PORT")

                while (isActive && isRunning.get()) {
                    try {
                        val client = s.accept()
                        if (!isRunning.get() || !isActive) {
                            try { client.close() } catch (_: Exception) {}
                            break
                        }
                        sScope.launch {
                            handleClient(client)
                        }
                    } catch (e: Exception) {
                        if (!isRunning.get()) break
                        Log.e(TAG, "Error accepting client", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start router server", e)
            } finally {
                stop()
            }
        }
    }

    fun stop() {
        if (!isRunning.getAndSet(false)) return
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
        try {
            serverScope?.cancel()
        } catch (_: Exception) {}
        serverScope = null
        Log.i(TAG, "AppRoutingServer stopped")
    }

    private suspend fun handleClient(clientSocket: Socket) {
        var upstreamSocket: Socket? = null
        try {
            clientSocket.tcpNoDelay = true
            clientSocket.keepAlive = true
            clientSocket.soTimeout = 15_000 // 15s timeout for SOCKS5 handshake
            val clientIn = DataInputStream(clientSocket.getInputStream())
            val clientOut = DataOutputStream(clientSocket.getOutputStream())

            // SOCKS5 Handshake
            val ver = clientIn.readUnsignedByte()
            if (ver != 5) {
                clientSocket.close()
                return
            }
            val nMethods = clientIn.readUnsignedByte()
            val methods = ByteArray(nMethods)
            clientIn.readFully(methods)

            var hasPasswordAuth = false
            for (m in methods) {
                if (m.toInt() == 2) {
                    hasPasswordAuth = true
                    break
                }
            }

            if (!hasPasswordAuth) {
                clientOut.writeByte(5)
                clientOut.writeByte(0xFF) // No acceptable methods
                clientOut.flush()
                clientSocket.close()
                return
            }

            // Demand username/password auth
            clientOut.writeByte(5)
            clientOut.writeByte(2)
            clientOut.flush()

            // Subnegotiation: Username / Password
            val subVer = clientIn.readUnsignedByte()
            if (subVer != 1) {
                clientSocket.close()
                return
            }
            val ulen = clientIn.readUnsignedByte()
            val uBytes = ByteArray(ulen)
            clientIn.readFully(uBytes)
            val username = String(uBytes, Charsets.UTF_8)

            val plen = clientIn.readUnsignedByte()
            val pBytes = ByteArray(plen)
            clientIn.readFully(pBytes)

            // Success reply for auth
            clientOut.writeByte(1)
            clientOut.writeByte(0)
            clientOut.flush()

            // Parse source info from username "toinet@<src_ip>:<src_port>"
            var srcIp = "26.26.26.1"
            var srcPort = 0
            if (username.contains("@")) {
                val addrPart = username.substringAfter("@")
                val lastColon = addrPart.lastIndexOf(':')
                if (lastColon != -1) {
                    srcIp = addrPart.substring(0, lastColon).trim('[', ']')
                    srcPort = addrPart.substring(lastColon + 1).toIntOrNull() ?: 0
                }
            }

            // Read SOCKS5 CONNECT Request
            val reqVer = clientIn.readUnsignedByte()
            val cmd = clientIn.readUnsignedByte()
            val rsv = clientIn.readUnsignedByte()
            val atyp = clientIn.readUnsignedByte()

            var dstHost = ""
            val rawAddrBytes = mutableListOf<Byte>()
            when (atyp) {
                1 -> { // IPv4
                    val b = ByteArray(4)
                    clientIn.readFully(b)
                    dstHost = InetAddress.getByAddress(b).hostAddress ?: ""
                    b.forEach { rawAddrBytes.add(it) }
                }
                3 -> { // Domain
                    val dlen = clientIn.readUnsignedByte()
                    val b = ByteArray(dlen)
                    clientIn.readFully(b)
                    dstHost = String(b, Charsets.UTF_8)
                    rawAddrBytes.add(dlen.toByte())
                    b.forEach { rawAddrBytes.add(it) }
                }
                4 -> { // IPv6
                    val b = ByteArray(16)
                    clientIn.readFully(b)
                    dstHost = InetAddress.getByAddress(b).hostAddress ?: ""
                    b.forEach { rawAddrBytes.add(it) }
                }
                else -> {
                    clientSocket.close()
                    return
                }
            }
            val dstPort = clientIn.readUnsignedShort()

            if (cmd != 1) { // 1 = CONNECT
                // Unsupported command
                clientOut.write(byteArrayOf(5, 7, 0, 1, 0, 0, 0, 0, 0, 0))
                clientOut.flush()
                clientSocket.close()
                return
            }

            // Reconstruct exact request bytes in case we forward to upstream SOCKS5
            val requestBytes = ByteArray(4 + rawAddrBytes.size + 2).apply {
                this[0] = reqVer.toByte()
                this[1] = cmd.toByte()
                this[2] = rsv.toByte()
                this[3] = atyp.toByte()
                for (i in rawAddrBytes.indices) {
                    this[4 + i] = rawAddrBytes[i]
                }
                this[4 + rawAddrBytes.size] = ((dstPort shr 8) and 0xFF).toByte()
                this[4 + rawAddrBytes.size + 1] = (dstPort and 0xFF).toByte()
            }

            // Step 1: Initial resolution by package UID or explicit destination host
            var targetAction = resolveAction(srcIp, srcPort, dstHost, dstPort)
            if (targetAction == Prefs.appRoutingDefaultAction && dstHost.isNotBlank()) {
                val domainAction = findActionForDomain(dstHost)
                if (domainAction != null) {
                    targetAction = domainAction
                }
            }

            if (targetAction == ProxifierRule.ACTION_BLOCK) {
                // 0x02 = Connection not allowed by ruleset
                clientOut.write(byteArrayOf(5, 2, 0, 1, 0, 0, 0, 0, 0, 0))
                clientOut.flush()
                clientSocket.close()
                return
            }

            // Signal success to tun2socks to immediately unblock and read the first data chunk
            clientOut.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
            clientOut.flush()

            val firstChunk = ByteArray(16384)
            var firstChunkLen = 0
            clientSocket.soTimeout = 2000 // 2s timeout for first payload
            try {
                firstChunkLen = clientIn.read(firstChunk)
            } catch (_: Exception) {}

            if (firstChunkLen <= 0) {
                clientSocket.close()
                return
            }

            // Step 2: Inspect the TLS ClientHello / HTTP Host header for known domains (e.g. YouTube/googlevideo)
            // Specific domain rules (e.g. YouTube -> byedpi) take precedence over general application rules (e.g. Browsers -> easyvpn)
            val detectedDomain = extractDomain(firstChunk, firstChunkLen)
            if (detectedDomain != null) {
                val domainRule = ProxifierRulesManager.findRuleForDomain(detectedDomain)
                if (domainRule != null) {
                    targetAction = domainRule.action
                } else if (targetAction == Prefs.appRoutingDefaultAction) {
                    val domainAction = findActionForDomain(detectedDomain)
                    if (domainAction != null) {
                        targetAction = domainAction
                    }
                }
            }

            if (targetAction == ProxifierRule.ACTION_BLOCK) {
                clientSocket.close()
                return
            }

            when (targetAction) {
                ProxifierRule.ACTION_DIRECT -> {
                    // Direct connection, protected from VPN
                    val directSocket = Socket()
                    vpnService.protect(directSocket)
                    directSocket.tcpNoDelay = true
                    directSocket.keepAlive = true
                    directSocket.soTimeout = 120_000
                    try {
                        withContext(Dispatchers.IO) {
                            directSocket.connect(InetSocketAddress(dstHost, dstPort), 10000)
                        }
                    } catch (e: Exception) {
                        try { directSocket.close() } catch (_: Exception) {}
                        clientSocket.close()
                        return
                    }
                    upstreamSocket = directSocket

                    directSocket.getOutputStream().write(firstChunk, 0, firstChunkLen)
                    directSocket.getOutputStream().flush()

                    pipeSockets(clientSocket, directSocket)
                }
                else -> {
                    // SOCKS5 upstream proxy (e.g. ByeDPI, EasyVPN, Tor)
                    val proxyPort = getProxyPort(targetAction)
                    var proxySocket = Socket()
                    proxySocket.tcpNoDelay = true
                    proxySocket.keepAlive = true
                    proxySocket.soTimeout = 120_000

                    var connected = false
                    try {
                        withContext(Dispatchers.IO) {
                            proxySocket.connect(InetSocketAddress("127.0.0.1", proxyPort), 3000)
                        }
                        connected = true
                    } catch (e: Exception) {
                        Log.w(TAG, "Connect to proxy $targetAction on port $proxyPort failed, ensuring upstream started and retrying...", e)
                        try { proxySocket.close() } catch (_: Exception) {}
                        UpstreamProxyCoordinator.ensureUpstreamStarted(vpnService, targetAction)
                        delay(500)
                        proxySocket = Socket()
                        proxySocket.tcpNoDelay = true
                        proxySocket.keepAlive = true
                        proxySocket.soTimeout = 120_000
                        try {
                            withContext(Dispatchers.IO) {
                                proxySocket.connect(InetSocketAddress("127.0.0.1", proxyPort), 4000)
                            }
                            connected = true
                        } catch (e2: Exception) {
                            Log.e(TAG, "Retry connect to proxy $targetAction on port $proxyPort failed", e2)
                            try { proxySocket.close() } catch (_: Exception) {}
                        }
                    }

                    if (!connected) {
                        clientSocket.close()
                        return
                    }
                    upstreamSocket = proxySocket

                    val upIn = DataInputStream(proxySocket.getInputStream())
                    val upOut = DataOutputStream(proxySocket.getOutputStream())

                    // Upstream SOCKS5 handshake (No Auth)
                    upOut.write(byteArrayOf(5, 1, 0))
                    upOut.flush()
                    val upAuthResp = ByteArray(2)
                    upIn.readFully(upAuthResp)
                    if (upAuthResp[0].toInt() != 5 || upAuthResp[1].toInt() != 0) {
                        clientSocket.close()
                        proxySocket.close()
                        return
                    }

                    // Forward CONNECT request
                    upOut.write(requestBytes)
                    upOut.flush()

                    // Read upstream CONNECT response
                    val upRespHeader = ByteArray(4)
                    upIn.readFully(upRespHeader)
                    if (upRespHeader[0].toInt() != 5) {
                        throw IOException("Invalid SOCKS5 version from upstream")
                    }

                    // Discard upstream BND.ADDR and BND.PORT to fully consume handshake
                    val atypVal = upRespHeader[3].toInt() and 0xFF
                    val addrLen = when (atypVal) {
                        1 -> 4 + 2 // IPv4
                        4 -> 16 + 2 // IPv6
                        3 -> { // Domain
                            val dlen = upIn.readUnsignedByte()
                            dlen + 2
                        }
                        else -> throw IOException("Unsupported SOCKS5 ATYP from upstream: $atypVal")
                    }
                    val discardBuf = ByteArray(addrLen)
                    upIn.readFully(discardBuf)

                    if (upRespHeader[1].toInt() != 0) {
                        clientSocket.close()
                        proxySocket.close()
                        return
                    }

                    // Forward first data payload to upstream
                    proxySocket.getOutputStream().write(firstChunk, 0, firstChunkLen)
                    proxySocket.getOutputStream().flush()

                    pipeSockets(clientSocket, proxySocket)
                }
            }
        } catch (_: Exception) {
        } finally {
            try { clientSocket.close() } catch (_: Exception) {}
            try { upstreamSocket?.close() } catch (_: Exception) {}
        }
    }

    private fun resolveAction(srcIp: String, srcPort: Int, dstHost: String, dstPort: Int): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cm = vpnService.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (cm != null && srcPort > 0) {
                try {
                    val local = InetSocketAddress(InetAddress.getByName(srcIp), srcPort)
                    val remote = InetSocketAddress(InetAddress.getByName(dstHost), dstPort)
                    val uid = cm.getConnectionOwnerUid(android.system.OsConstants.IPPROTO_TCP, local, remote)
                    if (uid > 0) {
                        // Prevent self-loop if connection originated from ToInet itself
                        if (uid == android.os.Process.myUid()) {
                            return ProxifierRule.ACTION_DIRECT
                        }
                        val packages = vpnService.packageManager.getPackagesForUid(uid)
                        if (packages != null) {
                            val rule = ProxifierRulesManager.findRuleForPackages(packages)
                            if (rule != null) {
                                return rule.action
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error resolving connection owner: $srcIp:$srcPort -> $dstHost:$dstPort", e)
                }
            }
        }
        return Prefs.appRoutingDefaultAction
    }

    private fun getProxyPort(action: String): Int {
        return when (action) {
            ProxifierRule.ACTION_BYEDPI -> {
                try {
                    ru.toinet.android.byedpi.core.ByeDpiProxyPreferences.fromSharedPreferences(
                        androidx.preference.PreferenceManager.getDefaultSharedPreferences(vpnService)
                    ).port
                } catch (_: Exception) { 1780 }
            }
            ProxifierRule.ACTION_EASYVPN -> if (Prefs.easyVpnProxyPort in 1..65535) Prefs.easyVpnProxyPort else 1790
            ProxifierRule.ACTION_TOR -> {
                try {
                    ru.toinet.android.util.Prefs.proxySocksPort?.toIntOrNull()?.takeIf { it in 1..65535 } ?: 9853
                } catch (_: Exception) { 9853 }
            }
            ProxifierRule.ACTION_OPERAPROXY -> {
                Prefs.operaProxyBindAddress.split(":").last().toIntOrNull() ?: 1785
            }
            ProxifierRule.ACTION_SVPN -> if (Prefs.svpnLocalPort in 1..65535) Prefs.svpnLocalPort else 1770
            else -> 1780
        }
    }

    private fun findActionForDomain(domain: String): String? {
        val rule = ProxifierRulesManager.findRuleForDomain(domain)
        if (rule != null) {
            return rule.action
        }
        val lower = domain.lowercase()
        if (lower.contains("googlevideo.com") || lower.contains("youtube.com") || lower.contains("ytimg.com") || lower.contains("ggpht.com") || lower == "youtu.be") {
            val rules = ProxifierRulesManager.getRules()
            val ytRule = rules.firstOrNull { it.enabled && (it.name.contains("YouTube", ignoreCase = true) || it.targetPackages.any { p -> p.contains("youtube") }) }
            if (ytRule != null) {
                return ytRule.action
            }
            return ProxifierRule.ACTION_BYEDPI
        }
        return null
    }

    private fun extractDomain(bytes: ByteArray, length: Int): String? {
        if (length < 5) return null

        // 1. Try TLS SNI extraction
        try {
            if (bytes[0] == 0x16.toByte() && bytes[1] == 0x03.toByte()) {
                if (length >= 9 && bytes[5] == 0x01.toByte()) { // ClientHello
                    var pos = 43 // Skip Record Header (5) + Handshake Type(1) + Handshake Length(3) + Version(2) + Random(32)
                    if (pos < length) {
                        val sessionLen = bytes[pos].toInt() and 0xFF
                        pos += 1 + sessionLen
                        if (pos + 2 <= length) {
                            val cipherLen = ((bytes[pos].toInt() and 0xFF) shl 8) or (bytes[pos + 1].toInt() and 0xFF)
                            pos += 2 + cipherLen
                            if (pos + 1 <= length) {
                                val compLen = bytes[pos].toInt() and 0xFF
                                pos += 1 + compLen
                                if (pos + 2 <= length) {
                                    val extTotalLen = ((bytes[pos].toInt() and 0xFF) shl 8) or (bytes[pos + 1].toInt() and 0xFF)
                                    pos += 2
                                    val extEnd = minOf(pos + extTotalLen, length)
                                    while (pos + 4 <= extEnd) {
                                        val extType = ((bytes[pos].toInt() and 0xFF) shl 8) or (bytes[pos + 1].toInt() and 0xFF)
                                        val extLen = ((bytes[pos + 2].toInt() and 0xFF) shl 8) or (bytes[pos + 3].toInt() and 0xFF)
                                        pos += 4
                                        if (extType == 0) { // server_name
                                            if (pos + 2 <= extEnd) {
                                                val listLen = ((bytes[pos].toInt() and 0xFF) shl 8) or (bytes[pos + 1].toInt() and 0xFF)
                                                var namePos = pos + 2
                                                val listEnd = minOf(namePos + listLen, pos + extLen, length)
                                                while (namePos + 3 <= listEnd) {
                                                    val nameType = bytes[namePos].toInt() and 0xFF
                                                    val nameLen = ((bytes[namePos + 1].toInt() and 0xFF) shl 8) or (bytes[namePos + 2].toInt() and 0xFF)
                                                    namePos += 3
                                                    if (nameType == 0 && namePos + nameLen <= length) {
                                                        return String(bytes, namePos, nameLen, Charsets.US_ASCII)
                                                    }
                                                    namePos += nameLen
                                                }
                                            }
                                        }
                                        pos += extLen
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        // 2. Try HTTP Host header extraction
        try {
            val text = String(bytes, 0, minOf(length, 2048), Charsets.US_ASCII)
            val methods = listOf("GET ", "POST ", "HEAD ", "CONNECT ", "PUT ", "DELETE ", "OPTIONS ")
            if (methods.any { text.startsWith(it) }) {
                val lines = text.split("\r\n", "\n")
                for (line in lines) {
                    if (line.startsWith("Host:", ignoreCase = true)) {
                        return line.substring(5).trim().substringBefore(':')
                    }
                }
            }
        } catch (_: Exception) {}

        return null
    }

    private suspend fun pipeSockets(s1: Socket, s2: Socket) = coroutineScope {
        try {
            s1.soTimeout = 120_000
            s2.soTimeout = 120_000
        } catch (_: Exception) {}

        val firstDone = CompletableDeferred<Boolean>()

        val j1 = launch(Dispatchers.IO) {
            var clean = false
            try {
                val input: InputStream = s1.getInputStream()
                val output: OutputStream = s2.getOutputStream()
                val buffer = ByteArray(16384)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    output.flush()
                }
                clean = true
                try { s2.shutdownOutput() } catch (_: Exception) {}
            } catch (_: Exception) {
            } finally {
                firstDone.complete(clean)
            }
        }

        val j2 = launch(Dispatchers.IO) {
            var clean = false
            try {
                val input: InputStream = s2.getInputStream()
                val output: OutputStream = s1.getOutputStream()
                val buffer = ByteArray(16384)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    output.flush()
                }
                clean = true
                try { s1.shutdownOutput() } catch (_: Exception) {}
            } catch (_: Exception) {
            } finally {
                firstDone.complete(clean)
            }
        }

        try {
            val clean = firstDone.await()
            if (!clean) {
                try { s1.close() } catch (_: Exception) {}
                try { s2.close() } catch (_: Exception) {}
            } else {
                withTimeoutOrNull(15_000L) {
                    j1.join()
                    j2.join()
                }
            }
        } finally {
            try { s1.close() } catch (_: Exception) {}
            try { s2.close() } catch (_: Exception) {}
            j1.cancel()
            j2.cancel()
        }
    }
}

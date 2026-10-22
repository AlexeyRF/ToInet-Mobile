package ru.toinet.android.vpn.rules

import android.content.Context
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import ru.toinet.android.util.Prefs
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
    private var serverJob: Job? = null

    fun start() {
        if (isRunning.getAndSet(true)) return

        serverJob = scope.launch(Dispatchers.IO) {
            try {
                val s = ServerSocket()
                s.reuseAddress = true
                s.bind(InetSocketAddress("127.0.0.1", ROUTER_PORT))
                serverSocket = s
                Log.i(TAG, "AppRoutingServer started on 127.0.0.1:$ROUTER_PORT")

                while (isActive && isRunning.get()) {
                    try {
                        val client = s.accept()
                        scope.launch(Dispatchers.IO) {
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
        serverJob?.cancel()
        serverJob = null
        Log.i(TAG, "AppRoutingServer stopped")
    }

    private suspend fun handleClient(clientSocket: Socket) {
        var upstreamSocket: Socket? = null
        try {
            clientSocket.tcpNoDelay = true
            clientSocket.keepAlive = true
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

            // Resolve target application UID
            val targetAction = resolveAction(srcIp, srcPort, dstHost, dstPort)

            when (targetAction) {
                ProxifierRule.ACTION_BLOCK -> {
                    // 0x02 = Connection not allowed by ruleset
                    clientOut.write(byteArrayOf(5, 2, 0, 1, 0, 0, 0, 0, 0, 0))
                    clientOut.flush()
                    clientSocket.close()
                    return
                }
                ProxifierRule.ACTION_DIRECT -> {
                    // Direct connection, protected from VPN
                    val directSocket = Socket()
                    vpnService.protect(directSocket)
                    directSocket.tcpNoDelay = true
                    directSocket.keepAlive = true
                    try {
                        withContext(Dispatchers.IO) {
                            directSocket.connect(InetSocketAddress(dstHost, dstPort), 10000)
                        }
                    } catch (e: Exception) {
                        try {
                            clientOut.write(byteArrayOf(5, 4, 0, 1, 0, 0, 0, 0, 0, 0)) // Host unreachable
                            clientOut.flush()
                        } catch (_: Exception) {}
                        clientSocket.close()
                        return
                    }
                    upstreamSocket = directSocket
                    // Success response to tun2socks (standard IPv4 0.0.0.0:0)
                    clientOut.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
                    clientOut.flush()

                    pipeSockets(clientSocket, directSocket)
                }
                else -> {
                    // SOCKS5 upstream proxy
                    val proxyPort = getProxyPort(targetAction)
                    val proxySocket = Socket()
                    proxySocket.tcpNoDelay = true
                    proxySocket.keepAlive = true
                    try {
                        withContext(Dispatchers.IO) {
                            proxySocket.connect(InetSocketAddress("127.0.0.1", proxyPort), 5000)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to connect to proxy $targetAction on port $proxyPort", e)
                        try {
                            clientOut.write(byteArrayOf(5, 4, 0, 1, 0, 0, 0, 0, 0, 0))
                            clientOut.flush()
                        } catch (_: Exception) {}
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
                        clientOut.write(byteArrayOf(5, 1, 0, 1, 0, 0, 0, 0, 0, 0))
                        clientOut.flush()
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
                    val addrLen = when (upRespHeader[3].toInt()) {
                        1 -> 4 + 2 // IPv4
                        4 -> 16 + 2 // IPv6
                        3 -> { // Domain
                            val dlen = upIn.readUnsignedByte()
                            dlen + 2
                        }
                        else -> 6
                    }
                    val discardBuf = ByteArray(addrLen)
                    upIn.readFully(discardBuf)

                    if (upRespHeader[1].toInt() != 0) {
                        clientOut.write(byteArrayOf(5, upRespHeader[1], 0, 1, 0, 0, 0, 0, 0, 0))
                        clientOut.flush()
                        clientSocket.close()
                        proxySocket.close()
                        return
                    }

                    // Success response to tun2socks (standard IPv4 0.0.0.0:0)
                    clientOut.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
                    clientOut.flush()

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
                        val packages = vpnService.packageManager.getPackagesForUid(uid)
                        if (packages != null) {
                            for (pkg in packages) {
                                val rule = ProxifierRulesManager.findRuleForPackage(pkg)
                                if (rule != null) {
                                    return rule.action
                                }
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
                } catch (_: Exception) { 1080 }
            }
            ProxifierRule.ACTION_EASYVPN -> Prefs.easyVpnProxyPort
            ProxifierRule.ACTION_TOR -> 5242
            ProxifierRule.ACTION_ZAPRET -> Prefs.zapretPort
            ProxifierRule.ACTION_OPERAPROXY -> {
                Prefs.operaProxyBindAddress.split(":").last().toIntOrNull() ?: 1888
            }
            ProxifierRule.ACTION_REHAB -> 1788
            ProxifierRule.ACTION_FAKEVPN -> 1790
            else -> 1080
        }
    }

    private suspend fun pipeSockets(s1: Socket, s2: Socket) = coroutineScope {
        val j1 = launch(Dispatchers.IO) {
            var normalEof = false
            try {
                val input: InputStream = s1.getInputStream()
                val output: OutputStream = s2.getOutputStream()
                val buffer = ByteArray(16384)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    output.flush()
                }
                normalEof = true
                try { s2.shutdownOutput() } catch (_: Exception) {}
            } catch (_: Exception) {
            } finally {
                if (!normalEof) {
                    try { s2.close() } catch (_: Exception) {}
                }
            }
        }

        val j2 = launch(Dispatchers.IO) {
            try {
                val input: InputStream = s2.getInputStream()
                val output: OutputStream = s1.getOutputStream()
                val buffer = ByteArray(16384)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    output.flush()
                }
                try { s1.shutdownOutput() } catch (_: Exception) {}
            } catch (_: Exception) {
            } finally {
                try { s1.close() } catch (_: Exception) {}
                try { s2.close() } catch (_: Exception) {}
            }
        }

        try {
            j2.join()
        } finally {
            try { s1.close() } catch (_: Exception) {}
            try { s2.close() } catch (_: Exception) {}
            j1.cancel()
        }
    }
}

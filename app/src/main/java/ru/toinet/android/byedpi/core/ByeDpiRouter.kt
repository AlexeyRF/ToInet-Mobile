package ru.toinet.android.byedpi.core

import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONObject
import ru.toinet.android.byedpi.services.*
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer

object ByeDpiRouter {
    private var serverSocket: ServerSocket? = null
    private var routerJob: Job? = null
    private val instances = listOf(
        ByeDpiInstance1Service::class.java,
        ByeDpiInstance2Service::class.java,
        ByeDpiInstance3Service::class.java,
        ByeDpiInstance4Service::class.java,
        ByeDpiInstance5Service::class.java
    )
    private var usedInstances = 0
    private val domainToPort = mutableMapOf<String, Int>()
    private var defaultPort = 11081

    fun start(context: Context, jsonStr: String, listenPort: Int, bindIp: String) {
        if (routerJob != null) return

        try {
            val jsonObj = JSONObject(jsonStr)
            val uniqueCmds = mutableSetOf<String>()
            val domainToCmd = mutableMapOf<String, String>()
            
            val keys = jsonObj.keys()
            while (keys.hasNext()) {
                val domain = keys.next()
                val cmd = jsonObj.getString(domain)
                uniqueCmds.add(cmd)
                domainToCmd[domain] = cmd
            }

            val cmdToPort = mutableMapOf<String, Int>()
            var currentPort = 11081
            var launchedCount = 0

            for (cmd in uniqueCmds) {
                if (cmd.equals("TOR", ignoreCase = true)) {
                    cmdToPort[cmd] = 5242
                    continue
                }
                
                if (launchedCount < instances.size) {
                    val port = currentPort++
                    cmdToPort[cmd] = port
                    
                    val intent = Intent(context, instances[launchedCount])
                    intent.action = "START"
                    intent.putExtra("CMD", cmd)
                    intent.putExtra("PORT", port)
                    context.startService(intent)
                    launchedCount++
                }
            }
            usedInstances = launchedCount

            domainToPort.clear()
            domainToCmd.forEach { (domain, cmd) ->
                cmdToPort[cmd]?.let { port ->
                    domainToPort[domain] = port
                }
            }
            defaultPort = cmdToPort.values.firstOrNull() ?: 11081

            routerJob = CoroutineScope(Dispatchers.IO).launch {
                try {
                    serverSocket = ServerSocket(listenPort, 50, java.net.InetAddress.getByName(bindIp))
                    while (isActive) {
                        val clientSocket = serverSocket?.accept() ?: break
                        launch { handleClient(clientSocket) }
                    }
                } catch (e: Exception) {
                    Log.e("ByeDpiRouter", "Router Server error", e)
                }
            }

        } catch (e: Exception) {
            Log.e("ByeDpiRouter", "Failed to parse JSON", e)
        }
    }

    fun stop(context: Context) {
        routerJob?.cancel()
        routerJob = null
        try { serverSocket?.close() } catch (e: Exception) {}
        serverSocket = null

        for (i in 0 until usedInstances) {
            val intent = Intent(context, instances[i])
            intent.action = "STOP"
            context.startService(intent)
        }
        usedInstances = 0
    }

    private suspend fun handleClient(clientSocket: Socket) {
        var upSocket: Socket? = null
        try {
            withContext(Dispatchers.IO) {
                val input = clientSocket.getInputStream()
                val output = clientSocket.getOutputStream()

                val greeting = ByteArray(2)
                readExactly(input, greeting)
                if (greeting[0] != 0x05.toByte()) return@withContext
                val nmethods = greeting[1].toInt()
                readExactly(input, ByteArray(nmethods))

                output.write(byteArrayOf(0x05, 0x00))
                output.flush()

                val reqHeader = ByteArray(4)
                readExactly(input, reqHeader)
                if (reqHeader[1] != 0x01.toByte()) return@withContext

                val atyp = reqHeader[3].toInt()
                var targetDomain = ""
                val dstAddrBytes = when (atyp) {
                    1 -> {
                        val ip = ByteArray(4)
                        readExactly(input, ip)
                        ip
                    }
                    3 -> {
                        val len = input.read()
                        val domainBytes = ByteArray(len)
                        readExactly(input, domainBytes)
                        targetDomain = String(domainBytes)
                        byteArrayOf(len.toByte()) + domainBytes
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

                val reply = byteArrayOf(0x05, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
                output.write(reply)
                output.flush()

                val firstChunk = ByteArray(16384)
                var bytesRead = 0
                clientSocket.soTimeout = 500
                try {
                    bytesRead = input.read(firstChunk)
                } catch (e: Exception) {}
                clientSocket.soTimeout = 0

                if (bytesRead <= 0) return@withContext

                if (targetDomain.isEmpty()) {
                    val extracted = extractDomain(firstChunk, bytesRead)
                    if (extracted != null) targetDomain = extracted
                }

                var targetPort = defaultPort
                if (targetDomain.isNotEmpty()) {
                    val match = domainToPort.entries.find { 
                        targetDomain.equals(it.key, ignoreCase = true) || 
                        targetDomain.endsWith("." + it.key, ignoreCase = true) 
                    }
                    if (match != null) {
                        targetPort = match.value
                    }
                }

                upSocket = Socket()
                upSocket.connect(InetSocketAddress("127.0.0.1", targetPort), 5000)
                val upInput = upSocket.getInputStream()
                val upOutput = upSocket.getOutputStream()

                upOutput.write(byteArrayOf(0x05, 0x01, 0x00))
                upOutput.flush()
                readExactly(upInput, ByteArray(2))

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

                upOutput.write(firstChunk, 0, bytesRead)
                upOutput.flush()

                val t1 = async { forward(input, upOutput) }
                val t2 = async { forward(upInput, output) }
                try { awaitAll(t1, t2) } catch (e: Exception) { t1.cancel(); t2.cancel() }
            }
        } catch (e: Exception) {} finally {
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

    private suspend fun forward(input: InputStream, output: OutputStream) {
        withContext(Dispatchers.IO) {
            val buffer = ByteArray(16384)
            try {
                while (isActive) {
                    val r = input.read(buffer)
                    if (r <= 0) break
                    output.write(buffer, 0, r)
                    output.flush()
                }
            } catch (e: Exception) {}
        }
    }

    private fun extractDomain(firstChunk: ByteArray, length: Int): String? {
        if (length < 16) return null
        
        val method = String(firstChunk, 0, minOf(8, length))
        if (method.startsWith("GET ") || method.startsWith("POST ") || method.startsWith("CONNECT ") || 
            method.startsWith("PUT ") || method.startsWith("HEAD ") || method.startsWith("OPTIONS ")) {
            val httpData = String(firstChunk, 0, length)
            val hostLine = httpData.lines().find { it.startsWith("Host: ", ignoreCase = true) }
            return hostLine?.substring(6)?.trim()?.substringBefore(":")
        }

        if (firstChunk[0] == 0x16.toByte() && firstChunk[1] == 0x03.toByte() && firstChunk[5] == 0x01.toByte()) {
            try {
                var offset = 43
                if (offset + 1 > length) return null
                val sessionIdLen = firstChunk[offset].toInt() and 0xFF
                offset += 1 + sessionIdLen
                if (offset + 2 > length) return null
                val cipherSuitesLen = ((firstChunk[offset].toInt() and 0xFF) shl 8) or (firstChunk[offset+1].toInt() and 0xFF)
                offset += 2 + cipherSuitesLen
                if (offset + 1 > length) return null
                val compMethodsLen = firstChunk[offset].toInt() and 0xFF
                offset += 1 + compMethodsLen
                if (offset + 2 > length) return null
                val extensionsLen = ((firstChunk[offset].toInt() and 0xFF) shl 8) or (firstChunk[offset+1].toInt() and 0xFF)
                offset += 2
                
                val endOffset = minOf(offset + extensionsLen, length)
                while (offset + 4 <= endOffset) {
                    val extType = ((firstChunk[offset].toInt() and 0xFF) shl 8) or (firstChunk[offset+1].toInt() and 0xFF)
                    val extLen = ((firstChunk[offset+2].toInt() and 0xFF) shl 8) or (firstChunk[offset+3].toInt() and 0xFF)
                    offset += 4
                    if (extType == 0 && offset + extLen <= endOffset) {
                        var sniOffset = offset + 2
                        if (sniOffset + 1 <= endOffset) {
                            val nameType = firstChunk[sniOffset].toInt() and 0xFF
                            if (nameType == 0) {
                                sniOffset += 1
                                if (sniOffset + 2 <= endOffset) {
                                    val nameLen = ((firstChunk[sniOffset].toInt() and 0xFF) shl 8) or (firstChunk[sniOffset+1].toInt() and 0xFF)
                                    sniOffset += 2
                                    if (sniOffset + nameLen <= endOffset) {
                                        return String(firstChunk, sniOffset, nameLen)
                                    }
                                }
                            }
                        }
                    }
                    offset += extLen
                }
            } catch (e: Exception) {}
        }
        return null
    }
}

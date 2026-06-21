package ru.toinet.android.rehabilitator

import android.util.Log
import kotlinx.coroutines.*
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.InetAddress
import java.net.InetSocketAddress

class Socks5Connection(
    private val clientSock: Socket,
    private val byedpiAddr: InetSocketAddress,
    private val username: String?,
    private val password: String?
) {
    companion object {
        private const val TAG = "RehabilitatorConnection"
    }

    suspend fun handle() = withContext(Dispatchers.IO) {
        try {
            clientSock.soTimeout = 0
            val clientIn = clientSock.getInputStream()
            val clientOut = clientSock.getOutputStream()

            // 1. Greeting
            val header = ByteArray(2)
            if (clientIn.read(header) < 2) return@withContext
            if (header[0] != 5.toByte()) return@withContext
            val nmethods = header[1].toInt()
            val methods = ByteArray(nmethods)
            if (clientIn.read(methods) < nmethods) return@withContext

            clientOut.write(byteArrayOf(5, 0))
            clientOut.flush()

            // 2. Client Request
            val req = ByteArray(4)
            if (clientIn.read(req) < 4) return@withContext
            if (req[0] != 5.toByte() || req[1] != 1.toByte()) {
                sendReply(clientOut, 0x07)
                return@withContext
            }
            val atyp = req[3].toInt()
            val addrBytes: ByteArray
            val hostStr: String
            when (atyp) {
                1 -> {
                    addrBytes = ByteArray(4)
                    clientIn.read(addrBytes)
                    hostStr = InetAddress.getByAddress(addrBytes).hostAddress ?: ""
                }
                3 -> {
                    val dlen = clientIn.read()
                    val domainBytes = ByteArray(dlen)
                    clientIn.read(domainBytes)
                    addrBytes = byteArrayOf(dlen.toByte()) + domainBytes
                    hostStr = String(domainBytes)
                }
                4 -> {
                    addrBytes = ByteArray(16)
                    clientIn.read(addrBytes)
                    hostStr = InetAddress.getByAddress(addrBytes).hostAddress ?: ""
                }
                else -> {
                    sendReply(clientOut, 0x08)
                    return@withContext
                }
            }
            val portBytes = ByteArray(2)
            clientIn.read(portBytes)
            val port = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)

            Log.d(TAG, "Client request CONNECT $hostStr:$port")

            // 3. Connect to ByeDPI
            val byedpiSock = Socket()
            try {
                byedpiSock.connect(byedpiAddr, 5000)
                byedpiSock.soTimeout = 0
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect to ByeDPI daemon", e)
                sendReply(clientOut, 0x05)
                return@withContext
            }

            val bdIn = byedpiSock.getInputStream()
            val bdOut = byedpiSock.getOutputStream()

            // 4. Connect to upstream SOCKS5 via ByeDPI
            try {
                if (!username.isNullOrEmpty() && !password.isNullOrEmpty()) {
                    bdOut.write(byteArrayOf(5, 2, 0, 2))
                    bdOut.flush()
                    val resp = ByteArray(2)
                    bdIn.read(resp)
                    if (resp[0] != 5.toByte()) throw RuntimeException("Upstream SOCKS5 handshake failed")
                    if (resp[1] == 2.toByte()) {
                        val uBytes = username.toByteArray()
                        val pBytes = password.toByteArray()
                        val authReq = ByteArray(1 + 1 + uBytes.size + 1 + pBytes.size)
                        authReq[0] = 1
                        authReq[1] = uBytes.size.toByte()
                        System.arraycopy(uBytes, 0, authReq, 2, uBytes.size)
                        authReq[2 + uBytes.size] = pBytes.size.toByte()
                        System.arraycopy(pBytes, 0, authReq, 3 + uBytes.size, pBytes.size)
                        bdOut.write(authReq)
                        bdOut.flush()
                        val authResp = ByteArray(2)
                        bdIn.read(authResp)
                        if (authResp[0] != 1.toByte() || authResp[1] != 0.toByte()) {
                            throw RuntimeException("Upstream SOCKS5 auth failed")
                        }
                    } else if (resp[1] != 0.toByte()) {
                        throw RuntimeException("Upstream SOCKS5 requires unsupported auth method")
                    }
                } else {
                    bdOut.write(byteArrayOf(5, 1, 0))
                    bdOut.flush()
                    val resp = ByteArray(2)
                    bdIn.read(resp)
                    if (resp[0] != 5.toByte() || resp[1] != 0.toByte()) {
                        throw RuntimeException("Upstream SOCKS5 handshake failed (no auth)")
                    }
                }

                val connReq = ByteArray(4)
                connReq[0] = 5
                connReq[1] = 1
                connReq[2] = 0
                connReq[3] = atyp.toByte()
                bdOut.write(connReq)
                bdOut.write(addrBytes)
                bdOut.write(portBytes)
                bdOut.flush()

                // Read reply
                val upstreamReplyHeader = ByteArray(4)
                if (bdIn.read(upstreamReplyHeader) < 4) throw RuntimeException("Upstream proxy dropped")
                if (upstreamReplyHeader[1] != 0.toByte()) {
                    throw RuntimeException("Upstream proxy rejected connection")
                }
                val rAtyp = upstreamReplyHeader[3].toInt()
                val rAddrBytes: ByteArray
                when (rAtyp) {
                    1 -> rAddrBytes = ByteArray(4)
                    3 -> {
                        val rLen = bdIn.read()
                        rAddrBytes = ByteArray(rLen)
                    }
                    4 -> rAddrBytes = ByteArray(16)
                    else -> throw RuntimeException("Unknown atyp from upstream")
                }
                bdIn.read(rAddrBytes)
                val rPortBytes = ByteArray(2)
                bdIn.read(rPortBytes)

            } catch (e: Exception) {
                Log.e(TAG, "Failed upstream SOCKS5 via ByeDPI", e)
                sendReply(clientOut, 0x05)
                byedpiSock.close()
                return@withContext
            }

            // 5. Send success to client
            val reply = byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0)
            clientOut.write(reply)
            clientOut.flush()

            // 6. Pipe
            val p1 = launch { pipe(clientIn, bdOut) }
            val p2 = launch { pipe(bdIn, clientOut) }
            p1.join()
            p2.join()

            byedpiSock.close()

        } catch (e: Exception) {
            // connection dropped
        } finally {
            clientSock.close()
        }
    }

    private fun sendReply(out: OutputStream, rep: Int) {
        try {
            out.write(byteArrayOf(5, rep.toByte(), 0, 1, 0, 0, 0, 0, 0, 0))
            out.flush()
        } catch (e: Exception) {}
    }

    private fun pipe(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(8192)
        try {
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                output.write(buffer, 0, read)
                output.flush()
            }
        } catch (e: Exception) {
        } finally {
            try { input.close() } catch (e: Exception) {}
            try { output.close() } catch (e: Exception) {}
        }
    }
}

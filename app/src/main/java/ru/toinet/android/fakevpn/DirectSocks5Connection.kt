package ru.toinet.android.fakevpn

import android.util.Log
import kotlinx.coroutines.*
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.InetAddress
import java.net.InetSocketAddress

class DirectSocks5Connection(
    private val clientSock: Socket
) {
    companion object {
        private const val TAG = "FakeVpnConnection"
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
            val hostStr: String
            when (atyp) {
                1 -> {
                    val addrBytes = ByteArray(4)
                    clientIn.read(addrBytes)
                    hostStr = InetAddress.getByAddress(addrBytes).hostAddress ?: ""
                }
                3 -> {
                    val dlen = clientIn.read()
                    val domainBytes = ByteArray(dlen)
                    clientIn.read(domainBytes)
                    hostStr = String(domainBytes)
                }
                4 -> {
                    val addrBytes = ByteArray(16)
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

            Log.d(TAG, "FakeVPN connecting directly to $hostStr:$port")

            // 3. Connect directly
            val remoteSock = Socket()
            try {
                remoteSock.connect(InetSocketAddress(hostStr, port), 5000)
                remoteSock.soTimeout = 0
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect directly to $hostStr:$port", e)
                sendReply(clientOut, 0x05)
                return@withContext
            }

            // 5. Send success to client
            val reply = byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0)
            clientOut.write(reply)
            clientOut.flush()

            // 6. Pipe
            val p1 = launch { pipe(clientIn, remoteSock.getOutputStream()) }
            val p2 = launch { pipe(remoteSock.getInputStream(), clientOut) }
            p1.join()
            p2.join()
            remoteSock.close()

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

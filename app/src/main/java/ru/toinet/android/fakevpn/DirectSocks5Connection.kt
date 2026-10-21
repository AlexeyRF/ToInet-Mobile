package ru.toinet.android.fakevpn

import android.util.Log
import kotlinx.coroutines.*
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.InetAddress
import java.net.InetSocketAddress
import ru.toinet.android.util.Prefs
import java.security.SecureRandom

class DirectSocks5Connection(
    private val clientSock: Socket
) {
    companion object {
        private const val TAG = "FakeVpnConnection"
    }

    private fun readExact(input: InputStream, buffer: ByteArray, length: Int = buffer.size): Boolean {
        var total = 0
        while (total < length) {
            val r = input.read(buffer, total, length - total)
            if (r == -1) return false
            total += r
        }
        return true
    }



    suspend fun handle() = withContext(Dispatchers.IO) {
        try {
            clientSock.soTimeout = 0
            val clientIn = clientSock.getInputStream()
            val clientOut = clientSock.getOutputStream()

            // 1. Greeting
            val header = ByteArray(2)
            if (!readExact(clientIn, header)) return@withContext
            if (header[0] != 5.toByte()) return@withContext
            val nmethods = header[1].toInt() and 0xFF
            val methods = ByteArray(nmethods)
            if (!readExact(clientIn, methods)) return@withContext

            clientOut.write(byteArrayOf(5, 0))
            clientOut.flush()

            // 2. Client Request
            val req = ByteArray(4)
            if (!readExact(clientIn, req)) return@withContext
            if (req[0] != 5.toByte() || req[1] != 1.toByte()) {
                sendReply(clientOut, 0x07)
                return@withContext
            }
            val atyp = req[3].toInt() and 0xFF
            val hostStr: String
            when (atyp) {
                1 -> {
                    val addrBytes = ByteArray(4)
                    if (!readExact(clientIn, addrBytes)) return@withContext
                    hostStr = InetAddress.getByAddress(addrBytes).hostAddress ?: ""
                }
                3 -> {
                    val dlen = clientIn.read()
                    if (dlen <= 0) return@withContext
                    val domainBytes = ByteArray(dlen)
                    if (!readExact(clientIn, domainBytes)) return@withContext
                    hostStr = String(domainBytes)
                }
                4 -> {
                    val addrBytes = ByteArray(16)
                    if (!readExact(clientIn, addrBytes)) return@withContext
                    hostStr = InetAddress.getByAddress(addrBytes).hostAddress ?: ""
                }
                else -> {
                    sendReply(clientOut, 0x08)
                    return@withContext
                }
            }
            val portBytes = ByteArray(2)
            if (!readExact(clientIn, portBytes)) return@withContext
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
            val fakeTlsEnabled = Prefs.fakeVpnFakeTlsEnabled && port == 443
            val fakeTlsDomains = Prefs.fakeVpnFakeTlsDomains.split(" ", "\n", "\r").filter { it.isNotBlank() }

            var p1: Job? = null
            var p2: Job? = null
            p1 = launch {
                try {
                    if (fakeTlsEnabled && fakeTlsDomains.isNotEmpty()) {
                        pipeWithFakeTls(clientIn, remoteSock.getOutputStream(), fakeTlsDomains)
                    } else {
                        pipe(clientIn, remoteSock.getOutputStream())
                    }
                } finally {
                    try { remoteSock.close() } catch (_: Exception) {}
                    p2?.cancel()
                }
            }
            p2 = launch {
                try {
                    pipe(remoteSock.getInputStream(), clientOut)
                } finally {
                    try { clientSock.close() } catch (_: Exception) {}
                    p1?.cancel()
                }
            }
            p1.join()
            p2.join()
            try { remoteSock.close() } catch (_: Exception) {}

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

    private fun pipeWithFakeTls(input: InputStream, output: OutputStream, fakeDomains: List<String>) {
        val buffer = ByteArray(16384)
        var firstChunk = true
        try {
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                var toWrite = buffer
                var toWriteLen = read
                
                if (firstChunk) {
                    firstChunk = false
                    val randomDomain = fakeDomains[SecureRandom().nextInt(fakeDomains.size)]
                    val modified = SniSpoofer.spoofSni(buffer.copyOfRange(0, read), randomDomain)
                    if (modified != null) {
                        toWrite = modified
                        toWriteLen = modified.size
                        Log.d(TAG, "FakeVPN: SNI replaced with $randomDomain")
                    } else {
                        Log.d(TAG, "FakeVPN: SNI replacement failed or not a ClientHello")
                    }
                }
                
                output.write(toWrite, 0, toWriteLen)
                output.flush()
            }
        } catch (e: Exception) {
        } finally {
            try { input.close() } catch (e: Exception) {}
            try { output.close() } catch (e: Exception) {}
        }
    }
}

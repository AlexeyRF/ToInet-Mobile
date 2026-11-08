package ru.toinet.android.tgws

import android.util.Base64
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class RawWebSocket(
    val socket: Socket,
    val inputStream: InputStream,
    val outputStream: OutputStream
) {
    @Volatile
    var isClosed = false
        private set

    private val writeLock = Any()
    private val bufferedOut = BufferedOutputStream(outputStream, 65536)

    companion object {
        const val OP_CONT = 0x00
        const val OP_TEXT = 0x01
        const val OP_BINARY = 0x02
        const val OP_CLOSE = 0x08
        const val OP_PING = 0x09
        const val OP_PONG = 0x0A

        private val TRUST_ALL_CERTS = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
        })

        private val sslContext: SSLContext by lazy {
            SSLContext.getInstance("TLS").apply {
                init(null, TRUST_ALL_CERTS, SecureRandom())
            }
        }

        fun connect(
            host: String,
            domain: String,
            timeoutMs: Int = 10000,
            path: String = "/apiws",
            sni: String? = null,
            secure: Boolean = true,
            bufferSize: Int = 256 * 1024,
            proxy: java.net.Proxy? = null
        ): RawWebSocket {
            val port = if (secure) 443 else 80
            val targetSni = sni ?: domain

            val rawSocket = if (proxy != null) Socket(proxy) else Socket()
            rawSocket.tcpNoDelay = true
            try {
                rawSocket.receiveBufferSize = bufferSize
                rawSocket.sendBufferSize = bufferSize
            } catch (_: Exception) {}

            rawSocket.connect(InetSocketAddress(host, port), timeoutMs)
            rawSocket.soTimeout = timeoutMs

            val finalSocket: Socket
            if (secure) {
                val sslSocket = sslContext.socketFactory.createSocket(
                    rawSocket,
                    targetSni,
                    port,
                    true
                ) as SSLSocket

                try {
                    val sslParams = sslSocket.sslParameters
                    sslParams.serverNames = listOf(javax.net.ssl.SNIHostName(targetSni))
                    sslSocket.sslParameters = sslParams
                } catch (_: Exception) {}

                sslSocket.startHandshake()
                finalSocket = sslSocket
            } else {
                finalSocket = rawSocket
            }

            finalSocket.tcpNoDelay = true
            val inStream = finalSocket.getInputStream()
            val outStream = finalSocket.getOutputStream()

            val randomBytes = ByteArray(16)
            SecureRandom().nextBytes(randomBytes)
            val wsKey = Base64.encodeToString(randomBytes, Base64.NO_WRAP)

            val request = "GET $path HTTP/1.1\r\n" +
                    "Host: $domain\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Key: $wsKey\r\n" +
                    "Sec-WebSocket-Version: 13\r\n" +
                    "Sec-WebSocket-Protocol: binary\r\n" +
                    "Origin: https://web.telegram.org\r\n" +
                    "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36\r\n" +
                    "\r\n"

            outStream.write(request.toByteArray(Charsets.US_ASCII))
            outStream.flush()

            val headerBuf = ByteArray(8192)
            var pos = 0
            var headerEnd = -1
            while (pos < headerBuf.size) {
                val b = inStream.read()
                if (b == -1) {
                    finalSocket.close()
                    throw IOException("Connection closed during WS handshake")
                }
                headerBuf[pos++] = b.toByte()
                if (pos >= 4 &&
                    headerBuf[pos - 4] == '\r'.toByte() &&
                    headerBuf[pos - 3] == '\n'.toByte() &&
                    headerBuf[pos - 2] == '\r'.toByte() &&
                    headerBuf[pos - 1] == '\n'.toByte()
                ) {
                    headerEnd = pos
                    break
                }
            }

            if (headerEnd == -1) {
                finalSocket.close()
                throw IOException("WS handshake headers exceeded buffer limit")
            }

            val headersStr = String(headerBuf, 0, headerEnd, Charsets.US_ASCII)
            if (!headersStr.contains("101")) {
                finalSocket.close()
                throw IOException("WS handshake rejected: ${headersStr.substringBefore("\r\n")}")
            }

            finalSocket.soTimeout = 0
            return RawWebSocket(finalSocket, inStream, outStream)
        }
    }

    fun send(data: ByteArray, opcode: Int = OP_BINARY) {
        if (isClosed) return
        val frame = buildMaskedFrame(data, opcode)
        synchronized(writeLock) {
            if (!isClosed) {
                bufferedOut.write(frame)
                bufferedOut.flush()
            }
        }
    }

    fun sendBatch(parts: List<ByteArray>, opcode: Int = OP_BINARY) {
        if (isClosed || parts.isEmpty()) return
        synchronized(writeLock) {
            if (!isClosed) {
                for (part in parts) {
                    val frame = buildMaskedFrame(part, opcode)
                    bufferedOut.write(frame)
                }
                bufferedOut.flush()
            }
        }
    }

    private fun buildMaskedFrame(data: ByteArray, opcode: Int): ByteArray {
        val len = data.size
        var headerLen = 2
        if (len < 126) {
        } else if (len < 65536) {
            headerLen += 2
        } else {
            headerLen += 8
        }
        headerLen += 4 // 4-byte mask key

        val frame = ByteArray(headerLen + len)
        frame[0] = (0x80 or (opcode and 0x0F)).toByte()

        val mask = ByteArray(4)
        SecureRandom().nextBytes(mask)
        val maskInt = (mask[0].toInt() and 0xFF) or
                ((mask[1].toInt() and 0xFF) shl 8) or
                ((mask[2].toInt() and 0xFF) shl 16) or
                ((mask[3].toInt() and 0xFF) shl 24)

        var offset = 1
        if (len < 126) {
            frame[offset++] = (0x80 or len).toByte()
        } else if (len < 65536) {
            frame[offset++] = (0x80 or 126).toByte()
            frame[offset++] = (len ushr 8).toByte()
            frame[offset++] = (len and 0xFF).toByte()
        } else {
            frame[offset++] = (0x80 or 127).toByte()
            for (i in 7 downTo 0) {
                frame[offset++] = ((len.toLong() ushr (i * 8)) and 0xFF).toByte()
            }
        }

        System.arraycopy(mask, 0, frame, offset, 4)
        offset += 4

        if (TgwsAccel.isAvailable) {
            TgwsAccel.nativeWsMaskCopy(data, frame.copyOfRange(offset, offset + len), len, maskInt)
            // copy masked into frame
            val maskedSlice = ByteArray(len)
            TgwsAccel.nativeWsMaskCopy(data, maskedSlice, len, maskInt)
            System.arraycopy(maskedSlice, 0, frame, offset, len)
        } else {
            for (i in 0 until len) {
                frame[offset + i] = (data[i].toInt() xor mask[i % 4].toInt()).toByte()
            }
        }

        return frame
    }

    fun recv(): ByteArray? {
        while (!isClosed) {
            val b0 = inputStream.read()
            if (b0 == -1) {
                close()
                return null
            }
            val b1 = inputStream.read()
            if (b1 == -1) {
                close()
                return null
            }

            val opcode = b0 and 0x0F
            val isMasked = (b1 and 0x80) != 0
            var payloadLen = (b1 and 0x7F).toLong()

            if (payloadLen == 126L) {
                val ext = ByteArray(2)
                if (!readFully(ext)) {
                    close()
                    return null
                }
                payloadLen = ((ext[0].toInt() and 0xFF) shl 8 or (ext[1].toInt() and 0xFF)).toLong()
            } else if (payloadLen == 127L) {
                val ext = ByteArray(8)
                if (!readFully(ext)) {
                    close()
                    return null
                }
                payloadLen = 0L
                for (i in 0..7) {
                    payloadLen = (payloadLen shl 8) or (ext[i].toLong() and 0xFF)
                }
            }

            val maskKey = if (isMasked) {
                val key = ByteArray(4)
                if (!readFully(key)) {
                    close()
                    return null
                }
                key
            } else null

            val payload = ByteArray(payloadLen.toInt())
            if (!readFully(payload)) {
                close()
                return null
            }

            if (maskKey != null) {
                if (TgwsAccel.isAvailable) {
                    val maskInt = (maskKey[0].toInt() and 0xFF) or
                            ((maskKey[1].toInt() and 0xFF) shl 8) or
                            ((maskKey[2].toInt() and 0xFF) shl 16) or
                            ((maskKey[3].toInt() and 0xFF) shl 24)
                    TgwsAccel.nativeWsMaskInplace(payload, payload.size, maskInt)
                } else {
                    for (i in payload.indices) {
                        payload[i] = (payload[i].toInt() xor maskKey[i % 4].toInt()).toByte()
                    }
                }
            }

            when (opcode) {
                OP_CLOSE -> {
                    close()
                    return null
                }
                OP_PING -> {
                    try {
                        send(payload, OP_PONG)
                    } catch (_: Exception) {}
                    continue
                }
                OP_PONG -> {
                    continue
                }
                OP_BINARY, OP_TEXT, OP_CONT -> {
                    return payload
                }
                else -> continue
            }
        }
        return null
    }

    private fun readFully(buf: ByteArray): Boolean {
        var read = 0
        while (read < buf.size) {
            val n = inputStream.read(buf, read, buf.size - read)
            if (n == -1) return false
            read += n
        }
        return true
    }

    fun close() {
        if (!isClosed) {
            isClosed = true
            try {
                socket.close()
            } catch (_: Exception) {}
        }
    }
}

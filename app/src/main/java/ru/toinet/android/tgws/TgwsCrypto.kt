package ru.toinet.android.tgws

import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class CryptoCtx(
    val clientDec: Cipher,
    val clientEnc: Cipher,
    val tgEnc: Cipher,
    val tgDec: Cipher
)

data class HandshakeResult(
    val dcId: Int,
    val isMedia: Boolean,
    val protoTag: ByteArray,
    val clientDecPrekeyIv: ByteArray
)

data class FakeTlsResult(
    val clientRandom: ByteArray,
    val sessionId: ByteArray,
    val timestamp: Long
)

object TgwsCrypto {

    fun tryHandshake(handshake: ByteArray, secret: ByteArray): HandshakeResult? {
        if (handshake.size < 64) return null
        try {
            val decPrekeyAndIv = handshake.sliceArray(8 until 56)
            val decPrekey = decPrekeyAndIv.sliceArray(0 until 32)
            val decIv = decPrekeyAndIv.sliceArray(32 until 48)

            val md = MessageDigest.getInstance("SHA-256")
            md.update(decPrekey)
            md.update(secret)
            val decKey = md.digest()

            val decryptor = Cipher.getInstance("AES/CTR/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(decKey, "AES"), IvParameterSpec(decIv))
            }
            val decrypted = decryptor.update(handshake) ?: return null
            if (decrypted.size < 62) return null

            val protoTag = decrypted.sliceArray(56 until 60)
            val protoInt = ByteBuffer.wrap(protoTag).order(ByteOrder.LITTLE_ENDIAN).int
            if (protoInt != TgwsConfig.PROTO_ABRIDGED_INT &&
                protoInt != TgwsConfig.PROTO_INTERMEDIATE_INT &&
                protoInt != TgwsConfig.PROTO_PADDED_INTERMEDIATE_INT
            ) {
                return null
            }

            val dcIdx = ByteBuffer.wrap(decrypted.sliceArray(60 until 62)).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
            val dcId = Math.abs(dcIdx)
            val isMedia = dcIdx < 0

            return HandshakeResult(dcId, isMedia, protoTag, decPrekeyAndIv)
        } catch (_: Exception) {
            return null
        }
    }

    fun generateRelayInit(protoTag: ByteArray, dcIdx: Int): ByteArray {
        val rnd = ByteArray(64)
        val rndGen = SecureRandom()
        while (true) {
            rndGen.nextBytes(rnd)
            if (rnd[0] in TgwsConfig.RESERVED_FIRST_BYTES) continue
            val first4Str = String(rnd, 0, 4, Charsets.ISO_8859_1)
            if (first4Str in TgwsConfig.RESERVED_STARTS) continue
            val first4Int = ByteBuffer.wrap(rnd, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (first4Int == TgwsConfig.PROTO_ABRIDGED_INT ||
                first4Int == TgwsConfig.PROTO_INTERMEDIATE_INT ||
                first4Int == TgwsConfig.PROTO_PADDED_INTERMEDIATE_INT
            ) continue
            val second4Int = ByteBuffer.wrap(rnd, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (second4Int == 0) continue
            break
        }

        val encKey = rnd.sliceArray(8 until 40)
        val encIv = rnd.sliceArray(40 until 56)

        val encryptor = Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(encKey, "AES"), IvParameterSpec(encIv))
        }

        val encryptedFull = encryptor.doFinal(rnd)
        val keystreamTail = ByteArray(8)
        for (i in 0 until 8) {
            keystreamTail[i] = (encryptedFull[56 + i].toInt() xor rnd[56 + i].toInt()).toByte()
        }

        val dcBytes = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(dcIdx.toShort()).array()
        val tailPlain = protoTag + dcBytes + byteArrayOf(rndGen.nextInt().toByte(), rndGen.nextInt().toByte())

        val encryptedTail = ByteArray(8)
        for (i in 0 until 8) {
            encryptedTail[i] = (tailPlain[i].toInt() xor keystreamTail[i].toInt()).toByte()
        }

        System.arraycopy(encryptedTail, 0, rnd, 56, 8)
        return rnd
    }

    fun buildCryptoCtx(clientDecPrekeyIv: ByteArray, secret: ByteArray, relayInit: ByteArray): CryptoCtx {
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

        val clientDec = Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(cltDecKey, "AES"), IvParameterSpec(cltDecIv))
            update(ByteArray(64))
        }

        val clientEnc = Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(cltEncKey, "AES"), IvParameterSpec(cltEncIv))
        }

        val relayEncKey = relayInit.sliceArray(8 until 40)
        val relayEncIv = relayInit.sliceArray(40 until 56)

        val relayDecPrekeyIv = relayInit.sliceArray(8 until 56).reversedArray()
        val relayDecKey = relayDecPrekeyIv.sliceArray(0 until 32)
        val relayDecIv = relayDecPrekeyIv.sliceArray(32 until 48)

        val tgEnc = Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(relayEncKey, "AES"), IvParameterSpec(relayEncIv))
            update(ByteArray(64))
        }

        val tgDec = Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(relayDecKey, "AES"), IvParameterSpec(relayDecIv))
        }

        return CryptoCtx(clientDec, clientEnc, tgEnc, tgDec)
    }

    fun verifyClientHello(data: ByteArray, secret: ByteArray): FakeTlsResult? {
        if (data.size < 43) return null
        if (data[0] != 0x16.toByte() || data[5] != 0x01.toByte()) return null

        val clientRandomOffset = 11
        val clientRandom = data.sliceArray(clientRandomOffset until clientRandomOffset + 32)
        val zeroed = data.clone()
        for (i in 0 until 32) zeroed[clientRandomOffset + i] = 0

        val mac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(secret, "HmacSHA256"))
        }
        val expected = mac.doFinal(zeroed)

        if (!MessageDigest.isEqual(expected.sliceArray(0..27), clientRandom.sliceArray(0..27))) {
            return null
        }

        val tsXor = ByteArray(4)
        for (i in 0 until 4) {
            tsXor[i] = (clientRandom[28 + i].toInt() xor expected[28 + i].toInt()).toByte()
        }
        val ts = (ByteBuffer.wrap(tsXor).order(ByteOrder.LITTLE_ENDIAN).int.toLong()) and 0xFFFFFFFFL

        val now = System.currentTimeMillis() / 1000
        if (Math.abs(now - ts) > 120) return null

        var sessionId = ByteArray(32)
        val sessionIdOffset = 44
        if (data.size >= sessionIdOffset + 32 && data[43] == 0x20.toByte()) {
            sessionId = data.sliceArray(sessionIdOffset until sessionIdOffset + 32)
        }

        return FakeTlsResult(clientRandom, sessionId, ts)
    }

    fun buildServerHello(secret: ByteArray, clientRandom: ByteArray, sessionId: ByteArray): ByteArray {
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
        val appRecord = byteArrayOf(
            0x17, 0x03, 0x03,
            (encryptedSize shr 8).toByte(),
            encryptedSize.toByte()
        ) + encryptedData

        val response = sh + ccs + appRecord

        val mac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(secret, "HmacSHA256"))
            update(clientRandom)
        }
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

    fun writeFakeTlsAppRecords(out: OutputStream, data: ByteArray) {
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
}

package ru.toinet.android.tgws

import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class MsgSplitter(relayInit: ByteArray, private val protoInt: Int) {
    private val dec: Cipher = Cipher.getInstance("AES/CTR/NoPadding")
    private val cipherBuf = mutableListOf<Byte>()
    private val plainBuf = mutableListOf<Byte>()
    private var disabled = false

    init {
        val key = relayInit.sliceArray(8 until 40)
        val iv = relayInit.sliceArray(40 until 56)
        dec.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        dec.update(ByteArray(64))
    }

    fun split(chunk: ByteArray): List<ByteArray> {
        if (chunk.isEmpty()) return emptyList()
        if (disabled) return listOf(chunk)

        val cipherList = chunk.toList()
        cipherBuf.addAll(cipherList)
        val plainBytes = dec.update(chunk)
        if (plainBytes != null) {
            plainBuf.addAll(plainBytes.toList())
        }

        val parts = mutableListOf<ByteArray>()
        var offset = 0
        val bufLen = cipherBuf.size

        while (offset < bufLen) {
            val packetLen = nextPacketLen(offset, bufLen - offset) ?: break
            if (packetLen <= 0) {
                parts.add(cipherBuf.subList(offset, bufLen).toByteArray())
                offset = bufLen
                disabled = true
                break
            }
            parts.add(cipherBuf.subList(offset, offset + packetLen).toByteArray())
            offset += packetLen
        }

        if (offset > 0) {
            cipherBuf.subList(0, offset).clear()
            plainBuf.subList(0, offset).clear()
        }
        return parts
    }

    fun flush(): List<ByteArray> {
        if (cipherBuf.isEmpty()) return emptyList()
        val tail = cipherBuf.toByteArray()
        cipherBuf.clear()
        plainBuf.clear()
        return listOf(tail)
    }

    private fun nextPacketLen(offset: Int, avail: Int): Int? {
        if (avail <= 0) return null
        if (protoInt == 0xEFEFEFEF.toInt()) return nextAbridgedLen(offset, avail)
        if (protoInt == 0xEEEEEEEE.toInt() || protoInt == 0xDDDDDDDD.toInt()) return nextIntermediateLen(offset, avail)
        return 0
    }

    private fun nextAbridgedLen(offset: Int, avail: Int): Int? {
        val first = plainBuf[offset].toInt() and 0xFF
        val payloadLen: Int
        val headerLen: Int
        if (first == 0x7F || first == 0xFF) {
            if (avail < 4) return null
            val b1 = plainBuf[offset + 1].toInt() and 0xFF
            val b2 = plainBuf[offset + 2].toInt() and 0xFF
            val b3 = plainBuf[offset + 3].toInt() and 0xFF
            payloadLen = (b1 or (b2 shl 8) or (b3 shl 16)) * 4
            headerLen = 4
        } else {
            payloadLen = (first and 0x7F) * 4
            headerLen = 1
        }
        if (payloadLen <= 0) return 0
        val packetLen = headerLen + payloadLen
        if (avail < packetLen) return null
        return packetLen
    }

    private fun nextIntermediateLen(offset: Int, avail: Int): Int? {
        if (avail < 4) return null
        val b0 = plainBuf[offset].toInt() and 0xFF
        val b1 = plainBuf[offset + 1].toInt() and 0xFF
        val b2 = plainBuf[offset + 2].toInt() and 0xFF
        val b3 = plainBuf[offset + 3].toInt() and 0xFF
        val payloadLen = (b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)) and 0x7FFFFFFF
        if (payloadLen <= 0) return 0
        val packetLen = 4 + payloadLen
        if (avail < packetLen) return null
        return packetLen
    }
}

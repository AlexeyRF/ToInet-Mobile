package ru.toinet.android.tgws

import java.io.ByteArrayOutputStream

class MsgSplitter(private val protoInt: Int) {
    private val cipherBuf = ByteArrayOutputStream(65536)
    private val plainBuf = ByteArrayOutputStream(65536)
    private var disabled = false

    @Synchronized
    fun split(chunk: ByteArray, plain: ByteArray): List<ByteArray> {
        if (chunk.isEmpty()) return emptyList()
        if (disabled) return listOf(chunk)

        cipherBuf.write(chunk)
        plainBuf.write(plain)

        val cipherBytes = cipherBuf.toByteArray()
        val plainBytes = plainBuf.toByteArray()
        val bufLen = cipherBytes.size

        val parts = mutableListOf<ByteArray>()
        var offset = 0

        if (TgwsAccel.isAvailable) {
            val maxPackets = 64
            val offsets = IntArray(maxPackets)
            val lengths = IntArray(maxPackets)
            val count = TgwsAccel.nativeFindPackets(protoInt, plainBytes, bufLen, offset, offsets, lengths, maxPackets)
            if (count == -1) {
                parts.add(cipherBytes.copyOfRange(offset, bufLen))
                offset = bufLen
                disabled = true
            } else if (count > 0) {
                for (i in 0 until count) {
                    val pOff = offsets[i]
                    val pLen = lengths[i]
                    parts.add(cipherBytes.copyOfRange(pOff, pOff + pLen))
                    offset = pOff + pLen
                }
            }
        }

        while (offset < bufLen && !disabled) {
            val packetLen = nextPacketLen(plainBytes, offset, bufLen - offset) ?: break
            if (packetLen <= 0) {
                parts.add(cipherBytes.copyOfRange(offset, bufLen))
                offset = bufLen
                disabled = true
                break
            }
            parts.add(cipherBytes.copyOfRange(offset, offset + packetLen))
            offset += packetLen
        }

        cipherBuf.reset()
        plainBuf.reset()
        if (offset < bufLen) {
            cipherBuf.write(cipherBytes, offset, bufLen - offset)
            plainBuf.write(plainBytes, offset, bufLen - offset)
        }
        return parts
    }

    @Synchronized
    fun flush(): List<ByteArray> {
        if (cipherBuf.size() == 0) return emptyList()
        val tail = cipherBuf.toByteArray()
        cipherBuf.reset()
        plainBuf.reset()
        return listOf(tail)
    }

    private fun nextPacketLen(plain: ByteArray, offset: Int, avail: Int): Int? {
        if (avail <= 0) return null
        if (protoInt == 0xEFEFEFEF.toInt()) return nextAbridgedLen(plain, offset, avail)
        if (protoInt == 0xEEEEEEEE.toInt() || protoInt == 0xDDDDDDDD.toInt()) return nextIntermediateLen(plain, offset, avail)
        return 0
    }

    private fun nextAbridgedLen(plain: ByteArray, offset: Int, avail: Int): Int? {
        val first = plain[offset].toInt() and 0xFF
        val payloadLen: Int
        val headerLen: Int
        if (first == 0x7F || first == 0xFF) {
            if (avail < 4) return null
            val b1 = plain[offset + 1].toInt() and 0xFF
            val b2 = plain[offset + 2].toInt() and 0xFF
            val b3 = plain[offset + 3].toInt() and 0xFF
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

    private fun nextIntermediateLen(plain: ByteArray, offset: Int, avail: Int): Int? {
        if (avail < 4) return null
        val b0 = plain[offset].toInt() and 0xFF
        val b1 = plain[offset + 1].toInt() and 0xFF
        val b2 = plain[offset + 2].toInt() and 0xFF
        val b3 = plain[offset + 3].toInt() and 0xFF
        val payloadLen = (b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)) and 0x7FFFFFFF
        if (payloadLen <= 0) return 0
        val packetLen = 4 + payloadLen
        if (avail < packetLen) return null
        return packetLen
    }
}

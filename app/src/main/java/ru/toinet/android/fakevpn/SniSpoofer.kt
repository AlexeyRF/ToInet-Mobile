package ru.toinet.android.fakevpn

import java.nio.ByteBuffer

object SniSpoofer {
    fun spoofSni(clientHello: ByteArray, newDomain: String): ByteArray? {
        try {
            if (clientHello.size < 43) return null
            if (clientHello[0] != 0x16.toByte()) return null // Not a handshake
            if (clientHello[5] != 0x01.toByte()) return null // Not a ClientHello

            var pos = 43
            val sessionIdLen = clientHello[pos].toInt() and 0xFF
            pos += 1 + sessionIdLen

            if (pos + 2 > clientHello.size) return null
            val cipherSuitesLen = ((clientHello[pos].toInt() and 0xFF) shl 8) or (clientHello[pos + 1].toInt() and 0xFF)
            pos += 2 + cipherSuitesLen

            if (pos + 1 > clientHello.size) return null
            val compMethodsLen = clientHello[pos].toInt() and 0xFF
            pos += 1 + compMethodsLen

            if (pos + 2 > clientHello.size) return null
            val extLenOffset = pos
            val extLen = ((clientHello[pos].toInt() and 0xFF) shl 8) or (clientHello[pos + 1].toInt() and 0xFF)
            pos += 2

            val extEnd = pos + extLen
            if (extEnd > clientHello.size) return null

            var sniExtOffset = -1
            var oldDomainLen = 0
            var oldDomainOffset = -1
            var sniListLenOffset = -1
            var sniNameLenOffset = -1

            while (pos < extEnd) {
                if (pos + 4 > extEnd) break
                val extType = ((clientHello[pos].toInt() and 0xFF) shl 8) or (clientHello[pos + 1].toInt() and 0xFF)
                val extDataLen = ((clientHello[pos + 2].toInt() and 0xFF) shl 8) or (clientHello[pos + 3].toInt() and 0xFF)
                
                if (extType == 0x0000) { // SNI
                    sniExtOffset = pos
                    var p = pos + 4
                    if (p + 2 <= pos + 4 + extDataLen) {
                        sniListLenOffset = p
                        val listLen = ((clientHello[p].toInt() and 0xFF) shl 8) or (clientHello[p + 1].toInt() and 0xFF)
                        p += 2
                        if (p + 3 <= pos + 4 + extDataLen && clientHello[p] == 0x00.toByte()) { // type host_name
                            sniNameLenOffset = p + 1
                            oldDomainLen = ((clientHello[p + 1].toInt() and 0xFF) shl 8) or (clientHello[p + 2].toInt() and 0xFF)
                            oldDomainOffset = p + 3
                        }
                    }
                    break
                }
                pos += 4 + extDataLen
            }

            if (oldDomainOffset == -1) return null

            val newDomainBytes = newDomain.toByteArray()
            val delta = newDomainBytes.size - oldDomainLen

            val out = ByteArray(clientHello.size + delta)
            
            // Copy before domain
            System.arraycopy(clientHello, 0, out, 0, oldDomainOffset)
            // Copy new domain
            System.arraycopy(newDomainBytes, 0, out, oldDomainOffset, newDomainBytes.size)
            // Copy after domain
            System.arraycopy(clientHello, oldDomainOffset + oldDomainLen, out, oldDomainOffset + newDomainBytes.size, clientHello.size - (oldDomainOffset + oldDomainLen))

            // Update lengths
            fun updateLength(offset: Int, oldLen: Int, delta: Int, bytesCount: Int = 2) {
                val newLen = oldLen + delta
                if (bytesCount == 2) {
                    out[offset] = (newLen shr 8).toByte()
                    out[offset + 1] = newLen.toByte()
                } else if (bytesCount == 3) {
                    out[offset] = (newLen shr 16).toByte()
                    out[offset + 1] = (newLen shr 8).toByte()
                    out[offset + 2] = newLen.toByte()
                }
            }

            // Name len
            updateLength(sniNameLenOffset, oldDomainLen, delta)
            
            // List len
            val oldListLen = ((clientHello[sniListLenOffset].toInt() and 0xFF) shl 8) or (clientHello[sniListLenOffset + 1].toInt() and 0xFF)
            updateLength(sniListLenOffset, oldListLen, delta)
            
            // Ext data len
            val oldExtDataLen = ((clientHello[sniExtOffset + 2].toInt() and 0xFF) shl 8) or (clientHello[sniExtOffset + 3].toInt() and 0xFF)
            updateLength(sniExtOffset + 2, oldExtDataLen, delta)
            
            // Total Ext len
            updateLength(extLenOffset, extLen, delta)
            
            // Handshake len (3 bytes)
            val oldHsLen = ((clientHello[6].toInt() and 0xFF) shl 16) or ((clientHello[7].toInt() and 0xFF) shl 8) or (clientHello[8].toInt() and 0xFF)
            updateLength(6, oldHsLen, delta, 3)
            
            // Record len
            val oldRecLen = ((clientHello[3].toInt() and 0xFF) shl 8) or (clientHello[4].toInt() and 0xFF)
            updateLength(3, oldRecLen, delta)

            return out
        } catch (e: Exception) {
            return null
        }
    }
}

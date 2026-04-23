package ru.toinet.android.service.circumvention

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Parser for bridge lines.
 */
@Serializable(with = BridgeAsStringSerializer::class)
@Suppress("MemberVisibilityCanBePrivate", "unused")
class Bridge(var raw: String) {

    class Builder(
        var transport: String? = null,
        var ip: String,
        var port: Int,
        var fingerprint1: String? = null
    ) {

        var fingerprint2: String? = null
        var url: String? = null
        var front: String? = null
        var fronts = mutableSetOf<String>()
        var cert: String? = null
        var iatMode: String? = null
        var ice: String? = null
        var utls: String? = null
        var utlsImitate: String? = null
        var ver: String? = null


        constructor(bridge: Bridge) : this(bridge.transport, bridge.ip ?: "", bridge.port ?: 0, bridge.fingerprint1) {
            if (ip.isEmpty() || port < 1)
            {
                throw RuntimeException("Tried to create Bridge.Builder with invalid bridge!")
            }

            fingerprint2 = bridge.fingerprint2

            url = bridge.url
            front = bridge.front

            bridge.fronts?.let { fronts.addAll(it) }

            cert = bridge.cert
            iatMode = bridge.iatMode
            ice = bridge.ice
            utls = bridge.utls
            utlsImitate = bridge.utlsImitate
            ver = bridge.ver
        }

        fun build(): Bridge {
            val params = mutableListOf<String>()

            transport?.let {
                if (it.isNotEmpty()) params.add(it)
            }

            params.add("$ip:$port")

            fingerprint1?.let {
                if (it.isNotEmpty()) params.add(it)
            }

            fingerprint2?.let {
                if (it.isNotEmpty()) params.add("fingerprint=$it")
            }

            url?.let {
                if (it.isNotEmpty()) params.add("url=$it")
            }

            front?.let {
                if (it.isNotEmpty()) params.add("front=$it")
            }

            fronts.filter { it.isNotEmpty() }.let {
                if (it.isNotEmpty()) params.add("fronts=${it.joinToString(",")}")
            }

            cert?.let {
                if (it.isNotEmpty()) params.add("cert=$it")
            }

            iatMode?.let {
                if (it.isNotEmpty()) params.add("iat-mode=$it")
            }

            ice?.let {
                if (it.isNotEmpty()) params.add("ice=$it")
            }

            utls?.let {
                if (it.isNotEmpty()) params.add("utls=$it")
            }

            utlsImitate?.let {
                if (it.isNotEmpty()) params.add("utls-imitate=$it")
            }

            ver?.let {
                if (it.isNotEmpty()) params.add("ver=$it")
            }

            return Bridge(params.joinToString(" "))
        }
    }

    val rawPieces: List<String>
        get() {
            val pieces = raw.split(" ").toMutableList()

            // "Vanilla" bridges (conventional relays without obfuscation) don't have a transport.
            // Add an empty one, so parsing works.
            if (pieces.size < 3) {
                pieces.add(0, "")

                return pieces
            }

            return pieces
        }

    val transport: String?
        get() {
            val transport = rawPieces.firstOrNull()

            return if (transport.isNullOrEmpty()) null else transport
        }

    val address
        get() = rawPieces.getOrNull(1)

    val ip: String?
        get() {
            var pieces = address?.split(":")
            if (pieces.isNullOrEmpty()) return null

            // Remove port.
            pieces = pieces.dropLast(1)

            // Join IPv6 again.
            return pieces.joinToString(":")
        }

    val port
        get() = address?.split(":")?.lastOrNull()?.toInt()

    val fingerprint1: String?
        get() {
            val fingerprint1 = rawPieces.getOrNull(2) ?: return null

            return if (fingerprintRegex.matches(fingerprint1)) fingerprint1 else null
        }

    val fingerprint2
        get() = rawPieces.firstOrNull { it.startsWith("fingerprint=") }
            ?.split("=")?.lastOrNull()

    val url
        get() = rawPieces.firstOrNull { it.startsWith("url=") }
            ?.split("=")?.lastOrNull()

    val front
        get() = rawPieces.firstOrNull { it.startsWith("front=") }
            ?.split("=")?.lastOrNull()

    val fronts
        get() = rawPieces.firstOrNull { it.startsWith("fronts=") }
            ?.split("=")?.lastOrNull()?.split(",")?.filter { it.isNotEmpty() }

    val cert
        get() = rawPieces.firstOrNull { it.startsWith("cert=") }
            ?.split("=")?.lastOrNull()

    val iatMode
        get() = rawPieces.firstOrNull { it.startsWith("iat-mode=") }
            ?.split("=")?.lastOrNull()

    val ice
        get() = rawPieces.firstOrNull { it.startsWith("ice=") }
            ?.split("=")?.lastOrNull()

    val utls
        get() = rawPieces.firstOrNull { it.startsWith("utls=") }
            ?.split("=")?.lastOrNull()

    val utlsImitate
        get() = rawPieces.firstOrNull { it.startsWith("utls-imitate=") }
            ?.split("=")?.lastOrNull()

    val ver
        get() = rawPieces.firstOrNull { it.startsWith("ver=") }
            ?.split("=")?.lastOrNull()


    override fun toString(): String {
        return raw
    }

    companion object {

        val fingerprintRegex = Regex("^[a-f0-9]{40}$", RegexOption.IGNORE_CASE)

        @JvmStatic
        fun parseBridges(bridges: String): List<Bridge> {
            return bridges
                .split("\n")
                .mapNotNull {
                    val b = it.trim()
                    if (b.isNotEmpty()) Bridge(b) else null
                }
        }

        @JvmStatic
        fun getTransports(bridges: List<Bridge>): Set<String> {
            return bridges.mapNotNull { it.transport }.toSet()
        }
    }
}

object BridgeAsStringSerializer : KSerializer<Bridge> {
    override val descriptor = PrimitiveSerialDescriptor(Bridge::class.java.canonicalName!!,
        PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Bridge) {
        encoder.encodeString(value.raw)
    }

    override fun deserialize(decoder: Decoder): Bridge {
        return Bridge(decoder.decodeString())
    }
}
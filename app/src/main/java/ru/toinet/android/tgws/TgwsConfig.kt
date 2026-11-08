package ru.toinet.android.tgws

import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

object TgwsConfig {
    val PROTO_TAG_ABRIDGED = byteArrayOf(0xef.toByte(), 0xef.toByte(), 0xef.toByte(), 0xef.toByte())
    val PROTO_TAG_INTERMEDIATE = byteArrayOf(0xee.toByte(), 0xee.toByte(), 0xee.toByte(), 0xee.toByte())
    val PROTO_TAG_SECURE = byteArrayOf(0xdd.toByte(), 0xdd.toByte(), 0xdd.toByte(), 0xdd.toByte())

    const val PROTO_ABRIDGED_INT = 0xEFEFEFEF.toInt()
    const val PROTO_INTERMEDIATE_INT = 0xEEEEEEEE.toInt()
    const val PROTO_PADDED_INTERMEDIATE_INT = 0xDDDDDDDD.toInt()

    val RESERVED_FIRST_BYTES = setOf(0xEF.toByte(), 0xEE.toByte(), 0xDD.toByte())
    val RESERVED_STARTS = setOf(
        "HEAD", "POST", "GET ", "\u00ee\u00ee\u00ee\u00ee",
        "\u00dd\u00dd\u00dd\u00dd", "\u0016\u0003\u0001\u0002", "OPTI"
    )

    const val CFPROXY_DOMAINS_URL =
        "https://raw.githubusercontent.com/Flowseal/tg-ws-proxy/main/.github/cfproxy-domains.txt"

    val DC_DEFAULT_IPS: Map<Int, String> = mapOf(
        1 to "149.154.175.50",
        2 to "149.154.167.51",
        3 to "149.154.175.100",
        4 to "149.154.167.91",
        5 to "149.154.171.5",
        203 to "91.105.192.100"
    )

    val DC_TEST_IPS: Map<Int, String> = mapOf(
        1 to "149.154.175.10",
        2 to "149.154.167.40",
        3 to "149.154.175.117"
    )

    val IP_TO_DC: Map<String, Int> = mapOf(
        "149.154.175.50" to 1, "149.154.175.51" to 1, "149.154.175.54" to 1,
        "149.154.167.41" to 2, "149.154.167.50" to 2, "149.154.167.51" to 2, "149.154.167.220" to 2,
        "149.154.175.100" to 3, "149.154.175.101" to 3,
        "149.154.167.91" to 4, "149.154.167.92" to 4,
        "91.108.56.100" to 5, "91.108.56.126" to 5, "91.108.56.101" to 5, "91.108.56.116" to 5, "149.154.171.5" to 5,
        "91.105.192.100" to 203
    )

    val TG_RANGES: List<Pair<Long, Long>> = listOf(
        Pair(ipToLong("185.76.151.0"), ipToLong("185.76.151.255")),
        Pair(ipToLong("149.154.160.0"), ipToLong("149.154.175.255")),
        Pair(ipToLong("91.105.192.0"), ipToLong("91.105.193.255")),
        Pair(ipToLong("91.108.0.0"), ipToLong("91.108.255.255"))
    )

    private val CFPROXY_ENC = listOf(
        "virkgj.com", "vmmzovy.com", "mkuosckvso.com", "zaewayzmplad.com",
        "twdmbzcm.com", "awzwsldi.com", "clngqrflngqin.com", "tjacxbqtj.com",
        "bxaxtxmrw.com", "dmohrsgmohcrwb.com", "vwbmtmoi.com", "khgrre.com",
        "ulihssf.com", "tmhqsdqmfpmk.com", "xwuwoqbm.com", "orgcnunpj.com",
        "zhkuldz.com", "zypoljnslxa.com", "efabnxaowuzs.com", "zaftuzsftqdq.com"
    )

    fun decodeCfDomain(s: String): String {
        if (!s.endsWith(".com")) return s
        val p = s.substring(0, s.length - 4)
        val n = p.count { it.isLetter() }
        val decoded = StringBuilder()
        for (c in p) {
            if (c.isLetter()) {
                val base = if (c > '`') 97 else 65
                val shifted = (c.code - base - n) % 26
                val finalCode = if (shifted < 0) shifted + 26 + base else shifted + base
                decoded.append(finalCode.toChar())
            } else {
                decoded.append(c)
            }
        }
        return decoded.toString() + ".co.uk"
    }

    val DEFAULT_CF_PROXY_DOMAINS: List<String> = CFPROXY_ENC.map { decodeCfDomain(it) }

    fun ipToLong(ip: String): Long {
        return try {
            val parts = ip.split(".").map { it.toLong() }
            (parts[0] shl 24) or (parts[1] shl 16) or (parts[2] shl 8) or parts[3]
        } catch (_: Exception) {
            0L
        }
    }

    fun isTelegramIp(ip: String): Boolean {
        val n = ipToLong(ip)
        if (n != 0L && TG_RANGES.any { (lo, hi) -> n in lo..hi }) {
            return true
        }
        return IP_TO_DC.containsKey(ip)
    }

    fun wsDomains(dc: Int, isMedia: Boolean): List<String> {
        val effectiveDc = if (dc == 203) 2 else dc
        val base = if (effectiveDc > 5) "telegram.org" else "web.telegram.org"
        return if (isMedia) {
            listOf("kws$effectiveDc-1.$base", "kws$effectiveDc.$base")
        } else {
            listOf("kws$effectiveDc.$base")
        }
    }

    fun isValidDomain(domain: String): Boolean {
        if (domain.isBlank() || domain.length > 253) return false
        if (domain.startsWith(".") || domain.endsWith(".")) return false
        val labels = domain.split(".")
        if (labels.size < 2) return false
        for (label in labels) {
            if (label.isEmpty() || label.length > 63) return false
            if (label.startsWith("-") || label.endsWith("-")) return false
            if (!label.all { it.isLetterOrDigit() || it == '-' }) return false
        }
        val tld = labels.last()
        return tld.length >= 2 && tld.any { it.isLetter() }
    }
}

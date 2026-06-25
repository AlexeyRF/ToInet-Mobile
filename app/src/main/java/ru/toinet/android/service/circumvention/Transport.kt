package ru.toinet.android.service.circumvention

import IPtProxy.Controller
import IPtProxy.IPtProxy
import IPtProxy.OnTransportEvents
import android.content.Context
import android.util.Log
import ru.toinet.android.util.Prefs

enum class Transport(val id: String) {

    /**
     * Represents a direct connection to Tor with no bridges.
     */
    NONE("direct"),

    MEEK("meek"),
    OBFS4("obfs4"),

    /**
     * Tor connection with Snowflake, settable from ConfigConnectionBottomSheet.
     */
    SNOWFLAKE("snowflake"),

    /**
     * Tor connection with Snowflake using AMP brokers, settable from ConfigConnectionBottomSheet.
     */
    SNOWFLAKE_AMP("snowflake_amp"),

    /**
     * Use AMP brokers and start Snowflake with SQS rendezvous. Currently no way to
     * set SQS setting in app, if you force it, a runtime exception is thrown in
     * {@link ru.toinet.android.service.circumvention.SnowflakeClient#startWithSqsRendezvous(Controller)}
     */
    SNOWFLAKE_SQS("snowflake_sqs"),

    WEBTUNNEL("webtunnel"),

    DNSTT("dnstt"),

    /**
     * Use ByeDPI to bypass DPI.
     */
    BYEDPI("byedpi"),

    /**
     * Start lyrebird with obfs4 bridges stored in @{link {@link #getBridgesList()}}
     * This can be set in manually via the CustomBridgeBottomSheet.
     */
    CUSTOM("custom");

    companion object {
        @JvmStatic
        var stateLocation = ""

        const val TAG = "Transport"

        val controller: Controller by lazy {
            Controller(stateLocation, true, false, "INFO", statusCollector)
        }

        fun fromId(id: String): Transport {
            return when (id) {
                MEEK.id -> MEEK
                OBFS4.id -> OBFS4
                SNOWFLAKE.id -> SNOWFLAKE
                SNOWFLAKE_AMP.id -> SNOWFLAKE_AMP
                SNOWFLAKE_SQS.id -> SNOWFLAKE_SQS
                WEBTUNNEL.id -> WEBTUNNEL
                DNSTT.id -> DNSTT
                BYEDPI.id -> BYEDPI
                CUSTOM.id -> CUSTOM
                else -> NONE
            }
        }

        private val statusCollector = object : OnTransportEvents {
            override fun connected(name: String?) {
                if (name == null) return

                Log.d(TAG, "$name connected")
            }

            override fun error(name: String?, error: Exception?) {
                if (name == null) return

                Log.e(TAG, "$name error: $error")
            }

            override fun stopped(name: String?, error: Exception?) {
                if (name == null) return

                if (error != null) {
                    Log.e(
                        TAG,
                        "$name stopped: ${error.localizedMessage}"
                    )
                } else {
                    Log.d(TAG, "$name stopped.")
                }
            }

        }

        private const val AMP_BROKER = "https://snowflake-broker.torproject.net/"
        private val ampFronts = listOf("www.google.com")
        private const val AMP_CACHE = "https://cdn.ampproject.org/"
        private const val SQS_QUEUE =
            "https://sqs.us-east-1.amazonaws.com/893902434899/snowflake-broker"
        private const val SQS_CREDENTIALS =
            "eyJhd3MtYWNjZXNzLWtleS1pZCI6IkFLSUE1QUlGNFdKSlhTN1lIRUczIiwiYXdzLXNlY3JldC1rZXkiOiI3U0RNc0pBNHM1RitXZWJ1L3pMOHZrMFFXV0lsa1c2Y1dOZlVsQ0tRIn0="

        private val DNSTT_BRIDGES = listOf(
            "dnstt 192.0.3.1:80 F6B3CCA08E3C4026783FA14DBB14A3ADBCD0D27D udp=2.176.225.123:53 pubkey=488dd8eeab891e2df1b0fc0e5d8da28da23ea057a81934994d150105c2024048 domain=r.f14.1e-100.net",
            "dnstt 192.0.3.2:80 F6B3CCA08E3C4026783FA14DBB14A3ADBCD0D27D udp=185.129.168.241:53 pubkey=488dd8eeab891e2df1b0fc0e5d8da28da23ea057a81934994d150105c2024048 domain=r.f14.1e-100.net",
            "dnstt 192.0.2.3:80 F6B3CCA08E3C4026783FA14DBB14A3ADBCD0D27D udp=188.121.110.163:53 pubkey=488dd8eeab891e2df1b0fc0e5d8da28da23ea057a81934994d150105c2024048 domain=r.f14.1e-100.net",
            "dnstt 192.0.2.4:80 F6B3CCA08E3C4026783FA14DBB14A3ADBCD0D27D udp=195.114.8.10:53 pubkey=488dd8eeab891e2df1b0fc0e5d8da28da23ea057a81934994d150105c2024048 domain=r.f14.1e-100.net",
            "dnstt 192.0.2.5:80 F6B3CCA08E3C4026783FA14DBB14A3ADBCD0D27D doh=https://dns.google/dns-query pubkey=488dd8eeab891e2df1b0fc0e5d8da28da23ea057a81934994d150105c2024048 domain=r.f14.1e-100.net",
            "dnstt 192.0.2.6:80 A998F319ADB60EE344540EC4B21524CC484F96BE doh=https://dns.google/dns-query pubkey=241169008830694749fe96bb070c4855c5bb5b9c47b3833ed7d88521ba30a43f domain=t.ruhnama.net",
            "dnstt 192.0.3.7:80 80EEFA4F4875ED2B7B5A86DF2D7588AD32E29F15 doh=https://dns.google/dns-query pubkey=a2fb71077eeaa54a02cda7a90be306af5d299ab21822a8b277d4eacbc9168631 domain=t2.bypasscensorship.org",
        )

        private val HARDCODED_OBFS4 = listOf(
            "obfs4 84.22.108.82:9202 38D749F6FA80488329EDB1233B8FF8D229A1C57A cert=HX55aw36yJCnQu+1hSAg4pc7VhzE/6pgv9XlMf1AuCRPg8UlanGvfQiILqiGpLoXW4RiVA iat-mode=0",
            "obfs4 109.235.37.47:8443 CC3D4004181ACADE02AA19229777153F8C685684 cert=hbfGbCsbMFuZbTllS4H3JkOaXBpNNlA2Z32+hgy7JZum9DRAPAdZFZxIikof5qhu6SdyYw iat-mode=0",
            "obfs4 57.128.45.196:18384 E30D5552BEE79C5E8C61A943E9B3D2949F227C41 cert=boaTbcdp+rFHgUvweiAg60UUUpLZWecGl0uXRU358L/a7ZMrAnS/BodUKM3eyfWC+UVXTg iat-mode=0",
            "obfs4 51.89.228.250:21668 BA8BD67D8898CF378D4F73821DEB5657F4BB98DF cert=bEpLLgOwJ9fOJbeHb5r+ronUF2ck5nRd0Jl3zuy7rLoUp732QK2p/CUHjTAfBPCGfcVtSA iat-mode=0"
        )

        private val HARDCODED_WEBTUNNEL = listOf(
            "webtunnel [2001:db8:b1d5:4998:8150:f75b:988f:1f48]:443 216C8BB1C44FC2BFF7AF823B55AC38F113079B93 url=https://cdn-38.triplebit.dev/Bai8aXeiPhar5gai ver=0.0.2",
            "webtunnel [2001:db8:12ff:2d55:9130:36a7:c49b:d1f4]:443 933C998EC827D1C17CC93D1292BBC41735867CF8 url=https://x7t2qctb.xoomlia.com/qzxrtfmu/ ver=0.0.3",
            "webtunnel [2001:db8:9830:b7ec:bb37:f12:ec5f:aace]:443 67DFB4E30EF8CC5EFD87CC066D919D12DEDB5170 url= ver=0.0.4"
        )
    }

    val transportNames: Set<String>
        get() {
            return when (this) {
                NONE -> emptySet()
                MEEK -> setOf(IPtProxy.MeekLite)
                OBFS4 -> setOf(IPtProxy.Obfs4)
                WEBTUNNEL -> setOf(IPtProxy.Webtunnel)
                DNSTT -> setOf(IPtProxy.Dnstt)
                BYEDPI -> emptySet()
                CUSTOM -> {
                    Prefs.bridgesList
                        .mapNotNull { Bridge(it).transport }
                        .filter { it.isNotBlank() }
                        .toSet()
                }

                else -> setOf(IPtProxy.Snowflake)
            }
        }

    val port: Long
        get() {
            val transport = transportNames.firstOrNull() ?: return 0

            return controller.port(transport)
        }

    fun getTorConfig(context: Context): List<String> {
        val result = mutableListOf<String>()
        result.add("UseBridges ${if (this == NONE) "0" else "1"}")

        val proxy = Prefs.outboundProxy.first
        if (proxy != null) {
            var hostPort = proxy.host
            if (proxy.port in 1..<65536) hostPort += ":${proxy.port}"

            // Modern tor only supports https, socks4 and socks5. *No* http!
            when (proxy.scheme) {
                "https" -> {
                    result.add("HTTPSProxy $hostPort")

                    if (!proxy.userInfo.isNullOrBlank()) {
                        result.add("HTTPSProxyAuthenticator ${proxy.userInfo}")
                    }
                }

                "socks4" -> {
                    result.add("Socks4Proxy $hostPort")
                }

                "socks5" -> {
                    result.add("Socks5Proxy $hostPort")

                    val userInfo = proxy.userInfo?.split(":")

                    if (!userInfo?.getOrNull(0).isNullOrEmpty()) {
                        result.add("Socks5ProxyUsername ${userInfo[0]}")

                        var password = userInfo.getOrNull(1) ?: " "
                        if (password.isEmpty()) password = " "

                        result.add("Socks5ProxyPassword $password")
                    }
                }
            }
            // Ensure local PT connections bypass the proxy
            result.add("NoProxy 127.0.0.1")
        }

        if (Prefs.byedpiEnabled && Prefs.byedpiUseAsUpstream) {
            val byedpiPort = ru.toinet.android.byedpi.core.ByeDpiProxyPreferences.fromSharedPreferences(
                androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
            ).port
            result.add("Socks5Proxy 127.0.0.1:$byedpiPort")
            result.add("NoProxy 127.0.0.1")
        }

        for (transport in transportNames) {

            //sometimes there is a 0 for the port, which is invalid
            if (controller.port(transport) > 0)
                result.add(
                    "ClientTransportPlugin $transport socks5 127.0.0.1:${
                        controller.port(
                            transport
                        )
                    }"
                )
        }

        when (this) {
            NONE -> {
                // Outbound proxy is now handled above for all transport modes
            }

            MEEK -> {
                BuiltInBridges.getInstance(context)?.meek?.forEach {
                    result.add("Bridge ${it.raw}")
                }
            }

            OBFS4 -> {
                HARDCODED_OBFS4.forEach {
                    result.add("Bridge $it")
                }
            }

            SNOWFLAKE -> {
                BuiltInBridges.getInstance(context)?.snowflake?.forEach {
                    result.add("Bridge ${it.raw}")
                }
            }

            SNOWFLAKE_AMP -> {
                BuiltInBridges.getInstance(context)?.snowflake?.forEachIndexed { idx, it ->
                    val builder = Bridge.Builder(it)
                    builder.ip = "192.0.2.${5 + idx}"
                    builder.url = AMP_BROKER
                    builder.fronts = ampFronts.toMutableSet()

                    result.add("Bridge ${builder.build().raw}")
                }
            }

            SNOWFLAKE_SQS -> {
                BuiltInBridges.getInstance(context)?.snowflake?.forEachIndexed { idx, it ->
                    val builder = Bridge.Builder(it)
                    builder.ip = "192.0.2.${5 + idx}"
                    builder.url = null
                    builder.fronts.clear()

                    result.add("Bridge ${builder.build().raw}")
                }
            }

            WEBTUNNEL -> {
                HARDCODED_WEBTUNNEL.forEach {
                    val hasEmptyUrl = it.contains("url= ") || it.endsWith("url=")
                    if (!Prefs.torIgnoreEmptyUrl || !hasEmptyUrl) {
                        result.add("Bridge $it")
                    }
                }
            }

            DNSTT -> {
                BuiltInBridges.getInstance(context)?.dnstt?.forEach {
                    result.add("Bridge ${it.raw}")
                }

                DNSTT_BRIDGES.forEach {
                    result.add("Bridge $it")
                }
            }

            CUSTOM -> {
                Prefs.bridgesList.forEach {
                    result.add("Bridge $it")
                }
            }

            BYEDPI -> {
                // ByeDPI doesn't use Tor bridges
            }
        }

        return result
    }

    /**
     * @throws Exception if the transport cannot be initialized or if it couldn't bind a port for listening.
     */
    @Suppress("KotlinUnreachableCode") // unreachable code is disabled SQS logic
    fun start(context: Context) {
        when (this) {
            SNOWFLAKE -> {
                val snowflake = BuiltInBridges.getInstance(context)?.snowflake?.firstOrNull()

                // Seems more reliable in certain countries than the currently advertised one.
                val fronts = mutableSetOf<String>()
                snowflake?.front?.let { fronts.add(it) }
                snowflake?.fronts?.let { fronts.addAll(it) }

                controller.snowflakeIceServers = snowflake?.ice ?: ""
                controller.snowflakeBrokerUrl = snowflake?.url ?: ""
                controller.snowflakeFrontDomains = fronts.joinToString(",")
                controller.snowflakeAmpCacheUrl = ""
                controller.snowflakeSqsUrl = ""
                controller.snowflakeSqsCreds = ""
            }

            SNOWFLAKE_AMP -> {
                controller.snowflakeIceServers =
                    BuiltInBridges.getInstance(context)?.snowflake?.firstOrNull()?.ice ?: ""
                controller.snowflakeBrokerUrl = AMP_BROKER
                controller.snowflakeFrontDomains = ampFronts.joinToString(",")
                controller.snowflakeAmpCacheUrl = AMP_CACHE
                controller.snowflakeSqsUrl = ""
                controller.snowflakeSqsCreds = ""
            }

            SNOWFLAKE_SQS -> {
                /* TODO Make sure SQS queue and credentials are up to date in assets/fronts when
                    re-enabling this feature. also remove android:visibility="gone" from the SQS
                    container in app project's layout/config_connection_bottom_sheet.xml
                    */
                throw RuntimeException("Snowflake SQS Not supported right now https://github.com/guardianproject/orbot-android/issues/1320")

                controller.snowflakeIceServers =
                    BuiltInBridges.getInstance(context)?.snowflake?.firstOrNull()?.ice ?: ""
                controller.snowflakeBrokerUrl = ""
                controller.snowflakeFrontDomains = ""
                controller.snowflakeAmpCacheUrl = ""
                controller.snowflakeSqsUrl = SQS_QUEUE
                controller.snowflakeSqsCreds = SQS_CREDENTIALS
            }

            BYEDPI -> {
                // ByeDPI handles its own start logic via service routing
            }

            else -> Unit
        }

        val pair = Prefs.outboundProxy
        val proxy: String? = if (pair.first == null) null else pair.first.toString()

        for (transport in transportNames) {
            controller.start(transport, if (transport == IPtProxy.Snowflake) null else proxy)
        }
    }

    fun stop() {
        for (transport in transportNames) {
            controller.stop(transport)
        }
    }
}
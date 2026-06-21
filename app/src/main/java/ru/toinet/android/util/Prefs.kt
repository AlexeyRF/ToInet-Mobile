package ru.toinet.android.util

import android.content.ContentResolver
import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import ru.toinet.android.service.OrbotConstants
import ru.toinet.android.service.circumvention.Transport
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale
import java.util.concurrent.TimeUnit

object Prefs {
    private const val PREF_BRIDGES_LIST = "pref_bridges_list"
    private const val PREF_DEFAULT_LOCALE = "pref_default_locale"
    private const val PREF_DETECT_ROOT = "pref_detect_root"
    private const val PREF_ENABLE_LOGGING = "pref_enable_logging"
    private const val PREF_START_ON_BOOT = "pref_start_boot"
    private const val PREF_ALLOW_BACKGROUND_STARTS = "pref_allow_background_starts"
    private const val PREF_OPEN_PROXY_ON_ALL_INTERFACES = "pref_open_proxy_on_all_interfaces"
    private const val PREF_EXIT_NODES = "pref_exit_nodes"
    private const val PREF_BE_A_SNOWFLAKE = "pref_be_a_snowflake"
    private const val PREF_SHOW_SNOWFLAKE_MSG = "pref_show_snowflake_proxy_msg"
    private const val PREF_BE_A_SNOWFLAKE_LIMIT_WIFI = "pref_be_a_snowflake_limit_wifi"
    private const val PREF_BE_A_SNOWFLAKE_LIMIT_CHARGING = "pref_be_a_snowflake_limit_charing"

    private const val PREF_USE_SMART_CONNECT = "pref_use_smart_connect"
    private const val PREF_SMART_CONNECT_TIMEOUT = "pref_smart_connect_timeout"

    private const val PREF_HOST_ONION_SERVICES = "pref_host_onionservices"

    private const val PREF_SNOWFLAKES_SERVED_COUNT = "pref_snowflakes_served"
    private const val PREF_SNOWFLAKES_SERVED_COUNT_WEEKLY = "pref_snowflakes_served_weekly"

    private const val PREF_CURRENT_VERSION = "pref_current_version"

    private const val PREF_CAMO_APP_PACKAGE = "pref_key_camo_app"
    private const val PREF_CAMO_APP_DISPLAY_NAME = "pref_key_camo_app_display_name"
    private const val PREF_CAMO_APP_ALT_ICON_INDEX = "pref_key_camo_alticon"
    private const val PREF_REQUIRE_PASSWORD = "pref_require_password"
    private const val PREF_DISALLOW_BIOMETRIC_AUTH = "pref_auth_no_biometrics"

    private const val PREF_CONNECTION_PATHWAY = "pref_connection_pathway"

    const val PREF_SECURE_WINDOW_FLAG: String = "pref_flag_secure"

    private var cr: ContentResolver? = null


    var currentVersionForUpdate: Int
        get() = cr?.getPrefInt(PREF_CURRENT_VERSION) ?: 0
        set(version) = cr?.putPref(PREF_CURRENT_VERSION, version) ?: Unit

    private const val PREF_REINSTALL_GEOIP = "pref_geoip"

    @JvmStatic
    var isGeoIpReinstallNeeded: Boolean
        get() = cr?.getPrefBoolean(PREF_REINSTALL_GEOIP, true) ?: true
        set(value) = cr?.putPref(PREF_REINSTALL_GEOIP, value) ?: Unit

    @JvmStatic
    fun setContext(context: Context?) {
        if (cr == null) {
            cr = context?.contentResolver
        }
    }

    fun initWeeklyWorker(context: Context) {
        val myWorkBuilder =
            PeriodicWorkRequest.Builder(
                ResetSnowflakesServedWeeklyWorker::class.java,
                7,
                TimeUnit.DAYS
            )

        val myWork = myWorkBuilder.build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork("prefsWeeklyWorker", ExistingPeriodicWorkPolicy.KEEP, myWork)
    }

    @JvmStatic
    var hostOnionServicesEnabled: Boolean
        get() = cr?.getPrefBoolean(PREF_HOST_ONION_SERVICES, false) ?: false
        set(value) = cr?.putPref(PREF_HOST_ONION_SERVICES, value) ?: Unit

    @JvmStatic
    var bridgesList: List<String>
        get() {
            return cr?.getPrefString(PREF_BRIDGES_LIST)
                ?.split("\n")
                ?.filter { it.isNotBlank() }
                ?.map { it.trim() }
                ?: emptyList()
        }
        set(value) {
            cr?.putPref(
                PREF_BRIDGES_LIST,
                value.filter { it.isNotBlank() }.joinToString("\n") { it.trim() })
        }

    var bridgeCountry: String?
        get() = cr?.getPrefString("pref_bridge_country")
        set(value) = cr?.putPref("pref_bridge_country", value) ?: Unit

    @JvmStatic
    var defaultLocale: String
        get() = cr?.getPrefString(PREF_DEFAULT_LOCALE) ?: Locale.getDefault().language
        set(value) = cr?.putPref(PREF_DEFAULT_LOCALE, value) ?: Unit

    fun detectRoot(): Boolean {
        return cr?.getPrefBoolean(PREF_DETECT_ROOT, true) ?: true
    }

    fun beSnowflakeProxy(): Boolean {
        return cr?.getPrefBoolean(PREF_BE_A_SNOWFLAKE) ?: false
    }

    fun showSnowflakeProxyToast(): Boolean {
        return cr?.getPrefBoolean(PREF_SHOW_SNOWFLAKE_MSG) ?: false
    }

    fun setBeSnowflakeProxy(beSnowflakeProxy: Boolean) {
        cr?.putPref(PREF_BE_A_SNOWFLAKE, beSnowflakeProxy)
    }

    fun setBeSnowflakeProxyLimitWifi(beSnowflakeProxy: Boolean) {
        cr?.putPref(PREF_BE_A_SNOWFLAKE_LIMIT_WIFI, beSnowflakeProxy)
    }

    fun setBeSnowflakeProxyLimitCharging(beSnowflakeProxy: Boolean) {
        cr?.putPref(PREF_BE_A_SNOWFLAKE_LIMIT_CHARGING, beSnowflakeProxy)
    }

    fun limitSnowflakeProxyingWifi(): Boolean {
        return cr?.getPrefBoolean(PREF_BE_A_SNOWFLAKE_LIMIT_WIFI) ?: false
    }

    fun limitSnowflakeProxyingCharging(): Boolean {
        return cr?.getPrefBoolean(PREF_BE_A_SNOWFLAKE_LIMIT_CHARGING) ?: false
    }

    @JvmStatic
    fun useDebugLogging(): Boolean {
        return cr?.getPrefBoolean(PREF_ENABLE_LOGGING) ?: false
    }

    fun allowBackgroundStarts(): Boolean {
        return cr?.getPrefBoolean(PREF_ALLOW_BACKGROUND_STARTS, true) ?: true
    }

    fun openProxyOnAllInterfaces(): Boolean {
        return cr?.getPrefBoolean(PREF_OPEN_PROXY_ON_ALL_INTERFACES) ?: false
    }

    fun startOnBoot(): Boolean {
        return cr?.getPrefBoolean(PREF_START_ON_BOOT, true) ?: true
    }

    @JvmStatic
    var exitNodes: String?
        get() = cr?.getPrefString(PREF_EXIT_NODES)
        set(country) = cr?.putPref(PREF_EXIT_NODES, country) ?: Unit

    val snowflakesServed: Int
        get() = cr?.getPrefInt(PREF_SNOWFLAKES_SERVED_COUNT) ?: 0

    val snowflakesServedWeekly: Int
        get() = cr?.getPrefInt(PREF_SNOWFLAKES_SERVED_COUNT_WEEKLY) ?: 0

    fun addSnowflakeServed() {
        cr?.putPref(PREF_SNOWFLAKES_SERVED_COUNT, snowflakesServed + 1)
        cr?.putPref(PREF_SNOWFLAKES_SERVED_COUNT_WEEKLY, snowflakesServedWeekly + 1)
    }

    fun resetSnowflakesServedWeekly() {
        cr?.putPref(PREF_SNOWFLAKES_SERVED_COUNT_WEEKLY, 0)
    }

    @JvmStatic
    var transport: Transport
        /**
         * @return How Orbot is configured to attempt to connect to Tor
         */
        get() = Transport.fromId(cr?.getPrefString(PREF_CONNECTION_PATHWAY) ?: Transport.NONE.id)
        /**
         * Set how Orbot should initialize a tor connection (direct, with a PT, etc)
         */
        set(value) = cr?.putPref(PREF_CONNECTION_PATHWAY, value.id) ?: Unit

    var smartConnect: Boolean
        get() = cr?.getPrefBoolean(PREF_USE_SMART_CONNECT) ?: false
        set(value) = cr?.putPref(PREF_USE_SMART_CONNECT, value) ?: Unit


    var smartConnectTimeout: Int
        get() = cr?.getPrefInt(PREF_SMART_CONNECT_TIMEOUT) ?: 30
        set(value) = cr?.putPref(PREF_SMART_CONNECT_TIMEOUT, value) ?: Unit

    var turnProxyEnabled: Boolean
        get() = cr?.getPrefBoolean("pref_turn_proxy_enabled") ?: false
        set(value) = cr?.putPref("pref_turn_proxy_enabled", value) ?: Unit

    var turnProxyProvider: String
        get() = cr?.getPrefString("pref_turn_proxy_provider") ?: "vk"
        set(value) = cr?.putPref("pref_turn_proxy_provider", value) ?: Unit

    var turnProxyVkLink: String
        get() = cr?.getPrefString("pref_turn_proxy_vk_link") ?: ""
        set(value) = cr?.putPref("pref_turn_proxy_vk_link", value) ?: Unit

    var turnProxyServerAddr: String
        get() = cr?.getPrefString("pref_turn_proxy_server_addr") ?: ""
        set(value) = cr?.putPref("pref_turn_proxy_server_addr", value) ?: Unit

    var turnProxyLocalPort: Int
        get() = cr?.getPrefInt("pref_turn_proxy_local_port") ?: 9000
        set(value) = cr?.putPref("pref_turn_proxy_local_port", value) ?: Unit

    var turnProxyUseUdp: Boolean
        get() = cr?.getPrefBoolean("pref_turn_proxy_use_udp") ?: true
        set(value) = cr?.putPref("pref_turn_proxy_use_udp", value) ?: Unit

    var turnProxyObfKey: String
        get() = cr?.getPrefString("pref_turn_proxy_obf_key") ?: ""
        set(value) = cr?.putPref("pref_turn_proxy_obf_key", value) ?: Unit

    var turnProxyUseByeDpi: Boolean
        get() = cr?.getPrefBoolean("pref_turn_proxy_use_byedpi") ?: false
        set(value) = cr?.putPref("pref_turn_proxy_use_byedpi", value) ?: Unit

    var turnProxyThreads: Int
        get() = cr?.getPrefInt("pref_turn_proxy_threads") ?: 12
        set(value) = cr?.putPref("pref_turn_proxy_threads", value) ?: Unit

    var turnProxyStreamsPerCred: Int
        get() = cr?.getPrefInt("pref_turn_proxy_streams_per_cred") ?: 6
        set(value) = cr?.putPref("pref_turn_proxy_streams_per_cred", value) ?: Unit

    var turnProxyBond: Boolean
        get() = cr?.getPrefBoolean("pref_turn_proxy_bond") ?: false
        set(value) = cr?.putPref("pref_turn_proxy_bond", value) ?: Unit

    var turnProxyManualCaptcha: Boolean
        get() = cr?.getPrefBoolean("pref_turn_proxy_manual_captcha") ?: false
        set(value) = cr?.putPref("pref_turn_proxy_manual_captcha", value) ?: Unit

    var turnProxyBrowser: String
        get() = cr?.getPrefString("pref_turn_proxy_browser") ?: "firefox"
        set(value) = cr?.putPref("pref_turn_proxy_browser", value) ?: Unit

    var turnProxyDnsServers: String
        get() = cr?.getPrefString("pref_turn_proxy_dns_servers") ?: ""
        set(value) = cr?.putPref("pref_turn_proxy_dns_servers", value) ?: Unit

    var turnProxyDnsMode: String
        get() = cr?.getPrefString("pref_turn_proxy_dns_mode") ?: "auto"
        set(value) = cr?.putPref("pref_turn_proxy_dns_mode", value) ?: Unit

    var turnProxyMagicTurn: String
        get() = cr?.getPrefString("pref_turn_proxy_magic_turn") ?: ""
        set(value) = cr?.putPref("pref_turn_proxy_magic_turn", value) ?: Unit

    var turnProxyClientId: String
        get() = cr?.getPrefString("pref_turn_proxy_client_id") ?: ""
        set(value) = cr?.putPref("pref_turn_proxy_client_id", value) ?: Unit

    var turnProxyRawMode: Boolean
        get() = cr?.getPrefBoolean("pref_turn_proxy_raw_mode") ?: false
        set(value) = cr?.putPref("pref_turn_proxy_raw_mode", value) ?: Unit

    var turnProxyRawCommand: String
        get() = cr?.getPrefString("pref_turn_proxy_raw_command") ?: ""
        set(value) = cr?.putPref("pref_turn_proxy_raw_command", value) ?: Unit

    var turnProxyTcpForward: Boolean
        get() = cr?.getPrefBoolean("pref_turn_proxy_tcp_forward") ?: false
        set(value) = cr?.putPref("pref_turn_proxy_tcp_forward", value) ?: Unit

    var turnProxyObfProfile: String
        get() = cr?.getPrefString("pref_turn_proxy_obf_profile") ?: "rtpopus"
        set(value) = cr?.putPref("pref_turn_proxy_obf_profile", value) ?: Unit


    var tgwsUseByeDpi: Boolean
        get() = cr?.getPrefBoolean("pref_tgws_use_byedpi") ?: false
        set(value) = cr?.putPref("pref_tgws_use_byedpi", value) ?: Unit

    @JvmStatic
    var proxyEnabled: Boolean
        get() = cr?.getPrefBoolean("pref_proxy_enabled") ?: false
        set(value) = cr?.putPref("pref_proxy_enabled", value) ?: Unit

    // URI, if config present + valid, malformed URL string if config present + invalid
    val outboundProxy: Pair<URI?, String?>
        get() {
            if (!proxyEnabled) return Pair(null, null)

            val scheme = cr?.getPrefString("pref_proxy_type")?.lowercase()?.trim()
            if (scheme.isNullOrEmpty()) return Pair(null, null)

            val host = cr?.getPrefString("pref_proxy_host")?.trim()
            if (host.isNullOrEmpty()) return Pair(null, null)

            val url = StringBuilder(scheme)
            url.append("://")

            var needsAt = false
            val username = cr?.getPrefString("pref_proxy_username")
            if (!username.isNullOrEmpty()) {
                url.append(username)
                needsAt = true
            }

            val password = cr?.getPrefString("pref_proxy_password")
            if (!password.isNullOrEmpty()) {
                url.append(":")
                url.append(password)
                needsAt = true
            }

            if (needsAt) url.append("@")

            url.append(host)

            val port = try {
                cr?.getPrefString("pref_proxy_port")?.trim()?.toInt() ?: 0
            } catch (_: Throwable) {
                0
            }

            if (port in 1..<65536) {
                url.append(":")
                url.append(port)
            }

            url.append("/")

            return try {
                Pair(URI(url.toString()), null)
            } catch (_: URISyntaxException) {
                Pair(
                    null,
                    url.toString()
                )
            }
        }

    var isSecureWindow: Boolean
        get() = cr?.getPrefBoolean(PREF_SECURE_WINDOW_FLAG, false) ?: false
        set(isFlagSecure) = cr?.putPref(PREF_SECURE_WINDOW_FLAG, isFlagSecure) ?: Unit

    const val DEFAULT_CAMO_DISABLED_ACTIVITY: String = "ru.toinet.android.OrbotActivity"


    val selectedCamoApp: String
        get() = cr?.getPrefString(PREF_CAMO_APP_PACKAGE, DEFAULT_CAMO_DISABLED_ACTIVITY) ?: ""

    fun setCamoAppPackage(packageName: String?) {
        cr?.putPref(PREF_CAMO_APP_PACKAGE, packageName)
    }

    var camoAppDisplayName: String?
        get() = cr?.getPrefString(PREF_CAMO_APP_DISPLAY_NAME) ?: "Android"
        set(name) = cr?.putPref(PREF_CAMO_APP_DISPLAY_NAME, name) ?: Unit

    var camoAppAltIconIndex: Int?
        get() = cr?.getPrefInt(PREF_CAMO_APP_ALT_ICON_INDEX, -1)
        set(index) = cr?.putPref(PREF_CAMO_APP_ALT_ICON_INDEX, index ?: -1) ?: Unit


    val requireDeviceAuthentication: Boolean
        get() = cr?.getPrefBoolean(PREF_REQUIRE_PASSWORD) ?: false

    val disallowBiometricAuthentication: Boolean
        get() = cr?.getPrefBoolean(PREF_DISALLOW_BIOMETRIC_AUTH) ?: false

    val proxySocksPort: String?
        get() = cr?.getPrefString(OrbotConstants.PREF_SOCKS)

    val proxyHttpPort: String?
        get() = cr?.getPrefString(OrbotConstants.PREF_HTTP)

    val connectionPadding: Boolean
        get() = cr?.getPrefBoolean(OrbotConstants.PREF_CONNECTION_PADDING) ?: false

    val reducedConnectionPadding: Boolean
        get() = cr?.getPrefBoolean(OrbotConstants.PREF_REDUCED_CONNECTION_PADDING, true) ?: true

    val circuitPadding: Boolean
        get() = cr?.getPrefBoolean(OrbotConstants.PREF_CIRCUIT_PADDING, true) ?: true

    val reducedCircuitPadding: Boolean
        get() = cr?.getPrefBoolean(OrbotConstants.PREF_REDUCED_CIRCUIT_PADDING, true) ?: true

    val torTransPort: String?
        get() = cr?.getPrefString(OrbotConstants.PREF_TRANSPORT)

    val torDnsPort: String?
        get() = cr?.getPrefString(OrbotConstants.PREF_DNSPORT)

    val entryNodes: String?
        get() = cr?.getPrefString("pref_entrance_nodes")

    val excludeNodes: String?
        get() = cr?.getPrefString("pref_exclude_nodes")

    val strictNodes: Boolean
        get() = cr?.getPrefBoolean("pref_strict_nodes") ?: false

    val reachableAddresses: Boolean
        get() = cr?.getPrefBoolean(OrbotConstants.PREF_REACHABLE_ADDRESSES) ?: false

    val reachableAddressesPorts: String?
        get() = cr?.getPrefString(OrbotConstants.PREF_REACHABLE_ADDRESSES_PORTS)

    val customTorRc: String?
        get() = cr?.getPrefString("pref_custom_torrc")

    val isolateDest: Boolean
        get() = cr?.getPrefBoolean(OrbotConstants.PREF_ISOLATE_DEST) ?: false

    val isolatePort: Boolean
        get() = cr?.getPrefBoolean(OrbotConstants.PREF_ISOLATE_PORT) ?: false

    val isolateProtocol: Boolean
        get() = cr?.getPrefBoolean(OrbotConstants.PREF_ISOLATE_PROTOCOL) ?: false

    val isolateKeepAlive: Boolean
        get() = cr?.getPrefBoolean(OrbotConstants.PREF_ISOLATE_KEEP_ALIVE) ?: false

    val preferIpv6: Boolean
        get() = cr?.getPrefBoolean(OrbotConstants.PREF_PREFER_IPV6, true) ?: true

    val disableIpv4: Boolean
        get() = cr?.getPrefBoolean(OrbotConstants.PREF_DISABLE_IPV4) ?: false

    var torifiedApps: String
        get() = cr?.getPrefString(OrbotConstants.PREFS_KEY_TORIFIED, "") ?: ""
        set(value) = cr?.putPref(OrbotConstants.PREFS_KEY_TORIFIED, value) ?: Unit

    @JvmStatic
    var torDnsPortResolved: Int
        get() = cr?.getPrefInt(OrbotConstants.PREFS_DNS_PORT) ?: 0
        set(value) = cr?.putPref(OrbotConstants.PREFS_DNS_PORT, value) ?: Unit

    @JvmStatic
    var torEnabled: Boolean
        get() = cr?.getPrefBoolean("tor_enabled", true) ?: true
        set(value) = cr?.putPref("tor_enabled", value) ?: Unit

    @JvmStatic
    var isGlobalVpnEnabled: Boolean
        get() = cr?.getPrefBoolean("pref_global_vpn_enabled", false) ?: false
        set(value) = cr?.putPref("pref_global_vpn_enabled", value) ?: Unit

    var vpnAppsInitialized: Boolean
        get() = cr?.getPrefBoolean("pref_vpn_apps_initialized", false) ?: false
        set(value) = cr?.putPref("pref_vpn_apps_initialized", value) ?: Unit

    var vpnExcludedApps: Set<String>
        get() = cr?.getPrefString("pref_vpn_excluded_apps")?.split(",")?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
        set(value) = cr?.putPref("pref_vpn_excluded_apps", value.joinToString(",")) ?: Unit

    var vpnProvider: String
        get() = cr?.getPrefString("pref_vpn_provider") ?: "byedpi"
        set(value) = cr?.putPref("pref_vpn_provider", value) ?: Unit

    var byedpiEnabled: Boolean
        get() = cr?.getPrefBoolean("byedpi_enabled", false) ?: false
        set(value) = cr?.putPref("byedpi_enabled", value) ?: Unit

    @JvmStatic
    var byedpiUseAsUpstream: Boolean
        get() = cr?.getPrefBoolean("byedpi_use_as_upstream", false) ?: false
        set(value) = cr?.putPref("byedpi_use_as_upstream", value) ?: Unit

    @JvmStatic
    var byedpiMode: String
        get() = cr?.getPrefString("byedpi_mode") ?: "VPN"
        set(value) = cr?.putPref("byedpi_mode", value) ?: Unit

    @JvmStatic
    var byedpiArgs: String
        get() = cr?.getPrefString("byedpi_cmd_args") ?: ""
        set(value) = cr?.putPref("byedpi_cmd_args", value) ?: Unit

    @JvmStatic
    var byedpiEnableCmdSettings: Boolean
        get() = cr?.getPrefBoolean("byedpi_enable_cmd_settings", true) ?: true
        set(value) = cr?.putPref("byedpi_enable_cmd_settings", value) ?: Unit

    @JvmStatic
    var tgwsEnabled: Boolean
        get() = cr?.getPrefBoolean("tgws_enabled", false) ?: false
        set(value) = cr?.putPref("tgws_enabled", value) ?: Unit

    @JvmStatic
    var tgwsHost: String
        get() = cr?.getPrefString("tgws_host") ?: "127.0.0.1"
        set(value) = cr?.putPref("tgws_host", value) ?: Unit

    @JvmStatic
    var tgwsPort: Int
        get() = cr?.getPrefInt("tgws_port") ?: 1480
        set(value) = cr?.putPref("tgws_port", value) ?: Unit

    @JvmStatic
    var tgwsDcMappings: Map<Int, String>
        get() {
            val s = cr?.getPrefString("tgws_dc_mappings") ?: "2:149.154.167.220\n4:149.154.167.220"
            return s.split("\n").filter { it.contains(":") }.associate {
                val parts = it.split(":")
                parts[0].trim().toInt() to parts[1].trim()
            }
        }
        set(value) {
            val s = value.map { "${it.key}:${it.value}" }.joinToString("\n")
            cr?.putPref("tgws_dc_mappings", s)
        }

    @JvmStatic
    var rehabilitatorEnabled: Boolean
        get() = cr?.getPrefBoolean("rehabilitator_enabled", false) ?: false
        set(value) = cr?.putPref("rehabilitator_enabled", value) ?: Unit

    @JvmStatic
    var rehabilitatorHost: String
        get() = cr?.getPrefString("rehabilitator_host") ?: "127.0.0.1"
        set(value) = cr?.putPref("rehabilitator_host", value) ?: Unit

    @JvmStatic
    var rehabilitatorPort: Int
        get() = cr?.getPrefInt("rehabilitator_port") ?: 1080
        set(value) = cr?.putPref("rehabilitator_port", value) ?: Unit

    @JvmStatic
    var rehabilitatorUpstreamHost: String
        get() = cr?.getPrefString("rehabilitator_upstream_host") ?: ""
        set(value) = cr?.putPref("rehabilitator_upstream_host", value) ?: Unit

    @JvmStatic
    var rehabilitatorUpstreamPort: Int
        get() = cr?.getPrefInt("rehabilitator_upstream_port") ?: 1080
        set(value) = cr?.putPref("rehabilitator_upstream_port", value) ?: Unit

    @JvmStatic
    var rehabilitatorUsername: String
        get() = cr?.getPrefString("rehabilitator_username") ?: ""
        set(value) = cr?.putPref("rehabilitator_username", value) ?: Unit

    @JvmStatic
    var rehabilitatorPassword: String
        get() = cr?.getPrefString("rehabilitator_password") ?: ""
        set(value) = cr?.putPref("rehabilitator_password", value) ?: Unit

    @JvmStatic
    var rehabilitatorArgs: String
        get() = cr?.getPrefString("rehabilitator_args") ?: "--disorder 1"
        set(value) = cr?.putPref("rehabilitator_args", value) ?: Unit

}

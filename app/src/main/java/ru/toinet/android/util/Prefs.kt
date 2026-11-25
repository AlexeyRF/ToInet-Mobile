package ru.toinet.android.util

import android.content.ContentResolver
import android.content.Context
import ru.toinet.android.service.ToInetConstants
import ru.toinet.android.service.circumvention.Transport
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

object Prefs {
    private const val PREF_BRIDGES_LIST = "pref_bridges_list"
    private const val PREF_DEFAULT_LOCALE = "pref_default_locale"
    private const val PREF_DETECT_ROOT = "pref_detect_root"
    private const val PREF_ENABLE_LOGGING = "pref_enable_logging"
    private const val PREF_START_ON_BOOT = "pref_start_boot"
    private const val PREF_ALLOW_BACKGROUND_STARTS = "pref_allow_background_starts"
    private const val PREF_OPEN_PROXY_ON_ALL_INTERFACES = "pref_open_proxy_on_all_interfaces"
    private const val PREF_EXIT_NODES = "pref_exit_nodes"

    private const val PREF_CURRENT_VERSION = "pref_current_version"

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



    @JvmStatic
    fun useDebugLogging(): Boolean {
        return cr?.getPrefBoolean(PREF_ENABLE_LOGGING) ?: false
    }

    fun allowBackgroundStarts(): Boolean {
        return cr?.getPrefBoolean(PREF_ALLOW_BACKGROUND_STARTS, true) ?: true
    }

    var openProxyOnAllInterfaces: Boolean
        get() = cr?.getPrefBoolean(PREF_OPEN_PROXY_ON_ALL_INTERFACES) ?: false
        set(value) = cr?.putPref(PREF_OPEN_PROXY_ON_ALL_INTERFACES, value) ?: Unit

    var proxyShareWarningDismissed: Boolean
        get() = cr?.getPrefBoolean("pref_proxy_share_warning_dismissed", false) ?: false
        set(value) = cr?.putPref("pref_proxy_share_warning_dismissed", value) ?: Unit

    var directPingWarningDismissed: Boolean
        get() = cr?.getPrefBoolean("pref_direct_ping_warning_dismissed", false) ?: false
        set(value) = cr?.putPref("pref_direct_ping_warning_dismissed", value) ?: Unit

    fun startOnBoot(): Boolean {
        return cr?.getPrefBoolean(PREF_START_ON_BOOT, true) ?: true
    }

    @JvmStatic
    var exitNodes: String?
        get() = cr?.getPrefString(PREF_EXIT_NODES)
        set(country) = cr?.putPref(PREF_EXIT_NODES, country) ?: Unit

    @JvmStatic
    var transport: Transport
        get() = Transport.fromId(cr?.getPrefString(PREF_CONNECTION_PATHWAY) ?: Transport.NONE.id)
        set(value) = cr?.putPref(PREF_CONNECTION_PATHWAY, value.id) ?: Unit

    @JvmStatic
    var proxyEnabled: Boolean
        get() = cr?.getPrefBoolean("pref_proxy_enabled") ?: false
        set(value) = cr?.putPref("pref_proxy_enabled", value) ?: Unit

    // URI, if config present + valid, malformed URL string if config present + invalid
    @JvmStatic
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

    val requireDeviceAuthentication: Boolean
        get() = cr?.getPrefBoolean(PREF_REQUIRE_PASSWORD) ?: false

    val disallowBiometricAuthentication: Boolean
        get() = cr?.getPrefBoolean(PREF_DISALLOW_BIOMETRIC_AUTH) ?: false

    val proxySocksPort: String?
        get() = cr?.getPrefString(ToInetConstants.PREF_SOCKS)

    val proxyHttpPort: String?
        get() = cr?.getPrefString(ToInetConstants.PREF_HTTP)

    val connectionPadding: Boolean
        get() = cr?.getPrefBoolean(ToInetConstants.PREF_CONNECTION_PADDING) ?: false

    val reducedConnectionPadding: Boolean
        get() = cr?.getPrefBoolean(ToInetConstants.PREF_REDUCED_CONNECTION_PADDING, true) ?: true

    val circuitPadding: Boolean
        get() = cr?.getPrefBoolean(ToInetConstants.PREF_CIRCUIT_PADDING, true) ?: true

    val reducedCircuitPadding: Boolean
        get() = cr?.getPrefBoolean(ToInetConstants.PREF_REDUCED_CIRCUIT_PADDING, true) ?: true

    val torTransPort: String?
        get() = cr?.getPrefString(ToInetConstants.PREF_TRANSPORT)

    var torIgnoreEmptyUrl: Boolean
        get() = cr?.getPrefBoolean("pref_tor_ignore_empty_url", true) ?: true
        set(value) = cr?.putPref("pref_tor_ignore_empty_url", value) ?: Unit

    @JvmStatic
    var torSocksPortResolved: Int
        get() = cr?.getPrefInt("pref_tor_socks_port_resolved", 9853) ?: 9853
        set(value) = cr?.putPref("pref_tor_socks_port_resolved", value) ?: Unit

    val torDnsPort: String?
        get() = cr?.getPrefString(ToInetConstants.PREF_DNSPORT)

    val entryNodes: String?
        get() = cr?.getPrefString("pref_entrance_nodes")

    val excludeNodes: String?
        get() = cr?.getPrefString("pref_exclude_nodes")

    val strictNodes: Boolean
        get() = cr?.getPrefBoolean("pref_strict_nodes") ?: false

    val reachableAddresses: Boolean
        get() = cr?.getPrefBoolean(ToInetConstants.PREF_REACHABLE_ADDRESSES) ?: false

    val reachableAddressesPorts: String?
        get() = cr?.getPrefString(ToInetConstants.PREF_REACHABLE_ADDRESSES_PORTS)

    val customTorRc: String?
        get() = cr?.getPrefString("pref_custom_torrc")

    val isolateDest: Boolean
        get() = cr?.getPrefBoolean(ToInetConstants.PREF_ISOLATE_DEST) ?: false

    val isolatePort: Boolean
        get() = cr?.getPrefBoolean(ToInetConstants.PREF_ISOLATE_PORT) ?: false

    val isolateProtocol: Boolean
        get() = cr?.getPrefBoolean(ToInetConstants.PREF_ISOLATE_PROTOCOL) ?: false

    val isolateKeepAlive: Boolean
        get() = cr?.getPrefBoolean(ToInetConstants.PREF_ISOLATE_KEEP_ALIVE) ?: false

    val preferIpv6: Boolean
        get() = cr?.getPrefBoolean(ToInetConstants.PREF_PREFER_IPV6, true) ?: true

    val disableIpv4: Boolean
        get() = cr?.getPrefBoolean(ToInetConstants.PREF_DISABLE_IPV4) ?: false

    @JvmStatic
    var torDnsPortResolved: Int
        get() = cr?.getPrefInt(ToInetConstants.PREFS_DNS_PORT) ?: 0
        set(value) = cr?.putPref(ToInetConstants.PREFS_DNS_PORT, value) ?: Unit

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

    var vpnLoopProtectionInitialized: Boolean
        get() = cr?.getPrefBoolean("pref_vpn_loop_protection_initialized", false) ?: false
        set(value) = cr?.putPref("pref_vpn_loop_protection_initialized", value) ?: Unit

    @JvmStatic
    fun isLockedVpnPackage(packageName: String): Boolean {
        val lower = packageName.lowercase()
        return lower.contains("toinet") || lower.contains("alexeyrf")
    }

    var vpnExcludedApps: Set<String>
        get() = cr?.getPrefString("pref_vpn_excluded_apps")?.split(",")?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
        set(value) = cr?.putPref("pref_vpn_excluded_apps", value.joinToString(",")) ?: Unit

    var vpnProvider: String
        get() = cr?.getPrefString("pref_vpn_provider") ?: "byedpi"
        set(value) = cr?.putPref("pref_vpn_provider", value) ?: Unit

    var appRoutingEnabled: Boolean
        get() {
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return false
            return cr?.getPrefBoolean("pref_app_routing_enabled", true) ?: true
        }
        set(value) {
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return
            cr?.putPref("pref_app_routing_enabled", value) ?: Unit
        }

    var appRoutingRulesJson: String
        get() = cr?.getPrefString("pref_app_routing_rules_json") ?: ""
        set(value) = cr?.putPref("pref_app_routing_rules_json", value) ?: Unit

    @JvmStatic
    var appRoutingRulesVersion: Long
        get() = cr?.getPrefLong("pref_app_routing_rules_version", 0L) ?: 0L
        set(value) = cr?.putPref("pref_app_routing_rules_version", value) ?: Unit

    var appRoutingDefaultAction: String
        get() = cr?.getPrefString("pref_app_routing_default_action") ?: "direct"
        set(value) = cr?.putPref("pref_app_routing_default_action", value) ?: Unit

    @JvmStatic
    var torUpstreamProvider: String
        get() = cr?.getPrefString("pref_tor_upstream_provider") ?: "none"
        set(value) = cr?.putPref("pref_tor_upstream_provider", value) ?: Unit

    @JvmStatic
    var easyVpnUpstreamEnabled: Boolean
        get() = cr?.getPrefBoolean("pref_easyvpn_upstream_enabled", false) ?: false
        set(value) = cr?.putPref("pref_easyvpn_upstream_enabled", value) ?: Unit

    @JvmStatic
    var easyVpnUpstreamProvider: String
        get() = cr?.getPrefString("pref_easyvpn_upstream_provider") ?: "tor"
        set(value) = cr?.putPref("pref_easyvpn_upstream_provider", value) ?: Unit

    @JvmStatic
    var easyVpnUpstreamHost: String
        get() = cr?.getPrefString("pref_easyvpn_upstream_host") ?: "127.0.0.1"
        set(value) = cr?.putPref("pref_easyvpn_upstream_host", value) ?: Unit

    @JvmStatic
    var easyVpnUpstreamPort: Int
        get() = cr?.getPrefInt("pref_easyvpn_upstream_port") ?: 9853
        set(value) = cr?.putPref("pref_easyvpn_upstream_port", value) ?: Unit

    @JvmStatic
    var byedpiUpstreamEnabled: Boolean
        get() = cr?.getPrefBoolean("pref_byedpi_upstream_enabled", false) ?: false
        set(value) = cr?.putPref("pref_byedpi_upstream_enabled", value) ?: Unit

    @JvmStatic
    var byedpiUpstreamProvider: String
        get() = cr?.getPrefString("pref_byedpi_upstream_provider") ?: "easyvpn"
        set(value) = cr?.putPref("pref_byedpi_upstream_provider", value) ?: Unit

    @JvmStatic
    var byedpiUpstreamHost: String
        get() = cr?.getPrefString("pref_byedpi_upstream_host") ?: "127.0.0.1"
        set(value) = cr?.putPref("pref_byedpi_upstream_host", value) ?: Unit

    @JvmStatic
    var byedpiUpstreamPort: Int
        get() = cr?.getPrefInt("pref_byedpi_upstream_port") ?: 10808
        set(value) = cr?.putPref("pref_byedpi_upstream_port", value) ?: Unit

    @JvmStatic
    var byedpiEnabled: Boolean
        get() = cr?.getPrefBoolean("byedpi_enabled", false) ?: false
        set(value) = cr?.putPref("byedpi_enabled", value) ?: Unit



    @JvmStatic
    var byedpiSubscriptionUrl: String
        get() = cr?.getPrefString("byedpi_subscription_url") ?: ""
        set(value) = cr?.putPref("byedpi_subscription_url", value) ?: Unit

    @JvmStatic
    var byedpiMode: String
        get() = cr?.getPrefString("byedpi_mode") ?: "VPN"
        set(value) = cr?.putPref("byedpi_mode", value) ?: Unit

    @JvmStatic
    var byedpiArgs: String
        get() {
            val v = cr?.getPrefString("byedpi_cmd_args")?.trim() ?: ""
            return if (v.isEmpty()) "--preset general" else v
        }
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
        get() = if (openProxyOnAllInterfaces) "0.0.0.0" else "127.0.0.1"
        set(value) = Unit

    @JvmStatic
    var tgwsPort: Int
        get() = cr?.getPrefInt("tgws_port") ?: 1480
        set(value) = cr?.putPref("tgws_port", value) ?: Unit

    @JvmStatic
    var tgwsDcMappings: Map<Int, String>
        get() {
            val s = cr?.getPrefString("tgws_dc_mappings") ?: "2:149.154.167.220\n4:149.154.167.220"
            return s.split("\n").filter { it.contains(":") }.mapNotNull {
                val parts = it.split(":", limit = 2)
                if (parts.size == 2) {
                    val dc = parts[0].trim().toIntOrNull()
                    if (dc != null) dc to parts[1].trim() else null
                } else null
            }.toMap()
        }
        set(value) {
            val s = value.map { "${it.key}:${it.value}" }.joinToString("\n")
            cr?.putPref("tgws_dc_mappings", s)
        }

    @JvmStatic
    var tgwsSecret: String
        get() = cr?.getPrefString("tgws_secret") ?: ""
        set(value) = cr?.putPref("tgws_secret", value) ?: Unit

    @JvmStatic
    var tgwsFakeTls: String
        get() = cr?.getPrefString("tgws_fake_tls") ?: ""
        set(value) = cr?.putPref("tgws_fake_tls", value) ?: Unit

    var tgwsCfWorkerDomains: List<String>
        get() = cr?.getPrefString("tgws_cf_worker_domains")?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
        set(value) = cr?.putPref("tgws_cf_worker_domains", value.joinToString(",")) ?: Unit

    var tgwsCfProxyDomains: List<String>
        get() = cr?.getPrefString("tgws_cf_proxy_domains")?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
        set(value) = cr?.putPref("tgws_cf_proxy_domains", value.joinToString(",")) ?: Unit

    @JvmStatic
    var tgwsFallbackCfProxy: Boolean
        get() = cr?.getPrefBoolean("tgws_fallback_cfproxy", true) ?: true
        set(value) = cr?.putPref("tgws_fallback_cfproxy", value) ?: Unit

    @JvmStatic
    var tgwsCfProxyH2Media: Boolean
        get() = cr?.getPrefBoolean("tgws_cfproxy_h2_media", true) ?: true
        set(value) = cr?.putPref("tgws_cfproxy_h2_media", value) ?: Unit

    @JvmStatic
    var tgwsFrontingSni: String
        get() = cr?.getPrefString("tgws_fronting_sni") ?: ""
        set(value) = cr?.putPref("tgws_fronting_sni", value) ?: Unit

    @JvmStatic
    var tgwsDisableSecure: Boolean
        get() = cr?.getPrefBoolean("tgws_disable_secure", false) ?: false
        set(value) = cr?.putPref("tgws_disable_secure", value) ?: Unit

    @JvmStatic
    var tgwsForceTestDc: Boolean
        get() = cr?.getPrefBoolean("tgws_force_test_dc", false) ?: false
        set(value) = cr?.putPref("tgws_force_test_dc", value) ?: Unit

    @JvmStatic
    var tgwsPoolSize: Int
        get() = cr?.getPrefInt("tgws_pool_size", 4) ?: 4
        set(value) = cr?.putPref("tgws_pool_size", value) ?: Unit
    @JvmStatic
    var operaProxyEnabled: Boolean
        get() = cr?.getPrefBoolean("operaproxy_enabled", false) ?: false
        set(value) = cr?.putPref("operaproxy_enabled", value) ?: Unit

    @JvmStatic
    var operaProxyBindAddress: String
        get() {
            val addr = cr?.getPrefString("operaproxy_bind_address") ?: "127.0.0.1:1785"
            val port = addr.split(":").lastOrNull() ?: "1785"
            return if (openProxyOnAllInterfaces) "0.0.0.0:$port" else "127.0.0.1:$port"
        }
        set(value) = cr?.putPref("operaproxy_bind_address", value) ?: Unit

    @JvmStatic
    var operaProxyUpstream: String
        get() = cr?.getPrefString("operaproxy_upstream") ?: ""
        set(value) = cr?.putPref("operaproxy_upstream", value) ?: Unit

    @JvmStatic
    var operaProxyVerbose: Boolean
        get() = cr?.getPrefBoolean("opera_proxy_verbose", false) ?: false
        set(value) = cr?.putPref("opera_proxy_verbose", value) ?: Unit

    @JvmStatic
    var operaProxyPreset: String
        get() = cr?.getPrefString("operaproxy_preset") ?: "random"
        set(value) = cr?.putPref("operaproxy_preset", value) ?: Unit


    @JvmStatic
    var customSocksIp: String
        get() = cr?.getPrefString("custom_socks_ip") ?: "127.0.0.1"
        set(value) = cr?.putPref("custom_socks_ip", value) ?: Unit

    @JvmStatic
    var customSocksPort: Int
        get() = cr?.getPrefInt("custom_socks_port", 1780) ?: 1780
        set(value) = cr?.putPref("custom_socks_port", value) ?: Unit

    @JvmStatic
    var notificationLogProvider: String
        get() = "none"
        set(value) = cr?.putPref("notification_log_provider", value) ?: Unit
    @JvmStatic
    var useDynamicColors: Boolean
        get() = cr?.getPrefBoolean("use_dynamic_colors", true) ?: true
        set(value) = cr?.putPref("use_dynamic_colors", value) ?: Unit

    @JvmStatic
    var themeMode: Int
        get() = cr?.getPrefInt("theme_mode", -1) ?: -1 // -1 is MODE_NIGHT_FOLLOW_SYSTEM
        set(value) = cr?.putPref("theme_mode", value) ?: Unit

    @JvmStatic
    var byedpiWifiArgs: String
        get() {
            val v = cr?.getPrefString("pref_byedpi_wifi_args")?.trim() ?: ""
            return if (v.isEmpty()) byedpiArgs else v
        }
        set(value) = cr?.putPref("pref_byedpi_wifi_args", value) ?: Unit

    @JvmStatic
    var byedpiMobileArgs: String
        get() {
            val v = cr?.getPrefString("pref_byedpi_mobile_args")?.trim() ?: ""
            return if (v.isEmpty()) byedpiArgs else v
        }
        set(value) = cr?.putPref("pref_byedpi_mobile_args", value) ?: Unit

    @JvmStatic
    fun getCurrentByedpiArgs(context: Context): String {
        val isWifi = NetworkUtils.isWifiConnected(context)
        val preferred = if (isWifi) byedpiWifiArgs else byedpiMobileArgs
        return if (preferred.isNotBlank()) preferred else byedpiArgs
    }

    @JvmStatic
    var customVpnDns: String
        get() = cr?.getPrefString("custom_vpn_dns") ?: ""
        set(value) = cr?.putPref("custom_vpn_dns", value) ?: Unit

    @JvmStatic
    var lastStrategiesUpdate: Long
        get() = cr?.getPrefLong("last_strategies_update", 0L) ?: 0L
        set(value) = cr?.putPref("last_strategies_update", value) ?: Unit

    @JvmStatic
    var lastEasyVpnUpdate: Long
        get() = cr?.getPrefLong("last_easy_vpn_update", 0L) ?: 0L
        set(value) = cr?.putPref("last_easy_vpn_update", value) ?: Unit

    @JvmStatic
    var easyVpnSubs: String
        get() {
            val s = cr?.getPrefString("pref_easy_vpn_subs") ?: return ""
            val defaultUrls = ru.toinet.android.easyvpn.EasyVpnUpdater.DEFAULT_SUBS
            val userLines = s.lines()
                .map { it.trim() }
                .filter { line -> line.isNotEmpty() && defaultUrls.none { def -> line.equals(def, ignoreCase = true) } }
            return userLines.joinToString("\n")
        }
        set(value) {
            val defaultUrls = ru.toinet.android.easyvpn.EasyVpnUpdater.DEFAULT_SUBS
            val cleaned = value.lines()
                .map { it.trim() }
                .filter { line -> line.isNotEmpty() && defaultUrls.none { def -> line.equals(def, ignoreCase = true) } }
                .joinToString("\n")
            cr?.putPref("pref_easy_vpn_subs", cleaned) ?: Unit
        }

    @JvmStatic
    var easyVpnServerNumberOnly: Boolean
        get() = cr?.getPrefBoolean("easyvpn_server_number_only", true) ?: true
        set(value) = cr?.putPref("easyvpn_server_number_only", value) ?: Unit

    @JvmStatic
    var easyVpnProxyEnabled: Boolean
        get() = cr?.getPrefBoolean("easyvpn_proxy_enabled", false) ?: false
        set(value) = cr?.putPref("easyvpn_proxy_enabled", value) ?: Unit

    @JvmStatic
    var easyVpnProxyPort: Int
        get() = cr?.getPrefInt("easyvpn_proxy_port", 1790) ?: 1790
        set(value) = cr?.putPref("easyvpn_proxy_port", value) ?: Unit

    @JvmStatic
    var svpnEnabled: Boolean
        get() = cr?.getPrefBoolean("pref_svpn_enabled", false) ?: false
        set(value) = cr?.putPref("pref_svpn_enabled", value) ?: Unit

    @JvmStatic
    var svpnHost: String
        get() = cr?.getPrefString("pref_svpn_host") ?: ""
        set(value) = cr?.putPref("pref_svpn_host", value) ?: Unit

    @JvmStatic
    var svpnPort: Int
        get() = cr?.getPrefInt("pref_svpn_port", 22) ?: 22
        set(value) = cr?.putPref("pref_svpn_port", value) ?: Unit

    @JvmStatic
    var svpnUser: String
        get() = cr?.getPrefString("pref_svpn_user") ?: "root"
        set(value) = cr?.putPref("pref_svpn_user", value) ?: Unit

    @JvmStatic
    var svpnAuthType: String
        get() = cr?.getPrefString("pref_svpn_auth_type") ?: "password"
        set(value) = cr?.putPref("pref_svpn_auth_type", value) ?: Unit

    @JvmStatic
    var svpnPassword: String
        get() = SecureStorage.decrypt(cr?.getPrefString("pref_svpn_password") ?: "")
        set(value) = cr?.putPref("pref_svpn_password", SecureStorage.encrypt(value)) ?: Unit

    @JvmStatic
    var svpnPrivateKey: String
        get() = SecureStorage.decrypt(cr?.getPrefString("pref_svpn_private_key") ?: "")
        set(value) = cr?.putPref("pref_svpn_private_key", SecureStorage.encrypt(value)) ?: Unit

    @JvmStatic
    var svpnKeyPassphrase: String
        get() = SecureStorage.decrypt(cr?.getPrefString("pref_svpn_key_passphrase") ?: "")
        set(value) = cr?.putPref("pref_svpn_key_passphrase", SecureStorage.encrypt(value)) ?: Unit

    @JvmStatic
    var svpnLocalPort: Int
        get() = cr?.getPrefInt("pref_svpn_local_port", 1770) ?: 1770
        set(value) = cr?.putPref("pref_svpn_local_port", value) ?: Unit

    @JvmStatic
    var svpnDnsServer: String
        get() = cr?.getPrefString("pref_svpn_dns_server") ?: "77.88.8.8"
        set(value) = cr?.putPref("pref_svpn_dns_server", value) ?: Unit

    @JvmStatic
    var svpnDnsPort: Int
        get() = cr?.getPrefInt("pref_svpn_dns_port", 53) ?: 53
        set(value) = cr?.putPref("pref_svpn_dns_port", value) ?: Unit

    @JvmStatic
    var easyVpnProxyHost: String
        get() = if (openProxyOnAllInterfaces) "0.0.0.0" else "127.0.0.1"
        set(_) = Unit

    @JvmStatic
    var vpnBypassLan: Boolean
        get() = true // Зафиксировано в состоянии "Вкл"
        set(value) = cr?.putPref("pref_vpn_bypass_lan", value) ?: Unit

    @JvmStatic
    fun isProviderNeededStandalone(provider: String): Boolean {
        val torNeedsByedpi = torEnabled && proxyEnabled && (
            torUpstreamProvider == "byedpi" ||
                ((torUpstreamProvider.isBlank() || torUpstreamProvider == "none") &&
                    outboundProxy.first?.let { it.host == "127.0.0.1" && it.port == 1780 } == true)
            )
        val torNeedsEasyVpn = torEnabled && proxyEnabled && (
            torUpstreamProvider == "easyvpn" ||
                ((torUpstreamProvider.isBlank() || torUpstreamProvider == "none") &&
                    outboundProxy.first?.let { it.host == "127.0.0.1" && it.port == easyVpnProxyPort } == true)
            )
        return when (provider) {
            "tor" -> torEnabled ||
                (byedpiEnabled && byedpiUpstreamEnabled && byedpiUpstreamProvider == "tor") ||
                (easyVpnProxyEnabled && easyVpnUpstreamEnabled && easyVpnUpstreamProvider == "tor")
            "byedpi" -> byedpiEnabled ||
                (easyVpnProxyEnabled && easyVpnUpstreamEnabled && easyVpnUpstreamProvider == "byedpi") ||
                torNeedsByedpi
            "tgws" -> tgwsEnabled
            "operaproxy" -> ArchHelper.isOperaSupported && operaProxyEnabled
            "easyvpn" -> easyVpnProxyEnabled ||
                (byedpiEnabled && byedpiUpstreamEnabled && byedpiUpstreamProvider == "easyvpn") ||
                torNeedsEasyVpn
            "svpn", "ssh" -> svpnEnabled
            else -> false
        }
    }

    @JvmStatic
    fun isProviderActive(provider: String): Boolean {
        if (provider == "vpn") return isGlobalVpnEnabled
        val vpnNeeded = if (isGlobalVpnEnabled) {
            try {
                ru.toinet.android.vpn.rules.ProxifierRulesManager.getAllNeededVpnProviders()
            } catch (_: Throwable) {
                emptySet()
            }
        } else {
            emptySet()
        }
        val inVpn = when (provider) {
            "operaproxy" -> ArchHelper.isOperaSupported && vpnNeeded.contains("operaproxy")
            "svpn", "ssh" -> vpnNeeded.contains("svpn") || vpnNeeded.contains("ssh")
            "tgws" -> false
            else -> vpnNeeded.contains(provider)
        }
        return inVpn || isProviderNeededStandalone(provider)
    }

    @JvmStatic
    fun isPrimaryNotificationService(serviceKey: String): Boolean {
        if (isGlobalVpnEnabled) {
            return serviceKey == "vpn"
        }
        if (byedpiEnabled) return serviceKey == "byedpi"
        if (easyVpnProxyEnabled) return serviceKey == "easyvpn"
        if (operaProxyEnabled && ArchHelper.isOperaSupported) return serviceKey == "operaproxy"
        if (svpnEnabled) return serviceKey == "svpn" || serviceKey == "ssh"
        if (torEnabled) return serviceKey == "tor"
        if (tgwsEnabled) return serviceKey == "tgws"
        // Fallback when only an implicit upstream is needed standalone
        if (isProviderNeededStandalone("byedpi")) return serviceKey == "byedpi"
        if (isProviderNeededStandalone("easyvpn")) return serviceKey == "easyvpn"
        if (isProviderNeededStandalone("tor")) return serviceKey == "tor"
        return false
    }

    @JvmStatic
    fun shouldServiceShowNotification(serviceKey: String): Boolean {
        return isPrimaryNotificationService(serviceKey)
    }

    @JvmStatic
    fun shouldShowDetailedNotification(serviceKey: String): Boolean {
        return false
    }
}

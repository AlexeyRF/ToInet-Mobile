package ru.toinet.android.service

import androidx.core.net.toUri

object OrbotConstants {
    const val TAG = "Orbot"

    const val PREF_REACHABLE_ADDRESSES = "pref_reachable_addresses"
    const val PREF_REACHABLE_ADDRESSES_PORTS = "pref_reachable_addresses_ports"

    const val PREF_DNSPORT = "pref_dnsport"
    const val PREF_HTTP = "pref_http"
    const val PREF_SOCKS = "pref_socks"
    const val PREF_TRANSPORT = "pref_transport"

    const val PREF_ISOLATE_DEST = "pref_isolate_dest"
    const val PREF_ISOLATE_PORT = "pref_isolate_port"
    const val PREF_ISOLATE_PROTOCOL = "pref_isolate_protocol"
    const val PREF_ISOLATE_KEEP_ALIVE = "pref_isolate_keep_alive"

    const val PREF_CONNECTION_PADDING = "pref_connection_padding"
    const val PREF_REDUCED_CONNECTION_PADDING = "pref_reduced_connection_padding"
    const val PREF_CIRCUIT_PADDING = "pref_circuit_padding"
    const val PREF_REDUCED_CIRCUIT_PADDING = "pref_reduced_circuit_padding"

    const val PREF_PREFER_IPV6 = "pref_prefer_ipv6"
    const val PREF_DISABLE_IPV4 = "pref_disable_ipv4"

    const val APP_TOR_KEY = "_app_tor"

    const val DIRECTORY_TOR_DATA = "tordata"

    // geoip data file asset key
    const val GEOIP_ASSET_KEY = "geoip"
    const val GEOIP6_ASSET_KEY = "geoip6"

    const val HTTP_PROXY_PORT_DEFAULT = "5267"
    const val SOCKS_PROXY_PORT_DEFAULT = "5242"

    const val TOR_DNS_PORT_DEFAULT = 9053
    const val TOR_TRANSPROXY_PORT_DEFAULT = 9040

    // control port
    const val LOG_NOTICE_HEADER = "NOTICE: "
    const val LOG_NOTICE_BOOTSTRAPPED = "Bootstrapped"


    const val ACTION_STOP_FOREGROUND_TASK = "ru.toinet.android.intent.action.STOP_FOREGROUND_TASK"

    const val ACTION_LOCAL_LOCALE_SET = "ru.toinet.android.intent.LOCAL_LOCALE_SET"

    const val ACTION_UPDATE_ONION_NAMES = "ru.toinet.android.intent.action.UPDATE_ONION_NAMES"


    /**
     * The SOCKS proxy settings in URL form.
     */
    const val EXTRA_SOCKS_PROXY = "ru.toinet.android.intent.extra.SOCKS_PROXY"
    const val EXTRA_SOCKS_PROXY_HOST = "ru.toinet.android.intent.extra.SOCKS_PROXY_HOST"
    const val EXTRA_SOCKS_PROXY_PORT = "ru.toinet.android.intent.extra.SOCKS_PROXY_PORT"

    /**
     * The HTTP proxy settings in URL form.
     */
    const val EXTRA_HTTP_PROXY = "ru.toinet.android.intent.extra.HTTP_PROXY"
    const val EXTRA_HTTP_PROXY_HOST = "ru.toinet.android.intent.extra.HTTP_PROXY_HOST"
    const val EXTRA_HTTP_PROXY_PORT = "ru.toinet.android.intent.extra.HTTP_PROXY_PORT"

    const val EXTRA_DNS_PORT = "ru.toinet.android.intent.extra.DNS_PORT"
    const val EXTRA_TRANS_PORT = "ru.toinet.android.intent.extra.TRANS_PORT"

    const val EXTRA_NOT_SYSTEM = "ru.toinet.android.intent.extra.NOT_SYSTEM"

    const val LOCAL_ACTION_LOG = "log"
    const val LOCAL_ACTION_STATUS = "status"
    const val LOCAL_ACTION_BANDWIDTH = "bandwidth"
    const val LOCAL_EXTRA_TOTAL_READ = "totalRead"
    const val LOCAL_EXTRA_TOTAL_WRITTEN = "totalWritten"
    const val LOCAL_EXTRA_LAST_WRITTEN = "lastWritten"
    const val LOCAL_EXTRA_LAST_READ = "lastRead"
    const val LOCAL_EXTRA_LOG = "log"
    const val LOCAL_EXTRA_BOOTSTRAP_PERCENT = "percent"
    const val LOCAL_ACTION_PORTS = "ports"
    const val LOCAL_ACTION_V3_NAMES_UPDATED = "V3_NAMES_UPDATED"




    const val STATUS_STARTS_DISABLED = "STARTS_DISABLED"

    const val CMD_SET_EXIT = "setexit"
    const val CMD_ACTIVE = "ACTIVE"

    const val ONION_SERVICES_DIR = "v3_onion_services"
    const val V3_CLIENT_AUTH_DIR = "v3_client_auth"

    const val PREFS_DNS_PORT: String = "PREFS_DNS_PORT"

    const val PREFS_KEY_TORIFIED: String = "PrefTord"

    const val ONION_EMOJI: String = "\uD83E\uDDC5"

    val GET_BRIDES_BRIDGES_URI = "https://bridges.torproject.org/".toUri()


}

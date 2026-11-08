package ru.toinet.android.dns

enum class DnsProtocol {
    UDP,
    DOT,
    DOH
}

enum class DnsProxyMode {
    VPN,     // routes through the established VPN
    DIRECT,  // bypasses VPN directly to the internet
    CUSTOM   // custom SOCKS5 proxy
}

data class DnsConfig(
    val protocol: DnsProtocol = DnsProtocol.UDP,
    val host: String = "8.8.8.8",
    val port: Int = 53,
    val dohUrl: String = "",
    val proxyMode: DnsProxyMode = DnsProxyMode.VPN,
    val customProxyHost: String = "127.0.0.1",
    val customProxyPort: Int = 1780
)

package ru.toinet.android.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.InetSocketAddress
import java.net.Socket

object NetworkUtils {
    fun isNetworkAvailable(context: Context, allowOtherVpnApps: Boolean = false): Boolean {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        if (allowOtherVpnApps && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return true
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> true
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> true
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> true
            else -> false
        }
    }

    fun isWifiConnected(context: Context): Boolean {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    fun isCellularConnected(context: Context): Boolean {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }

    fun isPortAvailable(port: Int): Boolean {
        return try {
            java.net.ServerSocket(port, 1, java.net.InetAddress.getByName("127.0.0.1")).use { true }
        } catch (_: Exception) {
            false
        }
    }

    fun checkPortOrAuto(portString: String): String {
        if (!portString.equals("auto", ignoreCase = true)) {
            var port = portString.toIntOrNull() ?: return portString
            val startPort = port
            while (!isPortAvailable(port) && port <= 65535) {
                port++
            }
            return if (port <= 65535) port.toString() else startPort.toString()
        }
        return portString
    }

    fun isPortOpen(ip: String?, port: Int, timeout: Int): Boolean {
        try {
            val socket = Socket()
            socket.connect(InetSocketAddress(ip, port), timeout)
            socket.close()
            return true
        } catch (_: Exception) {
            return false
        }
    }

    fun getLocalIpAddress(): String {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces() ?: return "127.0.0.1"
            val candidates = mutableListOf<Pair<String, java.net.InetAddress>>()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                val name = intf.name.lowercase()
                if (intf.isLoopback || name.startsWith("tun") || name.startsWith("dummy") || name.startsWith("p2p")) {
                    continue
                }
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        candidates.add(name to addr)
                    }
                }
            }
            val prioritized = candidates.firstOrNull { (name, _) ->
                name.startsWith("ap") || name.startsWith("wlan") || name.startsWith("swlan") || name.startsWith("rndis") || name.startsWith("eth")
            }
            if (prioritized != null) {
                return prioritized.second.hostAddress ?: "127.0.0.1"
            }
            val lanCandidate = candidates.firstOrNull { (_, addr) ->
                addr.isSiteLocalAddress
            }
            if (lanCandidate != null) {
                return lanCandidate.second.hostAddress ?: "127.0.0.1"
            }
            return candidates.firstOrNull()?.second?.hostAddress ?: "127.0.0.1"
        } catch (_: Exception) {
            return "127.0.0.1"
        }
    }
}
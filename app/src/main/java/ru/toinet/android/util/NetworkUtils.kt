package ru.toinet.android.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.InetSocketAddress
import java.net.Socket

object NetworkUtils {
    fun isNetworkAvailable(context: Context, allowOtherVpnApps: Boolean = false): Boolean {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
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

    fun checkPortOrAuto(portString: String): String {
        if (!portString.equals("auto", ignoreCase = true)) {
            var isPortUsed = true
            var port = portString.toInt()
            while (isPortUsed) {
                isPortUsed = isPortOpen("127.0.0.1", port, 500)
                if (isPortUsed)  //the specified port is not available, so find one instead
                    port++
            }
            return port.toString()
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
package ru.toinet.android.vpn.services

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.lifecycle.lifecycleScope
import ru.toinet.android.R
import ru.toinet.android.ToInetActivity
import ru.toinet.android.byedpi.core.ByeDpiProxy
import ru.toinet.android.byedpi.core.ByeDpiProxyPreferences

import ru.toinet.android.vpn.data.*
import ru.toinet.android.vpn.utility.*
import ru.toinet.android.vpn.utility.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

import ru.toinet.android.vpn.utility.*
class AppVpnService : LifecycleVpnService() {
    private var tunFd: ParcelFileDescriptor? = null
    private var fakeVpnServer: ru.toinet.android.fakevpn.DirectSocks5Server? = null
    private var dnsProxyServer: ru.toinet.android.dns.DnsProxyServer? = null
    private var easyVpnServer: ru.toinet.android.easyvpn.EasyVpnServer? = null
    private var zapretServer: ru.toinet.android.zapret.ZapretServer? = null
    private var operaProxyServer: ru.toinet.android.operaproxy.OperaProxyServer? = null
    private val mutex = Mutex()
    private var stopping: Boolean = false

    companion object {
        private val TAG: String = AppVpnService::class.java.simpleName
        private const val FOREGROUND_SERVICE_ID: Int = 1
        private const val NOTIFICATION_CHANNEL_ID: String = "ByeDPIVpn"

        private var status: ServiceStatus = ServiceStatus.Disconnected
    }


    override fun onCreate() {
        super.onCreate()
        registerNotificationChannel(
            this,
            NOTIFICATION_CHANNEL_ID,
            R.string.vpn_channel_name,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val action = intent?.action

        if (action == START_ACTION || (action == "ACTIVE" && status == ServiceStatus.Connected)) {
            try {
                startForeground()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start foreground in onStartCommand", e)
            }
        }

        return when (action) {
            START_ACTION -> {
                lifecycleScope.launch { start() }
                START_STICKY
            }

            STOP_ACTION -> {
                lifecycleScope.launch { stop() }
                START_NOT_STICKY
            }

            "ACTIVE" -> {
                START_STICKY
            }

            else -> {
                if (action != null) Log.w(TAG, "Unknown action: $action")
                START_NOT_STICKY
            }
        }
    }

    override fun onRevoke() {
        Log.i(TAG, "VPN revoked")
        lifecycleScope.launch { stop() }
    }

    private suspend fun start() {
        Log.i(TAG, "Starting")


        if (status == ServiceStatus.Connected) {
            Log.w(TAG, "VPN already connected")
            startForeground()
            return
        }

        try {
            startForeground()
            mutex.withLock {
                startTun2Socks()
            }
            updateStatus(ServiceStatus.Connected)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VPN", e)
            updateStatus(ServiceStatus.Failed)
            stop()
        }
    }

    private fun startForeground() {
        val notification: Notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                FOREGROUND_SERVICE_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(FOREGROUND_SERVICE_ID, notification)
        }
    }

    private suspend fun stop() {
        Log.i(TAG, "Stopping")

        mutex.withLock {
            stopping = true
            try {
                stopTun2Socks()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop VPN", e)
            } finally {
                stopping = false
            }
        }

        updateStatus(ServiceStatus.Disconnected)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
        stopSelf()
    }



    private fun startTun2Socks() {
        Log.i(TAG, "Starting tun2socks")

        if (tunFd != null) {
            throw IllegalStateException("VPN field not null")
        }

        val sharedPreferences = getPreferences()
        val provider = ru.toinet.android.util.Prefs.vpnProvider
        var actualHost = "127.0.0.1"
        var actualPort = 1080
        when (provider) {
            "byedpi" -> actualPort = getByeDpiPreferences().port
            "tor" -> actualPort = 5242
            "tgws" -> actualPort = ru.toinet.android.util.Prefs.tgwsPort
            "rehab" -> actualPort = 1788
            // "turnproxy" -> actualPort = ru.toinet.android.util.Prefs.turnProxyLocalPort
            "fakevpn" -> actualPort = 1790
            "operaproxy" -> {
                actualHost = "127.0.0.1"
                actualPort = ru.toinet.android.util.Prefs.operaProxyBindAddress.split(":").last().toIntOrNull() ?: 1888
            }
            "easyvpn" -> actualPort = 1791
            "zapret" -> actualPort = ru.toinet.android.util.Prefs.zapretPort
            "custom" -> {
                actualHost = ru.toinet.android.util.Prefs.customSocksIp
                actualPort = ru.toinet.android.util.Prefs.customSocksPort
            }
            else -> actualPort = getByeDpiPreferences().port
        }
        var socksAddr = "$actualHost:$actualPort"

        if (provider == "easyvpn") {
            easyVpnServer = ru.toinet.android.easyvpn.EasyVpnServer(this, lifecycleScope)
            easyVpnServer?.start()
        }

        if (provider == "fakevpn") {
            fakeVpnServer = ru.toinet.android.fakevpn.DirectSocks5Server(
                java.net.InetSocketAddress("127.0.0.1", 1790)
            )
            fakeVpnServer?.start(lifecycleScope)
        }

        if (provider == "operaproxy") {
            operaProxyServer = ru.toinet.android.operaproxy.OperaProxyServer(this, lifecycleScope)
            operaProxyServer?.start()
        }

        if (provider == "zapret") {
            zapretServer = ru.toinet.android.zapret.ZapretServer(this, lifecycleScope)
            zapretServer?.start()
        }

        var dns = sharedPreferences.getStringNotNull("dns_ip", "208.67.222.222")
        // Custom VPN DNS commented out to avoid breaking pdnsd tcp queries:
        // if ((provider == "fakevpn" || provider == "byedpi" || provider == "zapret") && ru.toinet.android.util.Prefs.customVpnDns.isNotBlank()) {
        //     dns = ru.toinet.android.util.Prefs.customVpnDns
        // }

        val ipv6 = false

        val fd = createBuilder("8.8.8.8", ipv6).establish()
            ?: throw IllegalStateException("VPN connection failed")

        this.tunFd = fd

        ru.toinet.android.vpn.utility.VpnUtility.makePdnsdConf(this, dns, 53)

        val libDir = applicationInfo.nativeLibraryDir
        val dir = filesDir.absolutePath

        ru.toinet.android.vpn.utility.VpnUtility.exec(arrayOf(
            "$libDir/libpdnsd.so",
            "-c",
            "$dir/pdnsd.conf"
        ))

        val command = mutableListOf(
            "$libDir/libtun2socks.so",
            "--netif-ipaddr", "26.26.26.2",
            "--netif-netmask", "255.255.255.0",
            "--socks-server-addr", socksAddr,
            "--tunfd", fd.fd.toString(),
            "--tunmtu", "1500",
            "--loglevel", "3",
            "--pid", "$dir/tun2socks.pid",
            "--dnsgw", "26.26.26.1:8091"
        )
        
        if (ipv6) {
            command.add("--netif-ip6addr")
            command.add("fdfe:dcba:9876::2")
        }

        if (ru.toinet.android.vpn.utility.VpnUtility.exec(command.toTypedArray()) != 0) {
            stopTun2Socks()
            throw IllegalStateException("Failed to start tun2socks")
        }

        var i = 0
        var success = false
        while (i < 5) {
            if (ru.toinet.android.vpn.utility.VpnSystem.sendfd(fd.fd) != -1) {
                success = true
                break
            }
            i++
            try {
                Thread.sleep(1000L * i)
            } catch (e: Exception) {}
        }
        if (!success) {
            stopTun2Socks()
            throw IllegalStateException("Failed to send fd")
        }

        Log.i(TAG, "Tun2Socks started")
    }

    private fun stopTun2Socks() {
        Log.i(TAG, "Stopping tun2socks")

        val dir = filesDir.absolutePath
        ru.toinet.android.vpn.utility.VpnUtility.killPidFile("$dir/tun2socks.pid")



        tunFd?.close() ?: Log.w(TAG, "VPN not running")
        tunFd = null

        easyVpnServer?.stop()
        easyVpnServer = null

        fakeVpnServer?.stop()
        fakeVpnServer = null
        
        operaProxyServer?.stop()
        operaProxyServer = null

        if (ru.toinet.android.operaproxy.OperaProxyService.isRunning) {
            ru.toinet.android.operaproxy.OperaProxyService.stop(this)
        }
        
        dnsProxyServer?.stop()
        dnsProxyServer = null

        zapretServer?.stop()
        zapretServer = null

        if (ru.toinet.android.zapret.ZapretService.isRunning) {
            ru.toinet.android.zapret.ZapretService.stop(this)
        }

        Log.i(TAG, "Tun2socks stopped")
    }


    private fun getByeDpiPreferences(): ByeDpiProxyPreferences =
        ByeDpiProxyPreferences.fromSharedPreferences(getPreferences())

    private fun updateStatus(newStatus: ServiceStatus) {
        Log.d(TAG, "VPN status changed from $status to $newStatus")

        status = newStatus

        setStatus(
            when (newStatus) {
                ServiceStatus.Connected -> AppStatus.Running

                ServiceStatus.Disconnected,
                ServiceStatus.Failed -> {
                    AppStatus.Halted
                }
            },
            Mode.VPN
        )

        if (!ru.toinet.android.util.Prefs.torEnabled) {
            val toinetStatus = when (newStatus) {
                ServiceStatus.Connected -> org.torproject.jni.TorService.STATUS_ON
                ServiceStatus.Disconnected, ServiceStatus.Failed -> org.torproject.jni.TorService.STATUS_OFF
            }
            val statusIntent = Intent(ru.toinet.android.service.ToInetConstants.LOCAL_ACTION_STATUS)
            statusIntent.putExtra(org.torproject.jni.TorService.EXTRA_STATUS, toinetStatus)
            statusIntent.setPackage(packageName)
            sendBroadcast(statusIntent)
        }

        val intent = Intent(
            when (newStatus) {
                ServiceStatus.Connected -> STARTED_BROADCAST
                ServiceStatus.Disconnected -> STOPPED_BROADCAST
                ServiceStatus.Failed -> FAILED_BROADCAST
            }
        )
        intent.putExtra(SENDER, Sender.VPN.ordinal)
        sendBroadcast(intent)
    }

    private fun createNotification(): Notification {
        val provider = ru.toinet.android.util.Prefs.vpnProvider
        val title = when (provider) {
            "easyvpn" -> "EasyVPN"
            "zapret" -> "Zapret"
            "operaproxy" -> "Opera Proxy"
            else -> getString(R.string.byedpi_notification_title)
        }
        
        return createConnectionNotificationString(
            this,
            NOTIFICATION_CHANNEL_ID,
            title,
            getString(R.string.vpn_notification_content),
            AppVpnService::class.java,
        )
    }

    private fun createBuilder(dns: String, ipv6: Boolean): Builder {
        Log.d(TAG, "DNS: $dns")
        val builder = Builder()
        val provider = ru.toinet.android.util.Prefs.vpnProvider
        val sessionName = when (provider) {
            "easyvpn" -> "EasyVPN"
            "zapret" -> "Zapret"
            "operaproxy" -> "Opera Proxy"
            else -> "ByeDPI"
        }
        builder.setSession(sessionName)
        builder.setConfigureIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, ToInetActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
        )

        builder.addAddress("26.26.26.1", 24)
            .addRoute("0.0.0.0", 0)

        if (ipv6) {
            builder.addAddress("fdfe:dcba:9876::1", 126)
                .addRoute("::", 0)
        }

        if (dns.isNotBlank()) {
            builder.addDnsServer(dns)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        try {
            val pm = packageManager
            val packages = pm.getInstalledPackages(0)
            val excludedByUser = ru.toinet.android.util.Prefs.vpnExcludedApps.toMutableSet()
            var needsSave = false
            
            for (pkg in packages) {
                val pkgName = pkg.packageName
                if (!ru.toinet.android.util.Prefs.vpnAppsInitialized) {
                    if (pkgName.contains("ru.", ignoreCase = true) || 
                        pkgName.contains(".ru", ignoreCase = true) || 
                        pkgName.contains("toinet", ignoreCase = true)) {
                        excludedByUser.add(pkgName)
                        needsSave = true
                    }
                }
                
                if (excludedByUser.contains(pkgName)) {
                    try {
                        builder.addDisallowedApplication(pkgName)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to disallow app: $pkgName", e)
                    }
                }
            }
            if (needsSave) {
                ru.toinet.android.util.Prefs.vpnExcludedApps = excludedByUser
                ru.toinet.android.util.Prefs.vpnAppsInitialized = true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to configure split tunneling", e)
        }


        try {
            builder.addDisallowedApplication(applicationContext.packageName)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to disallow self: ${applicationContext.packageName}", e)
        }

        return builder
    }
}



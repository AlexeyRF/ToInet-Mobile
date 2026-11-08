package ru.toinet.android.vpn.services

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
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
import ru.toinet.android.util.ArchHelper
import ru.toinet.android.util.sendIntentToService
class AppVpnService : LifecycleVpnService() {
    private var tunFd: ParcelFileDescriptor? = null
    private var fakeVpnServer: ru.toinet.android.fakevpn.DirectSocks5Server? = null
    private var dnsProxyServer: ru.toinet.android.dns.DnsProxyServer? = null
    private var easyVpnServer: ru.toinet.android.easyvpn.EasyVpnServer? = null
    private var operaProxyServer: ru.toinet.android.operaproxy.OperaProxyServer? = null
    private var svpnServer: ru.toinet.android.svpn.SvpnServer? = null
    private var appRoutingServer: ru.toinet.android.vpn.rules.AppRoutingServer? = null
    private val mutex = Mutex()
    private var stopping: Boolean = false

    companion object {
        private val TAG: String = AppVpnService::class.java.simpleName
        private const val FOREGROUND_SERVICE_ID: Int = 1
        private const val NOTIFICATION_CHANNEL_ID: String = "ToInetVpn"
        const val ACTION_SWITCH_SERVER: String = "ru.toinet.android.vpn.SWITCH_SERVER"

        private var status: ServiceStatus = ServiceStatus.Disconnected

        @Volatile
        var activeService: AppVpnService? = null
            private set

        @Volatile
        var currentLogMessage: String? = null

        @Volatile
        private var lastLogNotificationTime: Long = 0L

        @JvmStatic
        fun updateLogNotification(context: Context, logMessage: String) {
            currentLogMessage = logMessage
            val service = activeService ?: return
            val now = System.currentTimeMillis()
            if (logMessage.isNotBlank() && now - lastLogNotificationTime < 1000L) {
                return
            }
            lastLogNotificationTime = now
            if (status == ServiceStatus.Connected && ru.toinet.android.util.Prefs.shouldServiceShowNotification("vpn")) {
                try {
                    val nm = service.getSystemService(android.app.NotificationManager::class.java)
                    nm?.notify(FOREGROUND_SERVICE_ID, service.createNotification())
                } catch (_: Exception) {}
            }
        }
    }


    override fun onCreate() {
        super.onCreate()
        activeService = this
        registerNotificationChannel(
            this,
            NOTIFICATION_CHANNEL_ID,
            R.string.vpn_channel_name,
        )
    }

    override fun onDestroy() {
        if (activeService == this) {
            activeService = null
        }
        currentLogMessage = null
        super.onDestroy()
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
                currentLogMessage = null
                ru.toinet.android.util.NotificationLogger.lastActiveVpnLog = null
                sendLocalStatus(org.torproject.jni.TorService.STATUS_STARTING)
                ru.toinet.android.util.NotificationLogger.log(this, ru.toinet.android.util.Prefs.vpnProvider, "Подключение к VPN (${ru.toinet.android.util.Prefs.vpnProvider})...")
                lifecycleScope.launch { start() }
                START_STICKY
            }

            STOP_ACTION -> {
                lifecycleScope.launch { stop() }
                START_NOT_STICKY
            }

            "ACTIVE" -> {
                if (status == ServiceStatus.Connected) {
                    startForeground()
                }
                START_STICKY
            }

            ACTION_SWITCH_SERVER -> {
                lifecycleScope.launch {
                    easyVpnServer?.switchToNextServer()
                    if (ru.toinet.android.util.Prefs.shouldServiceShowNotification("vpn")) {
                        val nm = getSystemService(android.app.NotificationManager::class.java)
                        nm?.notify(FOREGROUND_SERVICE_ID, createNotification())
                    }
                }
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
                withContext(Dispatchers.IO) {
                    startTun2Socks()
                }
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
            try {
                startForeground(
                    FOREGROUND_SERVICE_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed specialUse startForeground, fallback to standard", e)
                try {
                    startForeground(FOREGROUND_SERVICE_ID, notification)
                } catch (e2: Exception) {
                    Log.e(TAG, "Failed fallback startForeground", e2)
                }
            }
        } else {
            startForeground(FOREGROUND_SERVICE_ID, notification)
        }
    }

    private suspend fun stop() {
        Log.i(TAG, "Stopping")

        mutex.withLock {
            stopping = true
            try {
                withContext(Dispatchers.IO) {
                    stopTun2Socks()
                }
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
        val nm = getSystemService(android.app.NotificationManager::class.java)
        nm?.cancel(FOREGROUND_SERVICE_ID)
        stopSelf()
    }



    private fun startTun2Socks() {
        Log.i(TAG, "Starting tun2socks")

        if (tunFd != null) {
            Log.w(TAG, "Previous tunFd was not null, closing it safely")
            try {
                tunFd?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to close previous tunFd", e)
            }
            tunFd = null
        }

        val dir = filesDir.absolutePath
        val libDir = applicationInfo.nativeLibraryDir

        // Clean up any stale instances before starting
        ru.toinet.android.vpn.utility.VpnUtility.killPidFile("$dir/tun2socks.pid")
        ru.toinet.android.vpn.utility.VpnUtility.killPidFile("$dir/pdnsd.pid")

        val sharedPreferences = getPreferences()
        val isAppRouting = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && ru.toinet.android.util.Prefs.appRoutingEnabled
        val provider = ru.toinet.android.util.Prefs.vpnProvider
        var actualHost = "127.0.0.1"
        var actualPort = 1780
        when (provider) {
            "byedpi" -> actualPort = getByeDpiPreferences().port
            "tor" -> actualPort = 9853
            "fakevpn" -> actualPort = 1790
            "operaproxy" -> {
                actualHost = "127.0.0.1"
                actualPort = ru.toinet.android.util.Prefs.operaProxyBindAddress.split(":").last().toIntOrNull() ?: 1785
            }
            "easyvpn" -> actualPort = ru.toinet.android.util.Prefs.easyVpnProxyPort
            "svpn", "ssh" -> actualPort = ru.toinet.android.util.Prefs.svpnLocalPort
            else -> actualPort = getByeDpiPreferences().port
        }
        var socksAddr = "$actualHost:$actualPort"

        val neededProviders: MutableSet<String> = if (isAppRouting) {
            val set = ru.toinet.android.vpn.rules.ProxifierRulesManager.getNeededProviders().toMutableSet()
            set.add(provider)
            set
        } else {
            mutableSetOf(provider)
        }

        if (neededProviders.contains("tor") && ru.toinet.android.util.Prefs.proxyEnabled) {
            val u = ru.toinet.android.util.Prefs.torUpstreamProvider
            if (u != "none" && u != "custom" && u.isNotBlank()) {
                neededProviders.add(u)
            }
        }
        if (neededProviders.contains("easyvpn") && ru.toinet.android.util.Prefs.easyVpnUpstreamEnabled) {
            val u = ru.toinet.android.util.Prefs.easyVpnUpstreamProvider
            if (u != "none" && u != "custom" && u.isNotBlank()) {
                neededProviders.add(u)
            }
        }
        if (neededProviders.contains("byedpi") && ru.toinet.android.util.Prefs.byedpiUpstreamEnabled) {
            val u = ru.toinet.android.util.Prefs.byedpiUpstreamProvider
            if (u != "none" && u != "custom" && u.isNotBlank()) {
                neededProviders.add(u)
            }
        }

        if (isAppRouting) {
            appRoutingServer = ru.toinet.android.vpn.rules.AppRoutingServer(this, lifecycleScope).apply {
                start()
            }
        }

        if (neededProviders.contains("byedpi")) {
            val byedpiIntent = Intent(this, ru.toinet.android.byedpi.services.ByeDpiProxyService::class.java).apply {
                action = ru.toinet.android.vpn.data.START_ACTION
            }
            sendIntentToService(byedpiIntent, isForeground = false)
        }
        if (neededProviders.contains("easyvpn")) {
            if (ru.toinet.android.easyvpn.EasyVpnService.isRunning) {
                ru.toinet.android.easyvpn.EasyVpnService.stop(this)
                try {
                    Thread.sleep(100)
                } catch (_: Exception) {}
            }
            easyVpnServer = ru.toinet.android.easyvpn.EasyVpnServer(this, lifecycleScope).apply {
                onServerChanged = {
                    if (ru.toinet.android.util.Prefs.shouldServiceShowNotification("vpn")) {
                        val nm = getSystemService(android.app.NotificationManager::class.java)
                        nm?.notify(FOREGROUND_SERVICE_ID, createNotification())
                    }
                }
            }
            easyVpnServer?.start()
        }

        if (neededProviders.contains("fakevpn")) {
            fakeVpnServer = ru.toinet.android.fakevpn.DirectSocks5Server(
                java.net.InetSocketAddress("127.0.0.1", 1790)
            )
            fakeVpnServer?.start(lifecycleScope)
        }

        if (neededProviders.contains("operaproxy") && ArchHelper.isOperaSupported) {
            operaProxyServer = ru.toinet.android.operaproxy.OperaProxyServer(this, lifecycleScope)
            operaProxyServer?.start()
        }
        if (neededProviders.contains("svpn") || neededProviders.contains("ssh")) {
            val server = ru.toinet.android.svpn.SvpnServer(this, lifecycleScope)
            svpnServer = server
            if (provider == "svpn" || provider == "ssh") {
                val ok = server.startAndWait(12000L)
                if (!ok) {
                    val errMsg = server.lastErrorMessage ?: "Не удалось подключиться к SSH серверу"
                    ru.toinet.android.util.NotificationLogger.log(this, "svpn", "Ошибка SVPN: $errMsg")
                    throw IllegalStateException(errMsg)
                }
            } else {
                server.start()
            }
        }

        if (neededProviders.contains("tor")) {
            val torIntent = Intent(this, ru.toinet.android.service.ToInetService::class.java).apply {
                action = org.torproject.jni.TorService.ACTION_START
            }
            sendIntentToService(torIntent, isForeground = false)
        }

        var dns = sharedPreferences.getStringNotNull("dns_ip", "208.67.222.222")
        if (provider == "tor" && dns == "208.67.222.222") {
            dns = "1.1.1.1"
        }
        if ((provider == "svpn" || provider == "ssh") && ru.toinet.android.util.Prefs.svpnDnsServer.isNotBlank()) {
            dns = ru.toinet.android.util.Prefs.svpnDnsServer
        }
        if (dns.isBlank()) dns = "8.8.8.8"

        val hasExternalIpv6 = checkExternalIpv6()
        Log.i(TAG, "External IPv6 present: $hasExternalIpv6 (route v6: $hasExternalIpv6, block v6 if false)")

        val fd = createBuilder(dns, hasExternalIpv6).establish()
            ?: throw IllegalStateException("VPN connection failed")

        this.tunFd = fd

        val dnsPort = if (provider == "svpn" || provider == "ssh") ru.toinet.android.util.Prefs.svpnDnsPort else 53
        ru.toinet.android.vpn.utility.VpnUtility.makePdnsdConf(this, dns, dnsPort)

        ru.toinet.android.vpn.utility.VpnUtility.exec(arrayOf(
            "$libDir/libpdnsd.so",
            "-c",
            "$dir/pdnsd.conf"
        ))

        val command = if (isAppRouting) {
            mutableListOf(
                "$libDir/libtun2socks.so",
                "--netif-ipaddr", "26.26.26.2",
                "--netif-netmask", "255.255.255.0",
                "--socks-server-addr", "127.0.0.1:10850",
                "--tunfd", fd.fd.toString(),
                "--tunmtu", "1500",
                "--loglevel", "3",
                "--pid", "$dir/tun2socks.pid",
                "--dnsgw", "26.26.26.1:8091",
                "--username", "toinet",
                "--password", "toinet",
                "--append-source-to-username"
            )
        } else {
            mutableListOf(
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
        }
        
        if (hasExternalIpv6) {
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
        ru.toinet.android.vpn.utility.VpnUtility.killPidFile("$dir/pdnsd.pid")

        tunFd?.close() ?: Log.w(TAG, "VPN not running")
        tunFd = null

        appRoutingServer?.stop()
        appRoutingServer = null

        val isAppRouting = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && ru.toinet.android.util.Prefs.appRoutingEnabled
        val stoppedProviders: Set<String> = if (isAppRouting) {
            val set = ru.toinet.android.vpn.rules.ProxifierRulesManager.getNeededProviders().toMutableSet()
            set.add(ru.toinet.android.util.Prefs.vpnProvider)
            set
        } else {
            setOf(ru.toinet.android.util.Prefs.vpnProvider)
        }

        if (stoppedProviders.contains("byedpi") && !ru.toinet.android.util.Prefs.byedpiEnabled) {
            val byedpiIntent = Intent(this, ru.toinet.android.byedpi.services.ByeDpiProxyService::class.java).apply {
                action = ru.toinet.android.vpn.data.STOP_ACTION
            }
            try {
                startService(byedpiIntent)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to stop ByeDpiProxyService", e)
            }
        }

        if (stoppedProviders.contains("tor") && !ru.toinet.android.util.Prefs.torEnabled) {
            val torIntent = Intent(this, ru.toinet.android.service.ToInetService::class.java).apply {
                action = org.torproject.jni.TorService.ACTION_STOP
            }
            try {
                startService(torIntent)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to stop ToInetService", e)
            }
        }

        /*
        if (provider == "tgws" && !ru.toinet.android.util.Prefs.tgwsEnabled) {
            ru.toinet.android.tgws.TgwsService.stop(this)
        }
        */

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
        svpnServer?.stop()
        svpnServer = null

        if (ru.toinet.android.svpn.SvpnService.isRunning) {
            ru.toinet.android.svpn.SvpnService.stop(this)
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
            sendLocalStatus(toinetStatus)
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

    private fun sendLocalStatus(torStatus: String) {
        if (!ru.toinet.android.util.Prefs.torEnabled) {
            val statusIntent = Intent(ru.toinet.android.service.ToInetConstants.LOCAL_ACTION_STATUS).apply {
                putExtra(org.torproject.jni.TorService.EXTRA_STATUS, torStatus)
                setPackage(packageName)
            }
            sendBroadcast(statusIntent)
        }
    }

    private fun createNotification(): Notification {
        val isAddressStatus = ru.toinet.android.util.Prefs.notificationLogProvider == "address_status"
        val isAppRouting = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && ru.toinet.android.util.Prefs.appRoutingEnabled
        val provider = ru.toinet.android.util.Prefs.vpnProvider
        val title = if (isAddressStatus) {
            "Состояние Адреса"
        } else if (isAppRouting) "ToInet (Proxifier)" else when (provider) {
            "easyvpn" -> "EasyVPN"
            "svpn", "ssh" -> "SVPN (SSH)"
            "operaproxy" -> "Opera Proxy"
            "fakevpn" -> "FakeVPN"
            "tor" -> "TOR"
            "byedpi" -> getString(R.string.byedpi_notification_title)
            else -> "VPN"
        }
        
        val defaultDesc = if (isAddressStatus) {
            ru.toinet.android.util.AddressStatusHelper.getAddressStatus(this)
        } else if (ru.toinet.android.util.Prefs.notificationLogProvider == "none") {
            ""
        } else if (isAppRouting) "Раздельное туннелирование по правилам" else when (provider) {
            "easyvpn" -> easyVpnServer?.getCurrentServerDescription() ?: getString(R.string.vpn_notification_content)
            "tor" -> "Tor VPN запущен"
            "svpn", "ssh" -> "SVPN (SSH) запущен"
            "operaproxy" -> "Opera Proxy VPN запущен"
            "byedpi" -> "ByeDPI VPN запущен"
            "fakevpn" -> "FakeVPN запущен"
            else -> getString(R.string.vpn_notification_content)
        }
        val content = defaultDesc

        val switchIntent = if (provider == "easyvpn") {
            PendingIntent.getService(
                this,
                101,
                Intent(this, AppVpnService::class.java).setAction(ACTION_SWITCH_SERVER),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        } else {
            null
        }

        return createConnectionNotificationString(
            this,
            NOTIFICATION_CHANNEL_ID,
            title,
            content,
            AppVpnService::class.java,
            switchIntent,
        )
    }

    private fun createBuilder(dns: String, ipv6: Boolean): Builder {
        Log.d(TAG, "DNS: $dns")
        val builder = Builder()
        val provider = ru.toinet.android.util.Prefs.vpnProvider
        val sessionName = when (provider) {
            "easyvpn" -> "EasyVPN"
            "svpn", "ssh" -> "SVPN (SSH)"
            "operaproxy" -> "Opera Proxy"
            "fakevpn" -> "FakeVPN"
            "tor" -> "TOR"
            "byedpi" -> "ByeDPI"
            else -> "VPN"
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

        if (ru.toinet.android.util.Prefs.vpnBypassLan || ru.toinet.android.util.Prefs.openProxyOnAllInterfaces) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                builder.addRoute("0.0.0.0", 0)
                try {
                    builder.excludeRoute(android.net.IpPrefix(java.net.InetAddress.getByName("10.0.0.0"), 8))
                    builder.excludeRoute(android.net.IpPrefix(java.net.InetAddress.getByName("172.16.0.0"), 12))
                    builder.excludeRoute(android.net.IpPrefix(java.net.InetAddress.getByName("192.168.0.0"), 16))
                    builder.excludeRoute(android.net.IpPrefix(java.net.InetAddress.getByName("169.254.0.0"), 16))
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to exclude LAN routes", e)
                }
            } else {
                val nonLanRoutes = listOf(
                    Pair("1.0.0.0", 8),
                    Pair("2.0.0.0", 7),
                    Pair("4.0.0.0", 6),
                    Pair("8.0.0.0", 7),
                    Pair("11.0.0.0", 8),
                    Pair("12.0.0.0", 6),
                    Pair("16.0.0.0", 4),
                    Pair("32.0.0.0", 3),
                    Pair("64.0.0.0", 2),
                    Pair("128.0.0.0", 3),
                    Pair("160.0.0.0", 5),
                    Pair("168.0.0.0", 6),
                    Pair("172.0.0.0", 12),
                    Pair("172.32.0.0", 11),
                    Pair("172.64.0.0", 10),
                    Pair("172.128.0.0", 9),
                    Pair("173.0.0.0", 8),
                    Pair("174.0.0.0", 7),
                    Pair("176.0.0.0", 4),
                    Pair("192.0.0.0", 9),
                    Pair("192.128.0.0", 11),
                    Pair("192.160.0.0", 13),
                    Pair("192.169.0.0", 16),
                    Pair("192.170.0.0", 15),
                    Pair("192.172.0.0", 14),
                    Pair("192.176.0.0", 12),
                    Pair("192.192.0.0", 10),
                    Pair("193.0.0.0", 8),
                    Pair("194.0.0.0", 7),
                    Pair("196.0.0.0", 6),
                    Pair("200.0.0.0", 5),
                    Pair("208.0.0.0", 4)
                )
                for ((ip, prefix) in nonLanRoutes) {
                    try {
                        builder.addRoute(ip, prefix)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to add route $ip/$prefix", e)
                    }
                }
            }
        } else {
            builder.addRoute("0.0.0.0", 0)
        }

        try {
            // Always route IPv6 into TUN: if external IPv6 exists, tun2socks forwards it;
            // if IPv4-only, tun2socks omits --netif-ip6addr so all IPv6 is blackholed/blocked, preventing leaks.
            builder.addAddress("fdfe:dcba:9876::1", 126)
                .addRoute("::", 0)
            if (ipv6) {
                builder.addDnsServer("2606:4700:4700::1111")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to configure IPv6 on VPN interface", e)
        }

        if (dns.isNotBlank()) {
            builder.addDnsServer(dns)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        val isAppRouting = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && ru.toinet.android.util.Prefs.appRoutingEnabled
        if (!isAppRouting) {
            try {
                val pm = packageManager
                val packages = pm.getInstalledPackages(0)
                val excludedByUser = ru.toinet.android.util.Prefs.vpnExcludedApps.toMutableSet()
                var needsSave = false

                if (!ru.toinet.android.util.Prefs.vpnLoopProtectionInitialized) {
                    for (pkg in packages) {
                        val pkgName = pkg.packageName
                        if (ru.toinet.android.util.Prefs.isLockedVpnPackage(pkgName)) {
                            excludedByUser.add(pkgName)
                            needsSave = true
                        }
                    }
                    ru.toinet.android.util.Prefs.vpnLoopProtectionInitialized = true
                }

                if (!ru.toinet.android.util.Prefs.vpnAppsInitialized) {
                    for (pkg in packages) {
                        val pkgName = pkg.packageName
                        if (pkgName.contains("ru.", ignoreCase = true) || 
                            pkgName.contains(".ru", ignoreCase = true)) {
                            excludedByUser.add(pkgName)
                            needsSave = true
                        }
                    }
                    ru.toinet.android.util.Prefs.vpnAppsInitialized = true
                    needsSave = true
                }

                for (disallowedPkg in excludedByUser) {
                    try {
                        builder.addDisallowedApplication(disallowedPkg)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to disallow app: $disallowedPkg", e)
                    }
                }

                if (needsSave) {
                    ru.toinet.android.util.Prefs.vpnExcludedApps = excludedByUser
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to configure split tunneling", e)
            }
        }

        try {
            builder.addDisallowedApplication(applicationContext.packageName)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to disallow self: ${applicationContext.packageName}", e)
        }

        return builder
    }

    private fun checkExternalIpv6(): Boolean {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
                val activeNet = cm?.activeNetwork
                val linkProps = activeNet?.let { cm.getLinkProperties(it) }
                if (linkProps != null) {
                    for (linkAddr in linkProps.linkAddresses) {
                        if (isGlobalIpv6(linkAddr.address)) {
                            return true
                        }
                    }
                }
            }

            val interfaces = java.net.NetworkInterface.getNetworkInterfaces() ?: return false
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback) continue
                val name = iface.name.lowercase()
                if (name.startsWith("tun") || name.startsWith("dummy") || name.startsWith("tap") || name.startsWith("p2p")) continue

                for (addr in iface.inetAddresses) {
                    if (isGlobalIpv6(addr)) {
                        return true
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error checking external IPv6: ${e.message}")
        }
        return false
    }

    private fun isGlobalIpv6(addr: java.net.InetAddress?): Boolean {
        if (addr !is java.net.Inet6Address) return false
        if (addr.isLoopbackAddress || addr.isLinkLocalAddress || addr.isSiteLocalAddress ||
            addr.isAnyLocalAddress || addr.isMulticastAddress || addr.isIPv4CompatibleAddress) {
            return false
        }
        val firstByte = addr.address[0].toInt() and 0xff
        // Exclude ULA (fc00::/7)
        if ((firstByte and 0xfe) == 0xfc) {
            return false
        }
        return true
    }
}

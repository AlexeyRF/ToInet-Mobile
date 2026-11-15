package ru.toinet.android.vpn.services

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.lifecycle.lifecycleScope
import ru.toinet.android.R
import ru.toinet.android.ToInetActivity
import ru.toinet.android.byedpi.core.ByeDpiProxy
import ru.toinet.android.byedpi.core.ByeDpiProxyPreferences

import ru.toinet.android.vpn.data.*
import ru.toinet.android.vpn.utility.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import ru.toinet.android.util.ArchHelper
import ru.toinet.android.util.sendIntentToService
class AppVpnService : LifecycleVpnService() {
    private var tunFd: ParcelFileDescriptor? = null
    private var dnsProxyServer: ru.toinet.android.dns.DnsProxyServer? = null
    private var easyVpnServer: ru.toinet.android.easyvpn.EasyVpnServer? = null
    private var operaProxyServer: ru.toinet.android.operaproxy.OperaProxyServer? = null
    private var svpnServer: ru.toinet.android.svpn.SvpnServer? = null
    private var appRoutingServer: ru.toinet.android.vpn.rules.AppRoutingServer? = null
    private val mutex = Mutex()
    @Volatile
    private var stopping: Boolean = false
    private var watchdogJob: Job? = null

    companion object {
        private val TAG: String = AppVpnService::class.java.simpleName
        private const val FOREGROUND_SERVICE_ID: Int = 1
        private const val NOTIFICATION_CHANNEL_ID: String = "ToInetVpn"
        const val ACTION_SWITCH_SERVER: String = "ru.toinet.android.vpn.SWITCH_SERVER"
        const val ACTION_SWITCH_TOR_NODE: String = "ru.toinet.android.vpn.SWITCH_TOR_NODE"

        @Volatile
        private var status: ServiceStatus = ServiceStatus.Disconnected

        @Volatile
        var activeService: AppVpnService? = null
            private set

        val isConnected: Boolean
            get() = status == ServiceStatus.Connected

        val currentStatus: ServiceStatus
            get() = status

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
        stopWatchdog()
        if (tunFd != null) {
            try {
                tunFd?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to close tunFd in onDestroy", e)
            }
            tunFd = null
        }
        val dir = filesDir.absolutePath
        ru.toinet.android.vpn.utility.VpnUtility.killPidFile("$dir/tun2socks.pid")
        ru.toinet.android.vpn.utility.VpnUtility.killPidFile("$dir/pdnsd.pid")
        appRoutingServer?.stop()
        appRoutingServer = null
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
                    sendLocalStatus(org.torproject.jni.TorService.STATUS_ON)
                    if (appRoutingServer == null) {
                        appRoutingServer = ru.toinet.android.vpn.rules.AppRoutingServer(this, lifecycleScope).apply {
                            start()
                        }
                    }
                    ensureNeededProvidersRunning()
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

            ACTION_SWITCH_TOR_NODE -> {
                lifecycleScope.launch {
                    try {
                        val intent = Intent(this@AppVpnService, ru.toinet.android.service.ToInetService::class.java).apply {
                            setAction(net.freehaven.tor.control.TorControlCommands.SIGNAL_NEWNYM)
                            putExtra(ru.toinet.android.service.ToInetConstants.EXTRA_NOT_SYSTEM, true)
                        }
                        startService(intent)
                        android.widget.Toast.makeText(this@AppVpnService, "Запрошена новая нода Tor", android.widget.Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to switch Tor node", e)
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
            sendLocalStatus(org.torproject.jni.TorService.STATUS_STARTING)
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
        stopWatchdog()

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
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
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
        // Always use Proxifier rules routing mode
        val isAppRouting = true
        /*
        // Code for tunneling into 1 single provider (preserved but commented out):
        val isAppRouting = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && ru.toinet.android.util.Prefs.appRoutingEnabled
        val provider = ru.toinet.android.util.Prefs.vpnProvider
        var actualHost = "127.0.0.1"
        var actualPort = 1780
        when (provider) {
            "byedpi" -> actualPort = getByeDpiPreferences().port
            "tor" -> actualPort = 9853
            "operaproxy" -> {
                actualHost = "127.0.0.1"
                actualPort = ru.toinet.android.util.Prefs.operaProxyBindAddress.split(":").last().toIntOrNull() ?: 1785
            }
            "easyvpn" -> actualPort = if (ru.toinet.android.util.Prefs.easyVpnProxyPort in 1..65535) ru.toinet.android.util.Prefs.easyVpnProxyPort else 1790
            "svpn", "ssh" -> actualPort = ru.toinet.android.util.Prefs.svpnLocalPort
            else -> actualPort = getByeDpiPreferences().port
        }
        var socksAddr = "$actualHost:$actualPort"
        */
        val neededProviders: MutableSet<String> = ru.toinet.android.vpn.rules.ProxifierRulesManager.getNeededProviders().toMutableSet()

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
        if (neededProviders.contains("operaproxy") && ArchHelper.isOperaSupported) {
            operaProxyServer = ru.toinet.android.operaproxy.OperaProxyServer(this, lifecycleScope)
            operaProxyServer?.start()
        }
        if (neededProviders.contains("svpn") || neededProviders.contains("ssh")) {
            val server = ru.toinet.android.svpn.SvpnServer(this, lifecycleScope)
            svpnServer = server
            server.start()
        }

        if (neededProviders.contains("tor")) {
            val torIntent = Intent(this, ru.toinet.android.service.ToInetService::class.java).apply {
                action = org.torproject.jni.TorService.ACTION_START
            }
            sendIntentToService(torIntent, isForeground = false)
        }

        // Wait for proxy port to be open before routing traffic to it
        val isReady = waitForPort("127.0.0.1", 10850, 4000L)
        if (neededProviders.contains("byedpi")) {
            val byedpiPort = try {
                ru.toinet.android.byedpi.core.ByeDpiProxyPreferences.fromSharedPreferences(
                    androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
                ).port
            } catch (_: Exception) { 1780 }
            waitForPort("127.0.0.1", byedpiPort, 3000L)
        }
        /*
        // Single provider wait logic (commented out):
        val isReady = if (isAppRouting) {
            waitForPort("127.0.0.1", 10850, 4000L)
        } else {
            val waitTimeout = when (provider) {
                "operaproxy" -> 6000L
                "easyvpn" -> 10000L
                "byedpi" -> 4000L
                "tor" -> 4000L
                else -> 3000L
            }
            waitForPort(actualHost, actualPort, waitTimeout)
        }
        */
        if (!isReady) {
            Log.e(TAG, "AppRoutingServer router port 127.0.0.1:10850 failed to start within timeout")
            ru.toinet.android.util.NotificationLogger.log(this, "proxifier", "Предупреждение: порт маршрутизатора 10850 не ответил вовремя")
        }

        var dns = sharedPreferences.getStringNotNull("dns_ip", "77.88.8.8")
        if ((neededProviders.contains("svpn") || neededProviders.contains("ssh")) && ru.toinet.android.util.Prefs.svpnDnsServer.isNotBlank()) {
            dns = ru.toinet.android.util.Prefs.svpnDnsServer
        }
        if (dns.isBlank() || dns == "208.67.222.222" || dns == "1.1.1.1") {
            dns = "77.88.8.8"
        }

        // Force IPv4-only on VPN interface: tun2socks and pdnsd do not support IPv6 DNS resolution
        // (tun2socks.c: case 6: goto fail), and local proxies (ByeDPI/Opera/EasyVPN) operate on IPv4.
        // Omitting IPv6 routes prevents browsers from attempting Happy Eyeballs IPv6 queries/connections,
        // which caused ERR_CONNECTION_RESET / ERR_CONNECTION_CLOSED.
        val hasExternalIpv6 = false
        Log.i(TAG, "VPN routing configured: IPv4-only (hasExternalIpv6 forced to false)")

        val fd = createBuilder(dns, hasExternalIpv6).establish()
            ?: throw IllegalStateException("VPN connection failed")

        this.tunFd = fd

        var startupSuccess = false
        try {
            val dnsPort = if (neededProviders.contains("svpn") || neededProviders.contains("ssh")) ru.toinet.android.util.Prefs.svpnDnsPort else 53
            ru.toinet.android.vpn.utility.VpnUtility.makePdnsdConf(this, dns, dnsPort)

            ru.toinet.android.vpn.utility.VpnUtility.exec(arrayOf(
                "$libDir/libpdnsd.so",
                "-c",
                "$dir/pdnsd.conf"
            ))

            val command = mutableListOf(
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
            /*
            // Single provider tunneling mode (commented out):
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
            */
            
            if (hasExternalIpv6) {
                command.add("--netif-ip6addr")
                command.add("fdfe:dcba:9876::2")
            }

            if (ru.toinet.android.vpn.utility.VpnUtility.exec(command.toTypedArray()) != 0) {
                throw IllegalStateException("Failed to start tun2socks")
            }

            var attempts = 0
            var success = false
            while (attempts < 25) {
                if (ru.toinet.android.vpn.utility.VpnSystem.sendfd(fd.fd) != -1) {
                    success = true
                    break
                }
                attempts++
                try {
                    Thread.sleep(200)
                } catch (e: Exception) {}
            }
            if (!success) {
                throw IllegalStateException("Failed to send fd")
            }

            Log.i(TAG, "Tun2Socks started")
            startWatchdog()
            startupSuccess = true
        } finally {
            if (!startupSuccess) {
                Log.e(TAG, "Failed to start tun2socks, ensuring tunFd is closed and resources are freed")
                try {
                    fd.close()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to close fd on startup failure", e)
                }
                this.tunFd = null
                stopTun2Socks()
            }
        }
    }

    private fun stopTun2Socks() {
        Log.i(TAG, "Stopping tun2socks")
        stopWatchdog()

        val dir = filesDir.absolutePath
        ru.toinet.android.vpn.utility.VpnUtility.killPidFile("$dir/tun2socks.pid")
        ru.toinet.android.vpn.utility.VpnUtility.killPidFile("$dir/pdnsd.pid")

        try {
            tunFd?.close() ?: Log.w(TAG, "VPN not running")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to close tunFd", e)
        } finally {
            tunFd = null
        }

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

        easyVpnServer?.stop()
        easyVpnServer = null        
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

    private fun ensureNeededProvidersRunning() {
        val neededProviders = ru.toinet.android.vpn.rules.ProxifierRulesManager.getNeededProviders()
        if (neededProviders.contains("byedpi")) {
            val byedpiIntent = Intent(this, ru.toinet.android.byedpi.services.ByeDpiProxyService::class.java).apply {
                action = ru.toinet.android.vpn.data.START_ACTION
            }
            sendIntentToService(byedpiIntent, isForeground = false)
        }
        if (neededProviders.contains("easyvpn") && easyVpnServer == null) {
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
        if (neededProviders.contains("operaproxy") && operaProxyServer == null && ArchHelper.isOperaSupported) {
            operaProxyServer = ru.toinet.android.operaproxy.OperaProxyServer(this, lifecycleScope)
            operaProxyServer?.start()
        }
        if ((neededProviders.contains("svpn") || neededProviders.contains("ssh")) && svpnServer == null) {
            val server = ru.toinet.android.svpn.SvpnServer(this, lifecycleScope)
            svpnServer = server
            server.start()
        }
        if (neededProviders.contains("tor")) {
            val torIntent = Intent(this, ru.toinet.android.service.ToInetService::class.java).apply {
                action = org.torproject.jni.TorService.ACTION_START
            }
            sendIntentToService(torIntent, isForeground = false)
        }
    }

    private fun startWatchdog() {
        stopWatchdog()
        watchdogJob = lifecycleScope.launch(Dispatchers.IO) {
            val dir = filesDir.absolutePath
            val tunPidFile = "$dir/tun2socks.pid"
            val pdnsdPidFile = "$dir/pdnsd.pid"

            kotlinx.coroutines.delay(4000L)

            while (coroutineContext.isActive && status == ServiceStatus.Connected && !stopping) {
                kotlinx.coroutines.delay(3000L)
                if (status != ServiceStatus.Connected || stopping) break

                val tunAlive = ru.toinet.android.vpn.utility.VpnUtility.isPidAlive(tunPidFile)
                val pdnsdAlive = ru.toinet.android.vpn.utility.VpnUtility.isPidAlive(pdnsdPidFile)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && ru.toinet.android.util.Prefs.appRoutingEnabled) {
                    val needed = ru.toinet.android.vpn.rules.ProxifierRulesManager.getNeededProviders()
                    if (needed.contains("byedpi") && ru.toinet.android.byedpi.services.ByeDpiProxyService.status != ServiceStatus.Connected) {
                        Log.w(TAG, "Watchdog: ByeDpiProxyService is disconnected while needed in app routing, reviving...")
                        ensureNeededProvidersRunning()
                    }
                }

                if (!tunAlive || !pdnsdAlive) {
                    Log.w(TAG, "Watchdog: VPN process died unexpectedly! tun2socks=$tunAlive, pdnsd=$pdnsdAlive")
                    ru.toinet.android.util.NotificationLogger.log(
                        this@AppVpnService,
                        ru.toinet.android.util.Prefs.vpnProvider,
                        "Внимание: VPN-процесс завершился (tun2socks=$tunAlive, pdnsd=$pdnsdAlive). Автоперезапуск..."
                    )
                    try {
                        mutex.withLock {
                            if (status == ServiceStatus.Connected && !stopping) {
                                withContext(Dispatchers.IO) {
                                    stopTun2Socks()
                                    startTun2Socks()
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Watchdog auto-restart failed", e)
                        updateStatus(ServiceStatus.Failed)
                        lifecycleScope.launch(Dispatchers.Main) {
                            stop()
                        }
                    }
                    break
                }
            }
        }
    }

    private fun stopWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
    }


    private fun getByeDpiPreferences(): ByeDpiProxyPreferences =
        ByeDpiProxyPreferences.fromSharedPreferences(getPreferences())

    private fun isTorActiveInVpn(): Boolean {
        if (ru.toinet.android.util.Prefs.vpnProvider == "tor") return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && ru.toinet.android.util.Prefs.appRoutingEnabled) {
            val needed = ru.toinet.android.vpn.rules.ProxifierRulesManager.getNeededProviders()
            if (needed.contains("tor")) return true
        }
        if (ru.toinet.android.util.Prefs.vpnProvider == "byedpi" && ru.toinet.android.util.Prefs.byedpiUpstreamEnabled && ru.toinet.android.util.Prefs.byedpiUpstreamProvider == "tor") {
            return true
        }
        return false
    }

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

        if (!isTorActiveInVpn()) {
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

        if (newStatus == ServiceStatus.Connected) {
            ru.toinet.android.util.NotificationLogger.log(this, ru.toinet.android.util.Prefs.vpnProvider, "VPN подключен (${ru.toinet.android.util.Prefs.vpnProvider})")
        }
    }

    private fun sendLocalStatus(torStatus: String) {
        if (!isTorActiveInVpn()) {
            val statusIntent = Intent(ru.toinet.android.service.ToInetConstants.LOCAL_ACTION_STATUS).apply {
                putExtra(org.torproject.jni.TorService.EXTRA_STATUS, torStatus)
                setPackage(packageName)
            }
            sendBroadcast(statusIntent)
        }
    }

    private fun createNotification(): Notification {
        val isAppRouting = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && ru.toinet.android.util.Prefs.appRoutingEnabled
        val provider = ru.toinet.android.util.Prefs.vpnProvider

        val hasEasyVpn: Boolean
        val hasTor: Boolean
        if (isAppRouting) {
            val needed = ru.toinet.android.vpn.rules.ProxifierRulesManager.getNeededProviders()
            hasEasyVpn = needed.contains("easyvpn")
            hasTor = needed.contains("tor")
        } else {
            hasEasyVpn = (provider == "easyvpn")
            hasTor = (provider == "tor")
        }

        val switchEasyVpnIntent = if (hasEasyVpn) {
            PendingIntent.getService(
                this,
                101,
                Intent(this, AppVpnService::class.java).setAction(ACTION_SWITCH_SERVER),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        } else {
            null
        }

        val switchTorIntent = if (hasTor) {
            PendingIntent.getService(
                this,
                102,
                Intent(this, AppVpnService::class.java).setAction(ACTION_SWITCH_TOR_NODE),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        } else {
            null
        }

        return createConnectionNotificationString(
            this,
            NOTIFICATION_CHANNEL_ID,
            "ToInet",
            null,
            AppVpnService::class.java,
            switchEasyVpnIntent,
            switchTorIntent,
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

        if (ipv6) {
            try {
                // Route IPv6 into TUN only if external IPv6 connectivity actually exists on the device.
                // If IPv4-only, omitting IPv6 addresses/routes prevents Happy Eyeballs hangs on modern apps.
                builder.addAddress("fdfe:dcba:9876::1", 126)
                    .addRoute("::", 0)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to configure IPv6 on VPN interface", e)
            }
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

                fun isBrowserPackage(pkg: String): Boolean {
                    val lower = pkg.lowercase()
                    return lower.contains("browser") || lower.contains("chrome") ||
                           lower.contains("firefox") || lower.contains("opera") ||
                           lower.contains("yandex") || lower.contains("searchplugin") ||
                           lower.contains("duckduckgo") || lower.contains("vivaldi") ||
                           lower.contains("brave") || lower.contains("kiwi") ||
                           lower.contains("edge") || lower.contains("tor")
                }

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
                        if (!isBrowserPackage(pkgName) && ru.toinet.android.util.AddressStatusHelper.isRuPackage(pkgName)) {
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

    private fun waitForPort(host: String, port: Int, timeoutMs: Long): Boolean {
        if (port <= 0) return false
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            try {
                java.net.Socket().use { socket ->
                    socket.connect(java.net.InetSocketAddress(host, port), 200)
                    Log.i(TAG, "Proxy port $host:$port is ready after ${System.currentTimeMillis() - start}ms")
                    return true
                }
            } catch (_: Exception) {
                try {
                    Thread.sleep(100)
                } catch (_: Exception) {}
            }
        }
        Log.w(TAG, "Proxy port $host:$port was not ready after ${timeoutMs}ms, proceeding anyway")
        return false
    }
}

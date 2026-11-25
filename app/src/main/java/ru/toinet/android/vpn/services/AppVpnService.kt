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
import ru.toinet.android.util.isAnyStandaloneServiceRunning
import ru.toinet.android.util.isServiceRunningInPackage
import ru.toinet.android.util.refreshStandalonePrimaryNotifications
import ru.toinet.android.util.sendIntentToService
class AppVpnService : LifecycleVpnService() {
    private var tunFd: ParcelFileDescriptor? = null
    private var dnsProxyServer: ru.toinet.android.dns.DnsProxyServer? = null
    private var easyVpnServer: ru.toinet.android.easyvpn.EasyVpnServer? = null
    private var operaProxyServer: ru.toinet.android.operaproxy.OperaProxyServer? = null
    private var svpnServer: ru.toinet.android.svpn.SvpnServer? = null
    private var appRoutingServer: ru.toinet.android.vpn.rules.AppRoutingServer? = null
    private val mutex = Mutex()
    private val providerReconcileMutex = Mutex()
    @Volatile
    private var stopping: Boolean = false
    @Volatile
    private var isForegroundService: Boolean = false
    private var watchdogJob: Job? = null

    companion object {
        private val TAG: String = AppVpnService::class.java.simpleName
        private const val NOTIFICATION_CHANNEL_ID: String = "ToInetVpn"
        const val ACTION_SWITCH_SERVER: String = "ru.toinet.android.vpn.SWITCH_SERVER"
        const val ACTION_SWITCH_TOR_NODE: String = "ru.toinet.android.vpn.SWITCH_TOR_NODE"
        const val ACTION_RELOAD_PROFILE: String = "ru.toinet.android.vpn.RELOAD_PROFILE"

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
                updateSharedForegroundNotification(service, "vpn") { service.createNotification() }
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
        easyVpnServer?.stop()
        easyVpnServer = null
        operaProxyServer?.stop()
        operaProxyServer = null
        svpnServer?.stop()
        svpnServer = null
        dnsProxyServer?.stop()
        dnsProxyServer = null
        if (status != ServiceStatus.Disconnected) {
            status = ServiceStatus.Disconnected
        }
        if (activeService == this) {
            activeService = null
        }
        val wasFg = isForegroundService
        isForegroundService = false
        stopSharedForeground(this, "vpn", wasForeground = wasFg)
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
                ru.toinet.android.util.Prefs.isVpnOrProxySessionActive = true
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
                    lifecycleScope.launch(Dispatchers.IO) {
                        reconcileNeededProviders()
                    }
                }
                START_STICKY
            }

            ACTION_RELOAD_PROFILE -> {
                if (status == ServiceStatus.Connected) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        reconcileNeededProviders()
                    }
                }
                START_STICKY
            }

            ACTION_SWITCH_SERVER -> {
                lifecycleScope.launch {
                    easyVpnServer?.switchToNextServer()
                    if (ru.toinet.android.util.Prefs.shouldServiceShowNotification("vpn")) {
                        updateSharedForegroundNotification(this@AppVpnService, "vpn") { createNotification() }
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
        startSharedForeground(this, "vpn") { createNotification() }
        isForegroundService = true
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
        if (ru.toinet.android.util.Prefs.isGlobalVpnEnabled && !isAnyStandaloneServiceRunning()) {
            ru.toinet.android.util.Prefs.isVpnOrProxySessionActive = false
        }
        val wasFg = isForegroundService
        isForegroundService = false
        stopSharedForeground(this, "vpn", wasForeground = wasFg)
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
        val neededProviders = ru.toinet.android.vpn.rules.ProxifierRulesManager.getAllNeededVpnProviders()

        if (isAppRouting) {
            appRoutingServer = ru.toinet.android.vpn.rules.AppRoutingServer(this, lifecycleScope).apply {
                start()
            }
        }

        if (neededProviders.contains("byedpi")) {
            val byedpiIntent = Intent(this, ru.toinet.android.byedpi.services.ByeDpiProxyService::class.java).apply {
                action = ru.toinet.android.vpn.data.START_ACTION
                putExtra("is_foreground", true)
            }
            sendIntentToService(byedpiIntent, isForeground = true)
        }
        if (neededProviders.contains("easyvpn")) {
            if (ru.toinet.android.easyvpn.EasyVpnService.isRunning || ru.toinet.android.easyvpn.EasyVpnService.activeService != null) {
                ru.toinet.android.easyvpn.EasyVpnService.stopAndWait(this)
            }
            easyVpnServer = ru.toinet.android.easyvpn.EasyVpnServer(this, lifecycleScope).apply {
                onServerChanged = {
                    if (ru.toinet.android.util.Prefs.shouldServiceShowNotification("vpn")) {
                        updateSharedForegroundNotification(this@AppVpnService, "vpn") { createNotification() }
                    }
                }
            }
            easyVpnServer?.start()
        }
        if (neededProviders.contains("operaproxy") && ArchHelper.isOperaSupported) {
            if (ru.toinet.android.operaproxy.OperaProxyService.isRunning || ru.toinet.android.operaproxy.OperaProxyService.activeService != null) {
                ru.toinet.android.operaproxy.OperaProxyService.stopAndWait(this)
            }
            operaProxyServer = ru.toinet.android.operaproxy.OperaProxyServer(this, lifecycleScope)
            operaProxyServer?.start()
        }
        if (neededProviders.contains("svpn") || neededProviders.contains("ssh")) {
            if (ru.toinet.android.svpn.SvpnService.isRunning || ru.toinet.android.svpn.SvpnService.activeService != null) {
                ru.toinet.android.svpn.SvpnService.stopAndWait(this)
            }
            val server = ru.toinet.android.svpn.SvpnServer(this, lifecycleScope)
            svpnServer = server
            server.onConnectionStateChanged = { connected, error ->
                if (!connected && status == ServiceStatus.Connected && !stopping) {
                    Log.e(TAG, "SVPN failed or disconnected: $error")
                    updateStatus(ServiceStatus.Failed)
                    lifecycleScope.launch(Dispatchers.Main) {
                        stop()
                    }
                }
            }
            val connected = server.startAndWait(8000L)
            if (!connected) {
                val err = server.lastErrorMessage ?: "SSH connection failed"
                Log.e(TAG, "SVPN failed to connect: $err")
                throw IllegalStateException("SVPN: $err")
            }
        }

        if (neededProviders.contains("tor")) {
            val torIntent = Intent(this, ru.toinet.android.service.ToInetService::class.java).apply {
                action = org.torproject.jni.TorService.ACTION_START
                putExtra("is_foreground", true)
            }
            sendIntentToService(torIntent, isForeground = true)
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

        val stoppedProviders = ru.toinet.android.vpn.rules.ProxifierRulesManager.getAllNeededVpnProviders()
        val keepStandaloneComponents = !ru.toinet.android.util.Prefs.isGlobalVpnEnabled

        if (stoppedProviders.contains("byedpi") &&
            (!keepStandaloneComponents || !ru.toinet.android.util.Prefs.isProviderNeededStandalone("byedpi"))
        ) {
            val byedpiIntent = Intent(this, ru.toinet.android.byedpi.services.ByeDpiProxyService::class.java).apply {
                action = ru.toinet.android.vpn.data.STOP_ACTION
                putExtra("is_foreground", false)
            }
            sendIntentToService(byedpiIntent, isForeground = false)
        }

        if (stoppedProviders.contains("tor") &&
            (!keepStandaloneComponents || !ru.toinet.android.util.Prefs.isProviderNeededStandalone("tor"))
        ) {
            val torIntent = Intent(this, ru.toinet.android.service.ToInetService::class.java).apply {
                action = org.torproject.jni.TorService.ACTION_STOP
                putExtra("is_foreground", false)
            }
            sendIntentToService(torIntent, isForeground = false)
        }

        easyVpnServer?.stop()
        easyVpnServer = null
        operaProxyServer?.stop()
        operaProxyServer = null
        dnsProxyServer?.stop()
        dnsProxyServer = null
        svpnServer?.stop()
        svpnServer = null

        if (keepStandaloneComponents) {
            if (ru.toinet.android.util.Prefs.isProviderNeededStandalone("easyvpn") && !ru.toinet.android.easyvpn.EasyVpnService.isRunning) {
                ru.toinet.android.easyvpn.EasyVpnService.start(this)
            }
            if (ru.toinet.android.util.Prefs.isProviderNeededStandalone("operaproxy") && ArchHelper.isOperaSupported && !ru.toinet.android.operaproxy.OperaProxyService.isRunning) {
                ru.toinet.android.operaproxy.OperaProxyService.start(this)
            }
            if (ru.toinet.android.util.Prefs.isProviderNeededStandalone("svpn") && !ru.toinet.android.svpn.SvpnService.isRunning) {
                ru.toinet.android.svpn.SvpnService.start(this)
            }
            refreshStandalonePrimaryNotifications()
        } else {
            if (stoppedProviders.contains("operaproxy") && ru.toinet.android.operaproxy.OperaProxyService.isRunning) {
                ru.toinet.android.operaproxy.OperaProxyService.stop(this)
            }
            if ((stoppedProviders.contains("svpn") || stoppedProviders.contains("ssh")) && ru.toinet.android.svpn.SvpnService.isRunning) {
                ru.toinet.android.svpn.SvpnService.stop(this)
            }
        }
        Log.i(TAG, "Tun2socks stopped")
    }

    private suspend fun reconcileNeededProviders() {
        providerReconcileMutex.withLock {
            if (status != ServiceStatus.Connected || stopping) return@withLock
            val neededProviders = ru.toinet.android.vpn.rules.ProxifierRulesManager.getAllNeededVpnProviders()

            if (neededProviders.contains("byedpi")) {
                if (ru.toinet.android.byedpi.services.ByeDpiProxyService.status != ServiceStatus.Connected) {
                    val byedpiIntent = Intent(this, ru.toinet.android.byedpi.services.ByeDpiProxyService::class.java).apply {
                        action = ru.toinet.android.vpn.data.START_ACTION
                        putExtra("is_foreground", true)
                    }
                    sendIntentToService(byedpiIntent, isForeground = true)
                }
            } else if (!ru.toinet.android.util.Prefs.isProviderNeededStandalone("byedpi")) {
                if (ru.toinet.android.byedpi.services.ByeDpiProxyService.status != ServiceStatus.Disconnected ||
                    isServiceRunningInPackage(ru.toinet.android.byedpi.services.ByeDpiProxyService::class.java)
                ) {
                    val byedpiStopIntent = Intent(this, ru.toinet.android.byedpi.services.ByeDpiProxyService::class.java).apply {
                        action = ru.toinet.android.vpn.data.STOP_ACTION
                        putExtra("is_foreground", false)
                    }
                    sendIntentToService(byedpiStopIntent, isForeground = false)
                }
            }

            if (neededProviders.contains("tor")) {
                if (!isServiceRunningInPackage(ru.toinet.android.service.ToInetService::class.java) ||
                    ru.toinet.android.util.Prefs.torRuntimeStatus == org.torproject.jni.TorService.STATUS_OFF
                ) {
                    val torIntent = Intent(this, ru.toinet.android.service.ToInetService::class.java).apply {
                        action = org.torproject.jni.TorService.ACTION_START
                        putExtra("is_foreground", true)
                    }
                    sendIntentToService(torIntent, isForeground = true)
                }
            } else if (!ru.toinet.android.util.Prefs.isProviderNeededStandalone("tor") &&
                isServiceRunningInPackage(ru.toinet.android.service.ToInetService::class.java)
            ) {
                val torStopIntent = Intent(this, ru.toinet.android.service.ToInetService::class.java).apply {
                    action = org.torproject.jni.TorService.ACTION_STOP
                    putExtra("is_foreground", false)
                }
                sendIntentToService(torStopIntent, isForeground = false)
            }

            if (neededProviders.contains("easyvpn")) {
                if (easyVpnServer == null || easyVpnServer?.isServerRunning != true) {
                    easyVpnServer?.stop()
                    easyVpnServer = null
                    if (ru.toinet.android.easyvpn.EasyVpnService.isRunning || ru.toinet.android.easyvpn.EasyVpnService.activeService != null) {
                        ru.toinet.android.easyvpn.EasyVpnService.stopAndWait(this)
                    }
                    easyVpnServer = ru.toinet.android.easyvpn.EasyVpnServer(this, lifecycleScope).apply {
                        onServerChanged = {
                            if (ru.toinet.android.util.Prefs.shouldServiceShowNotification("vpn")) {
                                updateSharedForegroundNotification(this@AppVpnService, "vpn") { createNotification() }
                            }
                        }
                    }
                    easyVpnServer?.start()
                }
            } else if (easyVpnServer != null) {
                easyVpnServer?.stop()
                easyVpnServer = null
                if (ru.toinet.android.util.Prefs.isProviderNeededStandalone("easyvpn") && !ru.toinet.android.easyvpn.EasyVpnService.isRunning) {
                    ru.toinet.android.easyvpn.EasyVpnService.start(this)
                }
            }

            if (neededProviders.contains("operaproxy") && ArchHelper.isOperaSupported) {
                if (operaProxyServer == null || operaProxyServer?.isServerRunning != true) {
                    operaProxyServer?.stop()
                    operaProxyServer = null
                    if (ru.toinet.android.operaproxy.OperaProxyService.isRunning || ru.toinet.android.operaproxy.OperaProxyService.activeService != null) {
                        ru.toinet.android.operaproxy.OperaProxyService.stopAndWait(this)
                    }
                    operaProxyServer = ru.toinet.android.operaproxy.OperaProxyServer(this, lifecycleScope)
                    operaProxyServer?.start()
                }
            } else if (operaProxyServer != null) {
                operaProxyServer?.stop()
                operaProxyServer = null
                if (ru.toinet.android.util.Prefs.isProviderNeededStandalone("operaproxy") &&
                    ArchHelper.isOperaSupported &&
                    !ru.toinet.android.operaproxy.OperaProxyService.isRunning
                ) {
                    ru.toinet.android.operaproxy.OperaProxyService.start(this)
                }
            }

            if (neededProviders.contains("svpn") || neededProviders.contains("ssh")) {
                if (svpnServer == null || svpnServer?.isServerRunning != true) {
                    svpnServer?.stop()
                    svpnServer = null
                    if (ru.toinet.android.svpn.SvpnService.isRunning || ru.toinet.android.svpn.SvpnService.activeService != null) {
                        ru.toinet.android.svpn.SvpnService.stopAndWait(this)
                    }
                    val server = ru.toinet.android.svpn.SvpnServer(this, lifecycleScope)
                    svpnServer = server
                    server.onConnectionStateChanged = { connected, error ->
                        if (!connected && status == ServiceStatus.Connected && !stopping) {
                            Log.e(TAG, "SVPN failed or disconnected: $error")
                            updateStatus(ServiceStatus.Failed)
                            lifecycleScope.launch(Dispatchers.Main) {
                                stop()
                            }
                        }
                    }
                    server.start()
                }
            } else if (svpnServer != null) {
                svpnServer?.stop()
                svpnServer = null
                if (ru.toinet.android.util.Prefs.isProviderNeededStandalone("svpn") && !ru.toinet.android.svpn.SvpnService.isRunning) {
                    ru.toinet.android.svpn.SvpnService.start(this)
                }
            }

            if (ru.toinet.android.util.Prefs.shouldServiceShowNotification("vpn")) {
                updateSharedForegroundNotification(this, "vpn") { createNotification() }
            }
            refreshActiveForegroundNotification(this)
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

                val needed = ru.toinet.android.vpn.rules.ProxifierRulesManager.getAllNeededVpnProviders()
                if (needed.contains("byedpi") && ru.toinet.android.byedpi.services.ByeDpiProxyService.status != ServiceStatus.Connected) {
                    Log.w(TAG, "Watchdog: ByeDpiProxyService is disconnected while needed in VPN routing, reviving...")
                    reconcileNeededProviders()
                }
                if (needed.contains("easyvpn") && easyVpnServer?.isServerRunning != true) {
                    Log.w(TAG, "Watchdog: EasyVpnServer stopped while needed in VPN routing, reviving...")
                    ensureProviderStarted("easyvpn", forceRestart = true)
                }
                if (needed.contains("operaproxy") && ArchHelper.isOperaSupported && operaProxyServer?.isServerRunning != true) {
                    Log.w(TAG, "Watchdog: OperaProxyServer stopped while needed in VPN routing, reviving...")
                    ensureProviderStarted("operaproxy", forceRestart = true)
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
        return try {
            ru.toinet.android.vpn.rules.ProxifierRulesManager.getAllNeededVpnProviders().contains("tor")
        } catch (_: Throwable) {
            ru.toinet.android.util.Prefs.vpnProvider == "tor"
        }
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

        val toinetStatus = when (newStatus) {
            ServiceStatus.Connected -> org.torproject.jni.TorService.STATUS_ON
            ServiceStatus.Disconnected, ServiceStatus.Failed -> {
                if (!ru.toinet.android.util.Prefs.isGlobalVpnEnabled && isAnyStandaloneServiceRunning()) {
                    org.torproject.jni.TorService.STATUS_ON
                } else {
                    org.torproject.jni.TorService.STATUS_OFF
                }
            }
        }
        val statusIntent = Intent(ru.toinet.android.service.ToInetConstants.LOCAL_ACTION_STATUS).apply {
            putExtra(org.torproject.jni.TorService.EXTRA_STATUS, toinetStatus)
            setPackage(packageName)
        }
        sendBroadcast(statusIntent)

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
        if (!isTorActiveInVpn() || torStatus == org.torproject.jni.TorService.STATUS_ON) {
            val statusIntent = Intent(ru.toinet.android.service.ToInetConstants.LOCAL_ACTION_STATUS).apply {
                putExtra(org.torproject.jni.TorService.EXTRA_STATUS, torStatus)
                setPackage(packageName)
            }
            sendBroadcast(statusIntent)
        }
    }

    private fun createNotification(): Notification {
        val needed = ru.toinet.android.vpn.rules.ProxifierRulesManager.getAllNeededVpnProviders()
        val hasEasyVpn = needed.contains("easyvpn")
        val hasTor = needed.contains("tor")

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
        builder.setMetered(false)

        val isAppRouting = ru.toinet.android.util.Prefs.appRoutingEnabled
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

    /**
     * Ensures that a specific provider managed in-process by this VPN service is started.
     * Called by UpstreamProxyCoordinator or syncStandaloneComponent when AppVpnService is active.
     */
    fun ensureProviderStarted(provider: String, forceRestart: Boolean = false) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                when (provider) {
                    "easyvpn" -> {
                        if (forceRestart || easyVpnServer == null) {
                            easyVpnServer?.stop()
                            easyVpnServer = null
                            if (ru.toinet.android.easyvpn.EasyVpnService.isRunning || ru.toinet.android.easyvpn.EasyVpnService.activeService != null) {
                                ru.toinet.android.easyvpn.EasyVpnService.stopAndWait(this@AppVpnService)
                            }
                            easyVpnServer = ru.toinet.android.easyvpn.EasyVpnServer(this@AppVpnService, lifecycleScope).apply {
                                onServerChanged = {
                                    if (ru.toinet.android.util.Prefs.shouldServiceShowNotification("vpn")) {
                                        updateSharedForegroundNotification(this@AppVpnService, "vpn") { createNotification() }
                                    }
                                }
                            }
                            easyVpnServer?.start()
                            Log.i(TAG, "ensureProviderStarted: started in-process EasyVpnServer (forceRestart=$forceRestart)")
                        }
                    }
                    "operaproxy" -> {
                        if (!ArchHelper.isOperaSupported) return@launch
                        if (forceRestart || operaProxyServer == null) {
                            operaProxyServer?.stop()
                            operaProxyServer = null
                            if (ru.toinet.android.operaproxy.OperaProxyService.isRunning || ru.toinet.android.operaproxy.OperaProxyService.activeService != null) {
                                ru.toinet.android.operaproxy.OperaProxyService.stopAndWait(this@AppVpnService)
                            }
                            operaProxyServer = ru.toinet.android.operaproxy.OperaProxyServer(this@AppVpnService, lifecycleScope)
                            operaProxyServer?.start()
                            Log.i(TAG, "ensureProviderStarted: started in-process OperaProxyServer (forceRestart=$forceRestart)")
                        }
                    }
                    "svpn", "ssh" -> {
                        if (forceRestart || svpnServer == null) {
                            svpnServer?.stop()
                            svpnServer = null
                            if (ru.toinet.android.svpn.SvpnService.isRunning || ru.toinet.android.svpn.SvpnService.activeService != null) {
                                ru.toinet.android.svpn.SvpnService.stopAndWait(this@AppVpnService)
                            }
                            val server = ru.toinet.android.svpn.SvpnServer(this@AppVpnService, lifecycleScope)
                            svpnServer = server
                            server.onConnectionStateChanged = { connected, error ->
                                if (!connected && status == ServiceStatus.Connected && !stopping) {
                                    Log.e(TAG, "SVPN failed or disconnected (ensureProviderStarted): $error")
                                    updateStatus(ServiceStatus.Failed)
                                    lifecycleScope.launch(Dispatchers.Main) { stop() }
                                }
                            }
                            server.start()
                            Log.i(TAG, "ensureProviderStarted: started in-process SvpnServer (forceRestart=$forceRestart)")
                        }
                    }
                    else -> Log.w(TAG, "ensureProviderStarted: unrecognized provider '$provider'")
                }
            } catch (e: Exception) {
                Log.e(TAG, "ensureProviderStarted failed for '$provider'", e)
            }
        }
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

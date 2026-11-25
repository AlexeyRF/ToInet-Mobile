package ru.toinet.android.util

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.PowerManager
import android.util.Log
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import org.torproject.jni.TorService
import ru.toinet.android.byedpi.services.ByeDpiProxyService
import ru.toinet.android.easyvpn.EasyVpnService
import ru.toinet.android.operaproxy.OperaProxyService
import ru.toinet.android.service.ToInetConstants
import ru.toinet.android.service.ToInetService
import ru.toinet.android.svpn.SvpnService
import ru.toinet.android.tgws.TgwsService
import ru.toinet.android.vpn.data.START_ACTION
import ru.toinet.android.vpn.data.STOP_ACTION
import ru.toinet.android.vpn.data.ServiceStatus
import ru.toinet.android.vpn.rules.ProxifierRulesManager
import ru.toinet.android.vpn.services.AppVpnService
import ru.toinet.android.vpn.utility.refreshActiveForegroundNotification

private const val EXT_TAG = "ToInetExt"
private const val ACTION_RESTART_TOR = "ru.toinet.android.tor.RESTART"
const val ACTION_VPN_PROFILE_CHANGED = "ru.toinet.android.ACTION_VPN_PROFILE_CHANGED"

fun Intent.putNotSystem(): Intent = this.putExtra(ToInetConstants.EXTRA_NOT_SYSTEM, true)

/**
 * Checks if a service class in this package (including remote processes like :tor) is running.
 */
@Suppress("DEPRECATION")
fun Context.isServiceRunningInPackage(serviceClass: Class<*>): Boolean {
    return try {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        am.getRunningServices(Int.MAX_VALUE).any {
            it.service.packageName == packageName && it.service.className == serviceClass.name
        }
    } catch (_: Exception) {
        false
    }
}

/**
 * Returns true if any standalone service (or Tor when active) is currently running.
 */
fun Context.isAnyStandaloneServiceRunning(): Boolean {
    if (ByeDpiProxyService.status == ServiceStatus.Connected) return true
    if (EasyVpnService.isRunning) return true
    if (OperaProxyService.isRunning) return true
    if (SvpnService.isRunning) return true
    if (TgwsService.isRunning) return true
    if (Prefs.isProviderNeededStandalone("tor") &&
        Prefs.torRuntimeStatus == TorService.STATUS_ON &&
        isServiceRunningInPackage(ToInetService::class.java)
    ) {
        return true
    }
    return false
}

fun Context.broadcastLocalStatus(status: String) {
    val statusIntent = Intent(ToInetConstants.LOCAL_ACTION_STATUS).apply {
        putExtra(TorService.EXTRA_STATUS, status)
        setPackage(packageName)
    }
    sendBroadcast(statusIntent)
}

/**
 * Stops a service using stopService(), safely ignoring any exceptions.
 * Used so background stops never crash with IllegalStateException on API 26+.
 */
fun Context.stopServiceCompat(intent: Intent) {
    try {
        stopService(intent)
    } catch (e: Exception) {
        Log.e(EXT_TAG, "stopServiceCompat failed for ${intent.component?.className}", e)
    }
}

/**
 * Broadcasts the effective standalone status (STATUS_ON / STATUS_OFF) without clobbering
 * active VPN or other still-running standalone components with a premature STATUS_OFF.
 */
fun broadcastStandaloneStatus(service: Service, context: Context = service) {
    if (Prefs.isGlobalVpnEnabled) return
    val isThisRunning = when (service) {
        is EasyVpnService -> EasyVpnService.isRunning
        is OperaProxyService -> OperaProxyService.isRunning
        is SvpnService -> SvpnService.isRunning
        is TgwsService -> TgwsService.isRunning
        is ByeDpiProxyService -> ByeDpiProxyService.status == ServiceStatus.Connected
        else -> false
    }
    if (isThisRunning) {
        context.broadcastLocalStatus(TorService.STATUS_ON)
        return
    }
    if (context.isAnyStandaloneServiceRunning()) {
        context.broadcastLocalStatus(TorService.STATUS_ON)
    } else {
        context.broadcastLocalStatus(TorService.STATUS_OFF)
    }
}

fun Context.sendIntentToService(intent: Intent, isForeground: Boolean = true): Boolean {
    val prepared = intent.putNotSystem()
    if (isForeground) {
        prepared.putExtra("is_foreground", true)
    }
    val actionName = prepared.action ?: prepared.component?.className ?: "unknown"
    return try {
        if (isForeground) {
            ContextCompat.startForegroundService(this, prepared)
        } else {
            startService(prepared)
        }
        true
    } catch (e: Exception) {
        Log.e(EXT_TAG, "sendIntentToService failed (isForeground=$isForeground) for $actionName: ${e.message}", e)
        if (isForeground) {
            // On API 26+, background startService always fails with IllegalStateException.
            val canTryPlainStart = isAppInForegroundLifecycle()
            if (canTryPlainStart) {
                try {
                    startService(prepared)
                    return true
                } catch (e2: Exception) {
                    Log.e(EXT_TAG, "Fallback startService failed for $actionName: ${e2.message}", e2)
                    NotificationLogger.log(this, "system", "Не удалось запустить службу $actionName: ${e2.message}")
                }
            } else {
                NotificationLogger.log(this, "system", "Фоновый запуск службы $actionName заблокирован системой: ${e.message}")
            }
        } else {
            val isStop = actionName == STOP_ACTION || actionName == TorService.ACTION_STOP
            if (isStop) {
                stopServiceCompat(prepared)
                return true
            }
            if (actionName != "ACTIVE" && actionName != ToInetConstants.CMD_ACTIVE) {
                NotificationLogger.log(this, "system", "Ошибка отправки команды $actionName: ${e.message}")
            }
        }
        false
    }
}

private fun isAppInForegroundLifecycle(): Boolean {
    return try {
        androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.currentState
            .isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
    } catch (_: Exception) {
        false
    }
}

fun Context.canStartForegroundServices(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        return true
    }
    if (isAppInForegroundLifecycle()) {
        return true
    }
    if (Prefs.isGlobalVpnEnabled && VpnService.prepare(this) == null) {
        return true
    }
    if (AppVpnService.activeService != null ||
        ByeDpiProxyService.status == ServiceStatus.Connected ||
        EasyVpnService.activeService != null ||
        OperaProxyService.activeService != null ||
        SvpnService.activeService != null ||
        TgwsService.activeService != null
    ) {
        return true
    }
    try {
        val pm = ContextCompat.getSystemService(this, PowerManager::class.java)
        if (pm?.isIgnoringBatteryOptimizations(packageName) == true) {
            return true
        }
    } catch (_: Exception) {}
    val alarmManager = ContextCompat.getSystemService(this, AlarmManager::class.java)
    return alarmManager?.canScheduleExactAlarms() ?: false
}

fun Context.sendIntentToService(action: String) {
    val isPing = action == "ACTIVE" || action == ToInetConstants.CMD_ACTIVE
    val isStop = action == TorService.ACTION_STOP
    val isStart = action == TorService.ACTION_START

    if (isStart) {
        Prefs.isVpnOrProxySessionActive = true
    } else if (isStop) {
        Prefs.isVpnOrProxySessionActive = false
    }

    val isGlobalVpnEnabled = Prefs.isGlobalVpnEnabled
    val neededVpnProviders = if (isGlobalVpnEnabled) {
        try {
            ProxifierRulesManager.getAllNeededVpnProviders()
        } catch (_: Throwable) {
            emptySet()
        }
    } else {
        emptySet()
    }

    val isByedpiActive = Prefs.isProviderActive("byedpi")
    val isTorActive = Prefs.isProviderActive("tor")
    val isTgwsActive = Prefs.isProviderActive("tgws")
    val easyVpnManagedByVpn = isGlobalVpnEnabled && neededVpnProviders.contains("easyvpn")
    val operaManagedByVpn = isGlobalVpnEnabled && neededVpnProviders.contains("operaproxy")
    val svpnManagedByVpn = isGlobalVpnEnabled &&
        (neededVpnProviders.contains("svpn") || neededVpnProviders.contains("ssh"))

    val isOperaStandalone = ArchHelper.isOperaSupported &&
        Prefs.isProviderActive("operaproxy") && !operaManagedByVpn
    val isEasyVpnStandalone = Prefs.isProviderActive("easyvpn") && !easyVpnManagedByVpn
    val isSvpnStandalone = Prefs.isProviderActive("svpn") && !svpnManagedByVpn

    var anyStartedOrStarting = false
    val torServiceRunning = isServiceRunningInPackage(ToInetService::class.java)

    // 1. ByeDPI
    if (isByedpiActive && isStart) {
        val started = sendIntentToService(
            Intent(this, ByeDpiProxyService::class.java).apply {
                this.action = START_ACTION
                putExtra("is_foreground", true)
            },
            isForeground = true
        )
        if (started) anyStartedOrStarting = true
    } else if (isStop) {
        if (ByeDpiProxyService.status != ServiceStatus.Disconnected ||
            isServiceRunningInPackage(ByeDpiProxyService::class.java)
        ) {
            sendIntentToService(
                Intent(this, ByeDpiProxyService::class.java).apply {
                    this.action = STOP_ACTION
                    putExtra("is_foreground", false)
                },
                isForeground = false
            )
        }
    } else if (isPing && isByedpiActive && ByeDpiProxyService.status == ServiceStatus.Connected) {
        sendIntentToService(
            Intent(this, ByeDpiProxyService::class.java).apply {
                this.action = "ACTIVE"
                putExtra("is_foreground", false)
            },
            isForeground = false
        )
    }

    // 2. AppVpnService
    if (isGlobalVpnEnabled && isStart) {
        if (easyVpnManagedByVpn && (EasyVpnService.isRunning || EasyVpnService.activeService != null)) {
            EasyVpnService.stop(this)
        }
        if (operaManagedByVpn && (OperaProxyService.isRunning || OperaProxyService.activeService != null)) {
            OperaProxyService.stop(this)
        }
        if (svpnManagedByVpn && (SvpnService.isRunning || SvpnService.activeService != null)) {
            SvpnService.stop(this)
        }
        val started = sendIntentToService(
            Intent(this, AppVpnService::class.java).apply {
                this.action = START_ACTION
                putExtra("is_foreground", true)
            },
            isForeground = true
        )
        if (started) anyStartedOrStarting = true
    } else if (isStop) {
        if (AppVpnService.activeService != null || AppVpnService.currentStatus != ServiceStatus.Disconnected) {
            sendIntentToService(
                Intent(this, AppVpnService::class.java).apply {
                    this.action = STOP_ACTION
                    putExtra("is_foreground", false)
                },
                isForeground = false
            )
        }
    } else if (isPing && isGlobalVpnEnabled) {
        if (AppVpnService.activeService != null) {
            sendIntentToService(
                Intent(this, AppVpnService::class.java).apply {
                    this.action = "ACTIVE"
                    putExtra("is_foreground", false)
                },
                isForeground = false
            )
        } else {
            broadcastLocalStatus(TorService.STATUS_OFF)
        }
    }

    // 3. Tor (ToInetService)
    if (isTorActive && isStart) {
        val started = sendIntentToService(
            Intent(this, ToInetService::class.java).apply {
                this.action = TorService.ACTION_START
                putExtra("is_foreground", true)
            },
            isForeground = true
        )
        if (started) anyStartedOrStarting = true
    } else if (isStop) {
        if (torServiceRunning) {
            sendIntentToService(
                Intent(this, ToInetService::class.java).apply {
                    this.action = TorService.ACTION_STOP
                    putExtra("is_foreground", false)
                },
                isForeground = false
            )
        }
    } else if (isPing && isTorActive && torServiceRunning) {
        sendIntentToService(
            Intent(this, ToInetService::class.java).apply {
                this.action = action
                putExtra("is_foreground", false)
            },
            isForeground = false
        )
    }

    // 4. TGWS (never managed by VPN)
    if (isTgwsActive && isStart) {
        TgwsService.start(
            this,
            Prefs.tgwsHost,
            Prefs.tgwsPort,
            Prefs.tgwsDcMappings,
            Prefs.tgwsSecret,
            Prefs.tgwsFakeTls,
            false,
            Prefs.tgwsCfWorkerDomains,
            Prefs.tgwsCfProxyDomains,
            fallbackCfProxy = Prefs.tgwsFallbackCfProxy,
            cfProxyH2Media = Prefs.tgwsCfProxyH2Media,
            frontingSni = Prefs.tgwsFrontingSni,
            disableSecure = Prefs.tgwsDisableSecure,
            forceTestDc = Prefs.tgwsForceTestDc,
            poolSize = Prefs.tgwsPoolSize
        )
        anyStartedOrStarting = true
    } else if (isStop) {
        if (TgwsService.isRunning || TgwsService.activeService != null) {
            TgwsService.stop(this)
        }
    } else if (isPing && isTgwsActive && TgwsService.isRunning) {
        sendIntentToService(
            Intent(this, TgwsService::class.java).apply {
                this.action = "ACTIVE"
                putExtra("is_foreground", false)
            },
            isForeground = false
        )
    }

    // 5. Opera Proxy (standalone when enabled outside VPN in-process management)
    if (isOperaStandalone && isStart) {
        OperaProxyService.start(this)
        anyStartedOrStarting = true
    } else if (isStop) {
        if (OperaProxyService.isRunning || OperaProxyService.activeService != null) {
            OperaProxyService.stop(this)
        }
    } else if (isPing && isOperaStandalone && OperaProxyService.isRunning) {
        sendIntentToService(
            Intent(this, OperaProxyService::class.java).apply {
                this.action = "ACTIVE"
                putExtra("is_foreground", false)
            },
            isForeground = false
        )
    }

    // 6. EasyVPN (standalone when enabled outside VPN in-process management)
    if (isEasyVpnStandalone && isStart) {
        EasyVpnService.start(this)
        anyStartedOrStarting = true
    } else if (isStop) {
        if (EasyVpnService.isRunning || EasyVpnService.activeService != null) {
            EasyVpnService.stop(this)
        }
    } else if (isPing && isEasyVpnStandalone && EasyVpnService.isRunning) {
        sendIntentToService(
            Intent(this, EasyVpnService::class.java).apply {
                this.action = "ACTIVE"
                putExtra("is_foreground", false)
            },
            isForeground = false
        )
    }

    // 7. SVPN (standalone when enabled outside VPN in-process management)
    if (isSvpnStandalone && isStart) {
        SvpnService.start(this)
        anyStartedOrStarting = true
    } else if (isStop) {
        if (SvpnService.isRunning || SvpnService.activeService != null) {
            SvpnService.stop(this)
        }
    } else if (isPing && isSvpnStandalone && SvpnService.isRunning) {
        sendIntentToService(
            Intent(this, SvpnService::class.java).apply {
                this.action = "ACTIVE"
                putExtra("is_foreground", false)
            },
            isForeground = false
        )
    }

    // Never emit fake STATUS_ON before services bind. Each service broadcasts STATUS_ON once bound.
    if (!isGlobalVpnEnabled) {
        if (isStart) {
            if (!anyStartedOrStarting && !isAnyStandaloneServiceRunning()) {
                broadcastLocalStatus(TorService.STATUS_OFF)
            } else if (!isTorActive && !isAnyStandaloneServiceRunning()) {
                broadcastLocalStatus(TorService.STATUS_STARTING)
            }
        } else if (isStop) {
            if (!isTorActive && !isAnyStandaloneServiceRunning()) {
                broadcastLocalStatus(TorService.STATUS_OFF)
            }
        } else if (isPing) {
            if (isAnyStandaloneServiceRunning()) {
                broadcastLocalStatus(TorService.STATUS_ON)
            } else if (Prefs.isProviderNeededStandalone("tor") &&
                Prefs.torRuntimeStatus == TorService.STATUS_STARTING &&
                torServiceRunning
            ) {
                broadcastLocalStatus(TorService.STATUS_STARTING)
            } else if (!isTorActive || !torServiceRunning || Prefs.torRuntimeStatus == TorService.STATUS_OFF) {
                broadcastLocalStatus(TorService.STATUS_OFF)
            }
        }
    }
}

/**
 * Synchronizes a standalone component (provider) start/restart/stop.
 * If AppVpnService is active and manages easyvpn/operaproxy/svpn in-process,
 * delegates start/restart inside AppVpnService instead of spawning a conflicting service.
 */
fun Context.syncStandaloneComponent(provider: String, enabled: Boolean, configChanged: Boolean = false) {
    val normalized = if (provider == "ssh") "svpn" else provider
    val activeVpn = AppVpnService.activeService
    val vpnActive = activeVpn != null || AppVpnService.currentStatus != ServiceStatus.Disconnected
    val neededVpnProviders = if (Prefs.isGlobalVpnEnabled || vpnActive) {
        try {
            ProxifierRulesManager.getAllNeededVpnProviders()
        } catch (_: Throwable) {
            emptySet()
        }
    } else {
        emptySet()
    }
    val isInProcessVpnProvider = normalized == "easyvpn" || normalized == "operaproxy" || normalized == "svpn"
    val vpnManagesInProcess = vpnActive && isInProcessVpnProvider &&
        (neededVpnProviders.contains(normalized) || (normalized == "svpn" && neededVpnProviders.contains("ssh")))

    if (vpnManagesInProcess) {
        if (enabled || configChanged) {
            activeVpn?.ensureProviderStarted(normalized, forceRestart = configChanged)
        }
        return
    }

    val stillNeededByActiveVpn = vpnActive && (neededVpnProviders.contains(normalized) ||
        (normalized == "svpn" && neededVpnProviders.contains("ssh")))

    when (normalized) {
        "tor" -> {
            if (enabled) {
                val action = if (configChanged) ACTION_RESTART_TOR else TorService.ACTION_START
                sendIntentToService(
                    Intent(this, ToInetService::class.java).apply {
                        this.action = action
                        putExtra("is_foreground", true)
                    },
                    isForeground = true
                )
            } else if (!stillNeededByActiveVpn && !Prefs.isProviderNeededStandalone("tor")) {
                if (isServiceRunningInPackage(ToInetService::class.java)) {
                    sendIntentToService(
                        Intent(this, ToInetService::class.java).apply {
                            this.action = TorService.ACTION_STOP
                            putExtra("is_foreground", false)
                        },
                        isForeground = false
                    )
                } else if (!Prefs.isGlobalVpnEnabled) {
                    if (isAnyStandaloneServiceRunning()) broadcastLocalStatus(TorService.STATUS_ON)
                    else broadcastLocalStatus(TorService.STATUS_OFF)
                }
            }
        }
        "byedpi" -> {
            if (enabled) {
                val running = ByeDpiProxyService.status == ServiceStatus.Connected
                if (!running || configChanged) {
                    val intent = Intent(this, ByeDpiProxyService::class.java).apply {
                        action = if (running && configChanged) ByeDpiProxyService.ACTION_RESTART else START_ACTION
                        putExtra("is_foreground", true)
                    }
                    sendIntentToService(intent, isForeground = true)
                }
            } else if (!stillNeededByActiveVpn && !Prefs.isProviderNeededStandalone("byedpi")) {
                if (ByeDpiProxyService.status != ServiceStatus.Disconnected ||
                    isServiceRunningInPackage(ByeDpiProxyService::class.java)
                ) {
                    sendIntentToService(
                        Intent(this, ByeDpiProxyService::class.java).apply {
                            action = STOP_ACTION
                            putExtra("is_foreground", false)
                        },
                        isForeground = false
                    )
                }
            }
        }
        "easyvpn" -> {
            if (enabled) {
                if (!EasyVpnService.isRunning || configChanged) {
                    EasyVpnService.start(this)
                }
            } else if (!Prefs.isProviderNeededStandalone("easyvpn")) {
                if (EasyVpnService.isRunning || EasyVpnService.activeService != null) {
                    EasyVpnService.stop(this)
                }
            }
        }
        "operaproxy" -> {
            if (!ArchHelper.isOperaSupported) return
            if (enabled) {
                if (!OperaProxyService.isRunning || configChanged) {
                    OperaProxyService.start(this)
                }
            } else if (!Prefs.isProviderNeededStandalone("operaproxy")) {
                if (OperaProxyService.isRunning || OperaProxyService.activeService != null) {
                    OperaProxyService.stop(this)
                }
            }
        }
        "svpn" -> {
            if (enabled) {
                if (!SvpnService.isRunning || configChanged) {
                    SvpnService.start(this)
                }
            } else if (!Prefs.isProviderNeededStandalone("svpn")) {
                if (SvpnService.isRunning || SvpnService.activeService != null) {
                    SvpnService.stop(this)
                }
            }
        }
        "tgws" -> {
            if (enabled) {
                if (!TgwsService.isRunning || configChanged) {
                    TgwsService.start(
                        this,
                        Prefs.tgwsHost, Prefs.tgwsPort, Prefs.tgwsDcMappings, Prefs.tgwsSecret,
                        Prefs.tgwsFakeTls, false, Prefs.tgwsCfWorkerDomains, Prefs.tgwsCfProxyDomains,
                        fallbackCfProxy = Prefs.tgwsFallbackCfProxy, cfProxyH2Media = Prefs.tgwsCfProxyH2Media,
                        frontingSni = Prefs.tgwsFrontingSni, disableSecure = Prefs.tgwsDisableSecure,
                        forceTestDc = Prefs.tgwsForceTestDc, poolSize = Prefs.tgwsPoolSize
                    )
                }
            } else {
                if (TgwsService.isRunning || TgwsService.activeService != null) {
                    TgwsService.stop(this)
                }
            }
        }
        else -> Log.w(EXT_TAG, "syncStandaloneComponent: unknown provider '$provider'")
    }
    if (!enabled && !Prefs.isGlobalVpnEnabled) {
        refreshStandalonePrimaryNotifications()
    }
}

/**
 * Enables or disables global VPN mode.
 * When enabled: stops port-conflicting standalone services needed by VPN and starts AppVpnService.
 * When disabled: stops AppVpnService while preserving/reviving enabled standalone services,
 * or transitions UI cleanly to STATUS_OFF if no standalone services are active.
 */
private fun Context.hasLiveOrRevivableStandaloneServices(): Boolean {
    if (isAnyStandaloneServiceRunning()) return true
    if (AppVpnService.activeService != null) {
        return Prefs.isProviderNeededStandalone("easyvpn") ||
            Prefs.isProviderNeededStandalone("operaproxy") ||
            Prefs.isProviderNeededStandalone("svpn")
    }
    return false
}

fun Context.setGlobalVpnEnabled(enabled: Boolean) {
    setGlobalVpnEnabledForProfile(Prefs.getActiveVpnProfileKey(), enabled)
}

fun Context.setGlobalVpnEnabledForProfile(profile: String, enabled: Boolean) {
    Prefs.setGlobalVpnEnabledForProfile(profile, enabled)
    if (profile != Prefs.getActiveVpnProfileKey()) {
        return
    }
    if (!enabled && !hasLiveOrRevivableStandaloneServices()) {
        Prefs.isVpnOrProxySessionActive = false
    }
    applyVpnEnabledState(enabled)
}

fun Context.setSeparateVpnProfilesEnabled(enabled: Boolean) {
    val previousVpnEnabled = Prefs.isGlobalVpnEnabled
    if (enabled) {
        Prefs.ensureVpnNetworkProfilesInitialized()
    }
    Prefs.currentVpnNetworkType = if (NetworkUtils.isWifiConnected(this)) {
        Prefs.VPN_PROFILE_WIFI
    } else {
        Prefs.VPN_PROFILE_MOBILE
    }
    Prefs.vpnSeparateNetworkProfiles = enabled
    ProxifierRulesManager.invalidateCache()
    applyActiveNetworkVpnProfile(previousVpnEnabled)
}

fun Context.applyActiveNetworkVpnProfile(previousVpnEnabled: Boolean) {
    val newVpnEnabled = Prefs.isGlobalVpnEnabled
    val vpnRunning = AppVpnService.isConnected ||
        AppVpnService.activeService != null ||
        AppVpnService.currentStatus != ServiceStatus.Disconnected

    when {
        !previousVpnEnabled && newVpnEnabled -> {
            if (Prefs.isVpnOrProxySessionActive && VpnService.prepare(this) == null) {
                applyVpnEnabledState(true)
            }
        }
        previousVpnEnabled && !newVpnEnabled -> {
            applyVpnEnabledState(false)
        }
        newVpnEnabled && vpnRunning -> {
            sendIntentToService(
                Intent(this, AppVpnService::class.java).apply {
                    action = AppVpnService.ACTION_RELOAD_PROFILE
                    putExtra("is_foreground", false)
                },
                isForeground = false
            )
            refreshActiveForegroundNotification(this)
        }
        !newVpnEnabled -> {
            if (isAnyStandaloneServiceRunning()) {
                refreshStandalonePrimaryNotifications()
                broadcastLocalStatus(TorService.STATUS_ON)
            } else if (!Prefs.isVpnOrProxySessionActive) {
                broadcastLocalStatus(TorService.STATUS_OFF)
            } else {
                refreshActiveForegroundNotification(this)
            }
        }
    }
}

private fun Context.applyVpnEnabledState(enabled: Boolean) {
    if (enabled) {
        Prefs.isVpnOrProxySessionActive = true
        val neededVpn = try {
            ProxifierRulesManager.getAllNeededVpnProviders()
        } catch (_: Throwable) {
            emptySet()
        }
        // Stop port-conflicting standalone services that AppVpnService will manage in-process
        if (neededVpn.contains("easyvpn") && (EasyVpnService.isRunning || EasyVpnService.activeService != null)) {
            EasyVpnService.stop(this)
        }
        if (neededVpn.contains("operaproxy") && (OperaProxyService.isRunning || OperaProxyService.activeService != null)) {
            OperaProxyService.stop(this)
        }
        if ((neededVpn.contains("svpn") || neededVpn.contains("ssh")) && (SvpnService.isRunning || SvpnService.activeService != null)) {
            SvpnService.stop(this)
        }
        val started = sendIntentToService(
            Intent(this, AppVpnService::class.java).apply {
                action = START_ACTION
                putExtra("is_foreground", true)
            },
            isForeground = true
        )
        if (!started && !isAnyStandaloneServiceRunning()) {
            broadcastLocalStatus(TorService.STATUS_OFF)
        }
    } else {
        val vpnWasRunning = AppVpnService.isConnected ||
            AppVpnService.activeService != null ||
            AppVpnService.currentStatus != ServiceStatus.Disconnected
        if (vpnWasRunning) {
            // AppVpnService.stopTun2Socks() will preserve/revive enabled standalone services
            sendIntentToService(
                Intent(this, AppVpnService::class.java).apply {
                    action = STOP_ACTION
                    putExtra("is_foreground", false)
                },
                isForeground = false
            )
        } else {
            // Ensure notification priority and UI status reflect current standalone services cleanly
            if (isAnyStandaloneServiceRunning()) {
                refreshStandalonePrimaryNotifications()
                broadcastLocalStatus(TorService.STATUS_ON)
            } else {
                broadcastLocalStatus(TorService.STATUS_OFF)
            }
        }
    }
}

/**
 * Refreshes already-running standalone services (with isForeground=false since they are already foreground)
 * so the new primary standalone service switches from silent to primary notification channel.
 */
internal fun Context.refreshStandalonePrimaryNotifications() {
    if (Prefs.isGlobalVpnEnabled) return
    refreshActiveForegroundNotification(this)
    if (ByeDpiProxyService.status == ServiceStatus.Connected) {
        sendIntentToService(
            Intent(this, ByeDpiProxyService::class.java).apply {
                action = "ACTIVE"
                putExtra("is_foreground", false)
            },
            isForeground = false
        )
    }
    if (EasyVpnService.activeService != null) {
        sendIntentToService(
            Intent(this, EasyVpnService::class.java).apply {
                action = "ACTIVE"
                putExtra("is_foreground", false)
            },
            isForeground = false
        )
    }
    if (OperaProxyService.activeService != null) {
        sendIntentToService(
            Intent(this, OperaProxyService::class.java).apply {
                action = "ACTIVE"
                putExtra("is_foreground", false)
            },
            isForeground = false
        )
    }
    if (SvpnService.activeService != null) {
        sendIntentToService(
            Intent(this, SvpnService::class.java).apply {
                action = "ACTIVE"
                putExtra("is_foreground", false)
            },
            isForeground = false
        )
    }
    if (TgwsService.activeService != null) {
        sendIntentToService(
            Intent(this, TgwsService::class.java).apply {
                action = "ACTIVE"
                putExtra("is_foreground", false)
            },
            isForeground = false
        )
    }
    if (Prefs.torRuntimeStatus != TorService.STATUS_OFF && isServiceRunningInPackage(ToInetService::class.java)) {
        sendIntentToService(
            Intent(this, ToInetService::class.java).apply {
                action = ToInetConstants.CMD_ACTIVE
                putExtra("is_foreground", false)
            },
            isForeground = false
        )
    }
}

fun <K, V> Map<K, V>.getKey(value: V) =
    entries.firstOrNull { it.value == value }?.key

fun Context.showToast(msg: CharSequence) =
    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

fun Context.showToast(@StringRes msgId: Int) =
    Toast.makeText(this, msgId, Toast.LENGTH_LONG).show()

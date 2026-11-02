package ru.toinet.android.util

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import ru.toinet.android.service.ToInetConstants
import ru.toinet.android.service.ToInetService
import java.text.Normalizer


fun Intent.putNotSystem(): Intent = this.putExtra(ToInetConstants.EXTRA_NOT_SYSTEM, true)


fun Context.sendIntentToService(intent: Intent, isForeground: Boolean = true) {
    try {
        if (isForeground && canStartForegroundServices()) {
            ContextCompat.startForegroundService(this, intent.putNotSystem())
        } else {
            startService(intent.putNotSystem())
        }
    } catch (e: Exception) {
        Log.e("ToInetService", "Failed to sendIntentToService for action: ${intent.action}", e)
        if (isForeground) {
            try {
                startService(intent.putNotSystem())
            } catch (e2: Exception) {
                Log.e("ToInetService", "Fallback startService failed as well", e2)
            }
        }
    }
}

fun Context.canStartForegroundServices(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        return true

    try {
        if (androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
            return true
        }
    } catch (_: Exception) {}

    if (Prefs.isGlobalVpnEnabled && VpnService.prepare(this) == null)
        return true

    val alarmManager = ContextCompat.getSystemService(this, AlarmManager::class.java)
    return alarmManager?.canScheduleExactAlarms() ?: false
}


fun Context.sendIntentToService(action: String) {
    val transport = Prefs.transport
    val torEnabled = Prefs.torEnabled
    val byedpiEnabled = Prefs.byedpiEnabled
    val byedpiMode = Prefs.byedpiMode
    val tgwsEnabled = Prefs.tgwsEnabled
    val rehabilitatorEnabled = Prefs.rehabilitatorEnabled
    val operaProxyEnabled = Prefs.operaProxyEnabled
    val zapretEnabled = Prefs.zapretEnabled
    val easyVpnProxyEnabled = Prefs.easyVpnProxyEnabled
    val svpnEnabled = Prefs.svpnEnabled

    val isPing = action == "ACTIVE" || action == ToInetConstants.CMD_ACTIVE
    val isStop = action == org.torproject.jni.TorService.ACTION_STOP

    val isGlobalVpnEnabled = Prefs.isGlobalVpnEnabled
    val isAppRouting = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && Prefs.appRoutingEnabled
    val neededProviders = if (isGlobalVpnEnabled && isAppRouting) {
        ru.toinet.android.vpn.rules.ProxifierRulesManager.getNeededProviders()
    } else emptySet()

    val isByedpiActive = byedpiEnabled || (isGlobalVpnEnabled && (Prefs.vpnProvider == "byedpi" || neededProviders.contains("byedpi")))
    if (isByedpiActive || action == org.torproject.jni.TorService.ACTION_STOP) {
        val byedpiAction = when (action) {
            org.torproject.jni.TorService.ACTION_START -> ru.toinet.android.vpn.data.START_ACTION
            org.torproject.jni.TorService.ACTION_STOP -> ru.toinet.android.vpn.data.STOP_ACTION
            else -> action
        }
        sendIntentToService(
            Intent(this, ru.toinet.android.byedpi.services.ByeDpiProxyService::class.java).apply {
                this.action = byedpiAction
            },
            isForeground = !isPing && !isStop && !isGlobalVpnEnabled && Prefs.isPrimaryNotificationService("byedpi")
        )
    }

    if (!isGlobalVpnEnabled && zapretEnabled) {
        if (action == org.torproject.jni.TorService.ACTION_START) {
            ru.toinet.android.zapret.ZapretService.start(this)
        } else if (isPing && ru.toinet.android.zapret.ZapretService.isRunning) {
            try {
                startService(Intent(this, ru.toinet.android.zapret.ZapretService::class.java).apply {
                    this.action = "ACTIVE"
                })
            } catch (e: Exception) {
                Log.e("ToInetService", "Failed to ping ZapretService", e)
            }
        }
    }
    if (action == org.torproject.jni.TorService.ACTION_STOP) {
        ru.toinet.android.zapret.ZapretService.stop(this)
    }

    if (isGlobalVpnEnabled || action == org.torproject.jni.TorService.ACTION_STOP) {
        val vpnAction = when (action) {
            org.torproject.jni.TorService.ACTION_START -> ru.toinet.android.vpn.data.START_ACTION
            org.torproject.jni.TorService.ACTION_STOP -> ru.toinet.android.vpn.data.STOP_ACTION
            else -> action
        }
        sendIntentToService(
            Intent(this, ru.toinet.android.vpn.services.AppVpnService::class.java).apply {
                this.action = vpnAction
            },
            isForeground = !isPing && !isStop
        )
    }

    val isTorActive = torEnabled || (isGlobalVpnEnabled && (Prefs.vpnProvider == "tor" || neededProviders.contains("tor")))
    if (isTorActive || action == org.torproject.jni.TorService.ACTION_STOP) {
        sendIntentToService(
            Intent(this, ToInetService::class.java).apply {
                this.action = action
            },
            isForeground = !isPing && !isStop && !isGlobalVpnEnabled && Prefs.isPrimaryNotificationService("tor")
        )
    }

    /*
    val isTgwsActive = tgwsEnabled || (isGlobalVpnEnabled && Prefs.vpnProvider == "tgws")
    if (isTgwsActive || action == org.torproject.jni.TorService.ACTION_STOP) {
        if (action == org.torproject.jni.TorService.ACTION_START) {
            ru.toinet.android.tgws.TgwsService.start(
                this,
                Prefs.tgwsHost,
                Prefs.tgwsPort,
                Prefs.tgwsDcMappings,
                Prefs.tgwsSecret,
                Prefs.tgwsFakeTls,
                false,
                Prefs.tgwsCfWorkerDomains,
                Prefs.tgwsCfProxyDomains
            )
        } else if (action == org.torproject.jni.TorService.ACTION_STOP) {
            ru.toinet.android.tgws.TgwsService.stop(this)
        }
    }
    */

    if (rehabilitatorEnabled || (isGlobalVpnEnabled && (Prefs.vpnProvider == "rehab" || Prefs.vpnProvider == "custom")) || action == org.torproject.jni.TorService.ACTION_STOP) {
        if (action == org.torproject.jni.TorService.ACTION_START) {
            ru.toinet.android.rehabilitator.RehabilitatorService.start(this)
        } else if (action == org.torproject.jni.TorService.ACTION_STOP) {
            ru.toinet.android.rehabilitator.RehabilitatorService.stop(this)
        }
    }
    
    if (!isGlobalVpnEnabled && operaProxyEnabled && ArchHelper.isOperaSupported) {
        if (action == org.torproject.jni.TorService.ACTION_START) {
            ru.toinet.android.operaproxy.OperaProxyService.start(this)
        } else if (isPing && ru.toinet.android.operaproxy.OperaProxyService.isRunning) {
            try {
                startService(Intent(this, ru.toinet.android.operaproxy.OperaProxyService::class.java).apply {
                    this.action = "ACTIVE"
                })
            } catch (e: Exception) {
                Log.e("ToInetService", "Failed to ping OperaProxyService", e)
            }
        }
    }
    if (action == org.torproject.jni.TorService.ACTION_STOP) {
        ru.toinet.android.operaproxy.OperaProxyService.stop(this)
    }

    if ((!isGlobalVpnEnabled || Prefs.vpnProvider != "easyvpn") && easyVpnProxyEnabled) {
        if (action == org.torproject.jni.TorService.ACTION_START) {
            ru.toinet.android.easyvpn.EasyVpnService.start(this)
        } else if (isPing) {
            if (ru.toinet.android.easyvpn.EasyVpnService.isRunning) {
                try {
                    startService(Intent(this, ru.toinet.android.easyvpn.EasyVpnService::class.java).apply {
                        this.action = "ACTIVE"
                    })
                } catch (e: Exception) {
                    Log.e("ToInetService", "Failed to ping EasyVpnService", e)
                }
            } else {
                ru.toinet.android.easyvpn.EasyVpnService.start(this)
            }
        }
    }
    if (action == org.torproject.jni.TorService.ACTION_STOP) {
        ru.toinet.android.easyvpn.EasyVpnService.stop(this)
    }

    if ((!isGlobalVpnEnabled || (Prefs.vpnProvider != "svpn" && Prefs.vpnProvider != "ssh")) && svpnEnabled) {
        if (action == org.torproject.jni.TorService.ACTION_START) {
            ru.toinet.android.svpn.SvpnService.start(this)
        } else if (isPing && ru.toinet.android.svpn.SvpnService.isRunning) {
            try {
                startService(Intent(this, ru.toinet.android.svpn.SvpnService::class.java).apply {
                    this.action = "ACTIVE"
                })
            } catch (e: Exception) {
                Log.e("ToInetService", "Failed to ping SvpnService", e)
            }
        }
    }
    if (action == org.torproject.jni.TorService.ACTION_STOP) {
        ru.toinet.android.svpn.SvpnService.stop(this)
    }

    if (!torEnabled && !isPing) {
        if (action == org.torproject.jni.TorService.ACTION_START) {
            val isOperaActive = operaProxyEnabled && ArchHelper.isOperaSupported
            val isEasyVpnActive = (!isGlobalVpnEnabled || Prefs.vpnProvider != "easyvpn") && easyVpnProxyEnabled
            val isSvpnActive = (!isGlobalVpnEnabled || (Prefs.vpnProvider != "svpn" && Prefs.vpnProvider != "ssh")) && svpnEnabled
            if (!isGlobalVpnEnabled && !byedpiEnabled && (rehabilitatorEnabled || isOperaActive || zapretEnabled || isEasyVpnActive || isSvpnActive)) {
                val intent = Intent(ToInetConstants.LOCAL_ACTION_STATUS)
                intent.putExtra(org.torproject.jni.TorService.EXTRA_STATUS, org.torproject.jni.TorService.STATUS_ON)
                intent.setPackage(packageName)
                sendBroadcast(intent)
            } else if (!isGlobalVpnEnabled && !byedpiEnabled && !rehabilitatorEnabled && !operaProxyEnabled && !zapretEnabled && !isEasyVpnActive && !isSvpnActive) {
                val intent = Intent(ToInetConstants.LOCAL_ACTION_STATUS)
                intent.putExtra(org.torproject.jni.TorService.EXTRA_STATUS, org.torproject.jni.TorService.STATUS_OFF)
                intent.setPackage(packageName)
                sendBroadcast(intent)
            }
        } else if (action == org.torproject.jni.TorService.ACTION_STOP) {
            if (!isGlobalVpnEnabled && !byedpiEnabled) {
                val intent = Intent(ToInetConstants.LOCAL_ACTION_STATUS)
                intent.putExtra(org.torproject.jni.TorService.EXTRA_STATUS, org.torproject.jni.TorService.STATUS_OFF)
                intent.setPackage(packageName)
                sendBroadcast(intent)
            }
        }
    }
}


fun <K, V> Map<K, V>.getKey(value: V) =
    entries.firstOrNull { it.value == value }?.key

fun Context.showToast(msg: CharSequence) =
    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

fun Context.showToast(@StringRes msgId: Int) =
    Toast.makeText(this, msgId, Toast.LENGTH_LONG).show()


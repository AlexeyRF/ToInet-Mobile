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
import ru.toinet.android.service.OrbotConstants
import ru.toinet.android.service.OrbotService
import java.text.Normalizer


fun Intent.putNotSystem(): Intent = this.putExtra(OrbotConstants.EXTRA_NOT_SYSTEM, true)


fun Context.sendIntentToService(intent: Intent, isForeground: Boolean = true) {
    //    Log.d("OrbotService", "sendIntentToService-${intent.action}")
    if (isForeground && canStartForegroundServices()) {
        ContextCompat.startForegroundService(this, intent.putNotSystem())
    } else {
        startService(intent.putNotSystem())
    }
}

fun Context.canStartForegroundServices(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        return true

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

    val turnProxyEnabled = Prefs.turnProxyEnabled
    val operaProxyEnabled = Prefs.operaProxyEnabled

    val isPing = action == "ACTIVE" || action == OrbotConstants.CMD_ACTIVE
    val isStop = action == org.torproject.jni.TorService.ACTION_STOP

    val isGlobalVpnEnabled = Prefs.isGlobalVpnEnabled

    if (byedpiEnabled || action == org.torproject.jni.TorService.ACTION_STOP) {
        val byedpiAction = when (action) {
            org.torproject.jni.TorService.ACTION_START -> ru.toinet.android.byedpi.data.START_ACTION
            org.torproject.jni.TorService.ACTION_STOP -> ru.toinet.android.byedpi.data.STOP_ACTION
            else -> action
        }
        sendIntentToService(
            Intent(this, ru.toinet.android.byedpi.services.ByeDpiProxyService::class.java).apply {
                this.action = byedpiAction
            },
            isForeground = !isPing && !isStop
        )
    }

    if (isGlobalVpnEnabled || action == org.torproject.jni.TorService.ACTION_STOP) {
        val vpnAction = when (action) {
            org.torproject.jni.TorService.ACTION_START -> ru.toinet.android.byedpi.data.START_ACTION
            org.torproject.jni.TorService.ACTION_STOP -> ru.toinet.android.byedpi.data.STOP_ACTION
            else -> action
        }
        sendIntentToService(
            Intent(this, ru.toinet.android.byedpi.services.ByeDpiVpnService::class.java).apply {
                this.action = vpnAction
            },
            isForeground = !isPing && !isStop
        )
    }

    if (torEnabled || action == org.torproject.jni.TorService.ACTION_STOP) {
        sendIntentToService(
            Intent(this, OrbotService::class.java).apply {
                this.action = action
            },
            isForeground = !isPing && !isStop
        )
    }

    if (tgwsEnabled || action == org.torproject.jni.TorService.ACTION_STOP) {
        if (action == org.torproject.jni.TorService.ACTION_START) {
            ru.toinet.android.tgws.TgwsService.start(
                this,
                Prefs.tgwsHost,
                Prefs.tgwsPort,
                Prefs.tgwsDcMappings,
                Prefs.tgwsSecret,
                Prefs.tgwsFakeTls,
                Prefs.tgwsUseByeDpi
            )
        } else if (action == org.torproject.jni.TorService.ACTION_STOP) {
            ru.toinet.android.tgws.TgwsService.stop(this)
        }
    }

    if (rehabilitatorEnabled || action == org.torproject.jni.TorService.ACTION_STOP) {
        if (action == org.torproject.jni.TorService.ACTION_START) {
            ru.toinet.android.rehabilitator.RehabilitatorService.start(this)
        } else if (action == org.torproject.jni.TorService.ACTION_STOP) {
            ru.toinet.android.rehabilitator.RehabilitatorService.stop(this)
        }
    }

    if (turnProxyEnabled || action == org.torproject.jni.TorService.ACTION_STOP) {
        if (action == org.torproject.jni.TorService.ACTION_START) {
            ru.toinet.android.turnproxy.TurnProxyService.start(this)
        } else if (action == org.torproject.jni.TorService.ACTION_STOP) {
            ru.toinet.android.turnproxy.TurnProxyService.stop(this)
        }
    }
    
    if (operaProxyEnabled || action == org.torproject.jni.TorService.ACTION_STOP) {
        if (action == org.torproject.jni.TorService.ACTION_START) {
            ru.toinet.android.operaproxy.OperaProxyService.start(this)
        } else if (action == org.torproject.jni.TorService.ACTION_STOP) {
            ru.toinet.android.operaproxy.OperaProxyService.stop(this)
        }
    }

    if (!torEnabled && !isPing) {
        if (action == org.torproject.jni.TorService.ACTION_START) {
            if (!byedpiEnabled && (tgwsEnabled || rehabilitatorEnabled || turnProxyEnabled || operaProxyEnabled)) {
                val intent = Intent(OrbotConstants.LOCAL_ACTION_STATUS)
                intent.putExtra(org.torproject.jni.TorService.EXTRA_STATUS, org.torproject.jni.TorService.STATUS_ON)
                intent.setPackage(packageName)
                sendBroadcast(intent)
            } else if (!byedpiEnabled && !tgwsEnabled && !rehabilitatorEnabled && !turnProxyEnabled && !operaProxyEnabled) {
                val intent = Intent(OrbotConstants.LOCAL_ACTION_STATUS)
                intent.putExtra(org.torproject.jni.TorService.EXTRA_STATUS, org.torproject.jni.TorService.STATUS_OFF)
                intent.setPackage(packageName)
                sendBroadcast(intent)
            }
        } else if (action == org.torproject.jni.TorService.ACTION_STOP) {
            if (!byedpiEnabled) {
                val intent = Intent(OrbotConstants.LOCAL_ACTION_STATUS)
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

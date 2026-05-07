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


fun Context.sendIntentToService(intent: Intent) {
    //    Log.d("OrbotService", "sendIntentToService-${intent.action}")
    if (canStartForegroundServices()) {
        ContextCompat.startForegroundService(this, intent.putNotSystem())
    } else {
        Log.e(
            "OrbotService",
            "Need additional permissions to start OrbotService in foreground (action=${intent.action})"
        )
    }
}

fun Context.canStartForegroundServices(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        return true

    if (VpnService.prepare(this) == null)
        return true

    val alarmManager = ContextCompat.getSystemService(this, AlarmManager::class.java)
    return alarmManager?.canScheduleExactAlarms() ?: false
}


fun Context.sendIntentToService(action: String) =
    sendIntentToService(
        Intent(this, OrbotService::class.java).apply {
            this.action = action
        }
    )


fun <K, V> Map<K, V>.getKey(value: V) =
    entries.firstOrNull { it.value == value }?.key

fun Context.showToast(msg: CharSequence) =
    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

fun Context.showToast(@StringRes msgId: Int) =
    Toast.makeText(this, msgId, Toast.LENGTH_LONG).show()

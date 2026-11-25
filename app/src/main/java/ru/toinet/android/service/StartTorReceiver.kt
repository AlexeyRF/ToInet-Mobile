package ru.toinet.android.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.text.TextUtils
import ru.toinet.android.service.ToInetConstants.STATUS_STARTS_DISABLED
import ru.toinet.android.util.Prefs
import ru.toinet.android.util.sendIntentToService
import org.torproject.jni.TorService.ACTION_START
import org.torproject.jni.TorService.ACTION_STATUS
import org.torproject.jni.TorService.EXTRA_PACKAGE_NAME
import org.torproject.jni.TorService.EXTRA_STATUS

class StartTorReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            /* sanitize the Intent before forwarding it to ToInetService */
            Prefs.setContext(context.applicationContext)
            val action = intent.action
            val isStart = TextUtils.equals(action, ACTION_START) ||
                    TextUtils.equals(action, "ru.toinet.android.intent.action.START")
            if (isStart) {
                val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME)
                if (Prefs.allowBackgroundStarts()) {
                    val startTorIntent = Intent(context, ToInetService::class.java)
                        .setAction(ACTION_START)
                        .putExtra(ToInetConstants.EXTRA_NOT_SYSTEM, true)
                        .putExtra("is_foreground", true)
                    if (packageName != null) {
                        startTorIntent.putExtra(EXTRA_PACKAGE_NAME, packageName)
                    }
                    context.sendIntentToService(startTorIntent, isForeground = true)
                } else if (!TextUtils.isEmpty(packageName)) {
                    // let the requesting app know that the user has disabled starting via Intent
                    val startsDisabledIntent = Intent(ACTION_STATUS).apply {
                        putExtra(EXTRA_STATUS, STATUS_STARTS_DISABLED)
                        `package` = packageName
                    }
                    context.sendBroadcast(startsDisabledIntent)
                }
            }
        } catch (_: RuntimeException) {
            //catch this to avoid malicious launches as document Cure53 Audit: ORB-01-009 WP1/2: ToInet DoS via exported activity (High)
        }
    }
}

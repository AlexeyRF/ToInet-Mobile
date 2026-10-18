package ru.toinet.android.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import ru.toinet.android.service.ToInetService
import ru.toinet.android.util.Prefs
import ru.toinet.android.util.putNotSystem
import ru.toinet.android.util.sendIntentToService
import org.torproject.jni.TorService.ACTION_START
import java.lang.RuntimeException

class OnBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            if (intent.action != "android.intent.action.QUICKBOOT_POWERON" &&
                intent.action != "android.intent.action.BOOT_COMPLETED")
                return

            if (Build.FINGERPRINT.contains("sdk_gphone")) {
                // on pixels emulated in new android studio on boot
                // gets launched every time you click run. this is annoying
                // for debugging and gets in the way of automated screenshots
                return
            }
            if (Prefs.startOnBoot() && !sReceivedBoot) {
                startService(context)
                sReceivedBoot = true
            }
        } catch (_: RuntimeException) {
            //catch this to avoid malicious launches as document Cure53 Audit: ORB-01-009 WP1/2: ToInet DoS via exported activity (High)
        }
    }

    private fun startService(context: Context) {
        try {
            context.sendIntentToService(ACTION_START)
        } catch (_: RuntimeException) {
            //catch this to avoid malicious launches as document Cure53 Audit: ORB-01-009 WP1/2: ToInet DoS via exported activity (High)
        }
    }

    companion object {
        private var sReceivedBoot = false
    }
}
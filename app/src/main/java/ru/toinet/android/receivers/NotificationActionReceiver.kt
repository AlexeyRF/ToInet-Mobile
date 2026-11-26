package ru.toinet.android.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.torproject.jni.TorService
import ru.toinet.android.util.Prefs
import ru.toinet.android.util.sendIntentToService

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == ACTION_STOP_ALL) {
            val appCtx = context.applicationContext
            Prefs.setContext(appCtx)
            appCtx.sendIntentToService(TorService.ACTION_STOP)
        }
    }

    companion object {
        const val ACTION_STOP_ALL = "ru.toinet.android.action.NOTIFICATION_STOP_ALL"
    }
}

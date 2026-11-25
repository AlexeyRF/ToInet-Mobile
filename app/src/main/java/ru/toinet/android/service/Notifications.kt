package ru.toinet.android.service

import android.content.Context
import androidx.core.app.NotificationCompat
import org.torproject.jni.TorService
import ru.toinet.android.R
import ru.toinet.android.vpn.utility.PRIMARY_CHANNEL_ID
import ru.toinet.android.vpn.utility.SILENT_CHANNEL_ID
import ru.toinet.android.vpn.utility.ensureSharedNotificationChannels

object Notifications {
    @JvmStatic
    fun createNotificationChannel(context: Context) {
        ensureSharedNotificationChannels(context)
    }

    @JvmStatic
    fun getNotificationTitleForStatus(context: Context, torStatus: String): String {
        if (torStatus == TorService.STATUS_STARTING)
            return context.getString(R.string.status_starting_up)
        else if (torStatus == TorService.STATUS_ON)
            return context.getString(R.string.status_activated)
        return context.getString(R.string.status_disabled)
    }

    @JvmStatic
    fun resolveTorNotificationChannel(): String {
        if (ru.toinet.android.util.Prefs.shouldShowDetailedNotification("tor") ||
            ru.toinet.android.util.Prefs.isPrimaryNotificationService("tor")
        ) {
            return TOINET_SERVICE_NOTIFICATION_CHANNEL
        }
        return TOINET_SERVICE_NOTIFICATION_CHANNEL_SILENT
    }

    @JvmStatic
    fun resolveTorNotificationPriority(): Int {
        if (ru.toinet.android.util.Prefs.shouldShowDetailedNotification("tor") ||
            ru.toinet.android.util.Prefs.isPrimaryNotificationService("tor")
        ) {
            return NotificationCompat.PRIORITY_LOW
        }
        return NotificationCompat.PRIORITY_MIN
    }

    const val TOINET_SERVICE_NOTIFICATION_CHANNEL = PRIMARY_CHANNEL_ID
    const val TOINET_SERVICE_NOTIFICATION_CHANNEL_MIN = PRIMARY_CHANNEL_ID
    const val TOINET_SERVICE_NOTIFICATION_CHANNEL_SILENT = SILENT_CHANNEL_ID
}

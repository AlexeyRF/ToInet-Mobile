package ru.toinet.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import ru.toinet.android.R
import org.torproject.jni.TorService

object Notifications {
    @JvmStatic
    @RequiresApi(api = Build.VERSION_CODES.O)
    fun createNotificationChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val appName = context.getString(R.string.app_name)
        val channelDescription = context.getString(R.string.app_description)

        manager.createNotificationChannel(
            NotificationChannel(
                TOINET_SERVICE_NOTIFICATION_CHANNEL,
                appName,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = channelDescription
                enableLights(false)
                enableVibration(false)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
        )

        try {
            manager.deleteNotificationChannel("toinet_channel_1_min")
        } catch (_: Exception) {}

        manager.createNotificationChannel(
            NotificationChannel(
                TOINET_SERVICE_NOTIFICATION_CHANNEL_MIN,
                appName,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = channelDescription
                enableLights(false)
                enableVibration(false)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
        )

        manager.createNotificationChannel(
            NotificationChannel(
                TOINET_SERVICE_NOTIFICATION_CHANNEL_SILENT,
                appName,
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = channelDescription
                enableLights(false)
                enableVibration(false)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
        )
    }


    @JvmStatic
    fun getNotificationTitleForStatus(context: Context, torStatus: String) : String {
        if (torStatus == TorService.STATUS_STARTING)
            return context.getString(R.string.status_starting_up)
        else if (torStatus == TorService.STATUS_ON)
            return context.getString(R.string.status_activated)
        return context.getString(R.string.status_disabled)
    }

    @JvmStatic
    fun resolveTorNotificationChannel(): String {
        val current = ru.toinet.android.util.Prefs.notificationLogProvider
        if (ru.toinet.android.util.Prefs.shouldShowDetailedNotification("tor")) {
            return TOINET_SERVICE_NOTIFICATION_CHANNEL
        }
        if (current == "none" && ru.toinet.android.util.Prefs.isPrimaryNotificationService("tor")) {
            return TOINET_SERVICE_NOTIFICATION_CHANNEL_MIN
        }
        return TOINET_SERVICE_NOTIFICATION_CHANNEL_SILENT
    }

    @JvmStatic
    fun resolveTorNotificationPriority(): Int {
        val current = ru.toinet.android.util.Prefs.notificationLogProvider
        if (ru.toinet.android.util.Prefs.shouldShowDetailedNotification("tor")) {
            return NotificationCompat.PRIORITY_LOW
        }
        if (current == "none" && ru.toinet.android.util.Prefs.isPrimaryNotificationService("tor")) {
            return NotificationCompat.PRIORITY_LOW
        }
        return NotificationCompat.PRIORITY_MIN
    }

    const val TOINET_SERVICE_NOTIFICATION_CHANNEL = "toinet_channel_1"
    const val TOINET_SERVICE_NOTIFICATION_CHANNEL_MIN = "toinet_channel_mini"
    const val TOINET_SERVICE_NOTIFICATION_CHANNEL_SILENT = "toinet_channel_silent"
}
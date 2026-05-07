package ru.toinet.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import ru.toinet.android.R
import ru.toinet.android.util.Prefs
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
                ORBOT_SERVICE_NOTIFICATION_CHANNEL,
                appName,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
            description = channelDescription
            enableLights(false)
            enableVibration(false)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
        })
    }

    @JvmStatic
    fun configureCamoNotification(notifyBuilder: NotificationCompat.Builder) {
        notifyBuilder
            .setContentTitle(Prefs.camoAppDisplayName)
            .setContentText(null)
            .setSubText(null)
            .setSmallIcon(R.drawable.ic_generic_info)
            .setProgress(0, 0, false)
            .setContentIntent(null)
    }

    @JvmStatic
    fun getNotificationTitleForStatus(context: Context, torStatus: String) : String {
        if (torStatus == TorService.STATUS_STARTING)
            return context.getString(R.string.status_starting_up)
        else if (torStatus == TorService.STATUS_ON)
            return context.getString(R.string.status_activated)
        return context.getString(R.string.status_disabled)
    }

    const val ORBOT_SERVICE_NOTIFICATION_CHANNEL = "orbot_channel_1"
}
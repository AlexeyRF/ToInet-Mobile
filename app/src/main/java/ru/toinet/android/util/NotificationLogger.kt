package ru.toinet.android.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import ru.toinet.android.ToInetActivity
import ru.toinet.android.R

object NotificationLogger {
    fun log(context: Context, providerName: String, message: String) {
        if (!Prefs.shouldShowDetailedNotification(providerName)) return

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val channelId = when (providerName) {
            "tgws" -> "tgws_channel"
            "operaproxy" -> "opera_proxy_channel"
            "zapret" -> "zapret_channel"
            else -> "toinet_log_channel"
        }

        val notifyId = when (providerName) {
            "tgws" -> 1480
            "operaproxy" -> 1888
            "zapret" -> 1081
            else -> 1999
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "ToInet: $providerName",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                enableLights(false)
                enableVibration(false)
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }

        val intent = Intent(context, ToInetActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = when (providerName) {
            "operaproxy" -> context.getString(R.string.opera_proxy_notification_title)
            "zapret" -> context.getString(R.string.zapret_notification_title)
            "tgws" -> "TGWS"
            else -> "ToInet: $providerName"
        }

        val builder = NotificationCompat.Builder(context, channelId)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_stat_tor)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setGroup("toinet_services")
            .setOnlyAlertOnce(true)

        manager.notify(notifyId, builder.build())
    }
}

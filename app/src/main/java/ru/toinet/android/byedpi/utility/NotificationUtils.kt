package ru.toinet.android.byedpi.utility

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import ru.toinet.android.R
import ru.toinet.android.OrbotActivity
import ru.toinet.android.byedpi.data.STOP_ACTION

fun registerNotificationChannel(context: Context, id: String, @StringRes name: Int) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        val isNone = ru.toinet.android.util.Prefs.notificationLogProvider == "none"
        val actualId = if (isNone) "${id}_min" else "${id}_low"
        val importance = if (isNone) NotificationManager.IMPORTANCE_MIN else NotificationManager.IMPORTANCE_LOW

        val channel = NotificationChannel(
            actualId,
            context.getString(name),
            importance
        )
        channel.enableLights(false)
        channel.enableVibration(false)
        channel.setShowBadge(false)

        manager.createNotificationChannel(channel)
    }
}

fun createConnectionNotification(
    context: Context,
    channelId: String,
    @StringRes title: Int,
    @StringRes content: Int,
    service: Class<*>,
): Notification {
    val isNone = ru.toinet.android.util.Prefs.notificationLogProvider == "none"
    val actualId = if (isNone) "${channelId}_min" else "${channelId}_low"

    val builder = NotificationCompat.Builder(context, actualId)
        .setSmallIcon(R.drawable.ic_stat_tor)
        .setSilent(true)
        .setGroup("toinet_services")
        .setPriority(if (isNone) NotificationCompat.PRIORITY_MIN else NotificationCompat.PRIORITY_LOW)
        .setContentIntent(
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, OrbotActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
        )

    if (isNone) {
        builder.setContentTitle("ToInet")
    } else {
        builder.setContentTitle(context.getString(title))
            .setContentText(context.getString(content))
            .addAction(0, context.getString(R.string.disable),
                PendingIntent.getService(
                    context,
                    0,
                    Intent(context, service).setAction(STOP_ACTION),
                    PendingIntent.FLAG_IMMUTABLE,
                )
            )
    }

    return builder.build()
}

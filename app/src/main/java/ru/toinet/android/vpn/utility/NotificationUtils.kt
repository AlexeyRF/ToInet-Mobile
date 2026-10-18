package ru.toinet.android.vpn.utility

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
import ru.toinet.android.ToInetActivity
import ru.toinet.android.vpn.data.STOP_ACTION

fun registerNotificationChannel(context: Context, id: String, channelName: String) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        try {
            manager.deleteNotificationChannel("${id}_min")
        } catch (_: Exception) {}

        val channelSilent = NotificationChannel(
            "${id}_silent",
            channelName,
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            enableLights(false)
            enableVibration(false)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
        }

        val channelMini = NotificationChannel(
            "${id}_mini",
            channelName,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            enableLights(false)
            enableVibration(false)
            setShowBadge(false)
        }

        val channelLow = NotificationChannel(
            "${id}_low",
            channelName,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            enableLights(false)
            enableVibration(false)
            setShowBadge(false)
        }

        manager.createNotificationChannel(channelSilent)
        manager.createNotificationChannel(channelMini)
        manager.createNotificationChannel(channelLow)
    }
}

fun registerNotificationChannel(context: Context, id: String, @StringRes name: Int) {
    registerNotificationChannel(context, id, context.getString(name))
}

fun resolveNotificationChannelAndPriority(serviceKey: String, baseChannelId: String): Pair<String, Int> {
    val current = ru.toinet.android.util.Prefs.notificationLogProvider
    if (ru.toinet.android.util.Prefs.shouldShowDetailedNotification(serviceKey)) {
        return Pair("${baseChannelId}_low", NotificationCompat.PRIORITY_LOW)
    }
    if (current == "none") {
        if (ru.toinet.android.util.Prefs.isPrimaryNotificationService(serviceKey)) {
            return Pair("${baseChannelId}_mini", NotificationCompat.PRIORITY_LOW)
        } else {
            return Pair("${baseChannelId}_silent", NotificationCompat.PRIORITY_MIN)
        }
    }
    // Non-selected background service: minimize completely so it doesn't clutter shade
    return Pair("${baseChannelId}_silent", NotificationCompat.PRIORITY_MIN)
}

fun createConnectionNotification(
    context: Context,
    channelId: String,
    @StringRes title: Int,
    @StringRes content: Int,
    service: Class<*>,
    serviceKey: String = "byedpi",
): Notification {
    registerNotificationChannel(context, channelId, title)

    val (actualId, priority) = resolveNotificationChannelAndPriority(serviceKey, channelId)
    val isDetailed = ru.toinet.android.util.Prefs.shouldShowDetailedNotification(serviceKey)

    val builder = NotificationCompat.Builder(context, actualId)
        .setSmallIcon(R.drawable.ic_stat_tor)
        .setSilent(true)
        .setOngoing(true)
        .setGroup("toinet_services")
        .setPriority(priority)
        .setContentIntent(
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, ToInetActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
        )

    if (!isDetailed) {
        builder.setContentTitle("ToInet")
            .setContentText(context.getString(title))
            .setSubText(null)
    } else {
        builder.setContentTitle(context.getString(title))
            .setContentText(context.getString(content))
    }

    builder.addAction(
        0,
        context.getString(R.string.disable),
        PendingIntent.getService(
            context,
            0,
            Intent(context, service).setAction(STOP_ACTION),
            PendingIntent.FLAG_IMMUTABLE,
        )
    )

    return builder.build()
}

fun createConnectionNotificationString(
    context: Context,
    channelId: String,
    title: String,
    content: String,
    service: Class<*>,
    switchServerIntent: PendingIntent? = null,
    serviceKey: String = "vpn",
): Notification {
    registerNotificationChannel(context, channelId, title)

    val (actualId, priority) = resolveNotificationChannelAndPriority(serviceKey, channelId)
    val isDetailed = ru.toinet.android.util.Prefs.shouldShowDetailedNotification(serviceKey)

    val builder = NotificationCompat.Builder(context, actualId)
        .setSmallIcon(R.drawable.ic_stat_tor)
        .setSilent(true)
        .setOngoing(true)
        .setGroup("toinet_services")
        .setPriority(priority)
        .setContentIntent(
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, ToInetActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
        )

    if (!isDetailed) {
        builder.setContentTitle("ToInet")
            .setContentText(title)
            .setSubText(null)
    } else {
        builder.setContentTitle(title)
            .setContentText(content)

        if (switchServerIntent != null) {
            builder.addAction(
                0,
                context.getString(R.string.easyvpn_switch_server),
                switchServerIntent
            )
        }
    }

    builder.addAction(0, context.getString(R.string.disable),
        PendingIntent.getService(
            context,
            0,
            Intent(context, service).setAction(STOP_ACTION),
            PendingIntent.FLAG_IMMUTABLE,
        )
    )

    return builder.build()
}

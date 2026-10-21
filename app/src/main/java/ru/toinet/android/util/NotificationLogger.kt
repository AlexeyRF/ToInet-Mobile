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
import ru.toinet.android.service.ToInetConstants
import ru.toinet.android.vpn.services.AppVpnService

object NotificationLogger {
    @Volatile
    var lastActiveVpnLog: String? = null

    fun log(context: Context, providerName: String, message: String) {
        val cleanMsg = message.trim()
        if (cleanMsg.isEmpty()) return

        // 1. Send log broadcast to update logBottomSheet and UI
        try {
            val logIntent = Intent(ToInetConstants.LOCAL_ACTION_LOG).apply {
                putExtra(ToInetConstants.LOCAL_EXTRA_LOG, "[$providerName] $cleanMsg")
                setPackage(context.packageName)
            }
            context.sendBroadcast(logIntent)
        } catch (_: Exception) {}

        // 2. If Global VPN is enabled and this provider is active, update the primary VPN notification
        val isVpn = Prefs.isGlobalVpnEnabled
        val vpnProvider = Prefs.vpnProvider
        val isCurrentVpn = isVpn && (
            providerName == vpnProvider ||
            (providerName in listOf("rehab", "custom") && vpnProvider in listOf("rehab", "custom"))
        )

        if (isCurrentVpn) {
            lastActiveVpnLog = cleanMsg
            AppVpnService.updateLogNotification(context, cleanMsg)
            return
        }

        // 3. For standalone proxy services
        if (!Prefs.shouldShowDetailedNotification(providerName)) return

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val channelId = when (providerName) {
            "tgws" -> "tgws_channel"
            "operaproxy" -> "opera_proxy_channel"
            "zapret" -> "zapret_channel"
            "easyvpn" -> "EasyVpnProxy"
            else -> "toinet_log_channel"
        }

        val notifyId = when (providerName) {
            "tgws" -> 1480
            "operaproxy" -> 1888
            "zapret" -> 1081
            "easyvpn" -> 1791
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
            "easyvpn" -> "EasyVPN"
            "tgws" -> "TGWS"
            else -> "ToInet: $providerName"
        }

        val builder = NotificationCompat.Builder(context, channelId)
            .setContentTitle(title)
            .setContentText(cleanMsg)
            .setSmallIcon(R.drawable.ic_stat_tor)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setGroup("toinet_services")
            .setOnlyAlertOnce(true)

        manager.notify(notifyId, builder.build())
    }
}

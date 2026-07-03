package ru.toinet.android.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import ru.toinet.android.OrbotActivity
import ru.toinet.android.R

object NotificationLogger {
    private const val NOTIFY_ID = 1
    private const val CHANNEL_ID = "orbot_channel_1"
    
    fun log(context: Context, providerName: String, message: String) {
        if (Prefs.notificationLogProvider != providerName) return

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Orbot",
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }

        val intent = Intent(context, OrbotActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Orbot: $providerName")
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_stat_tor)
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true)

        manager.notify(NOTIFY_ID, builder.build())
    }
}

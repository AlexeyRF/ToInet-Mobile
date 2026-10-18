package ru.toinet.android.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import org.torproject.jni.TorService
import ru.toinet.android.R
import ru.toinet.android.ToInetActivity
import ru.toinet.android.receivers.EasyVpnActionReceiver
import ru.toinet.android.service.circumvention.Transport
import java.net.InetSocketAddress
import java.net.Socket

object NetworkBlackoutMonitor {
    private const val TAG = "NetworkBlackoutMonitor"
    private const val CHANNEL_ID = "toinet_network_alerts"
    private const val NOTIFICATION_ID = 8842

    private var isMonitoring = false
    private var job: Job? = null

    @Volatile private var torStatus: String = TorService.STATUS_OFF
    @Volatile private var torProgress: Int = 0
    @Volatile private var sawPluggableTransportConnected: Boolean = false
    @Volatile private var ptConnectedTimestamp: Long = 0L

    private var lastAlertTime: Long = 0L
    private const val ALERT_COOLDOWN_MS = 5 * 60 * 1000L // 5 minutes

    @JvmStatic
    fun onTorLog(logMessage: String?) {
        if (logMessage == null) return
        val lower = logMessage.lowercase()
        if (lower.contains("pluggable transport") || lower.contains("conn_pt")) {
            sawPluggableTransportConnected = true
            ptConnectedTimestamp = System.currentTimeMillis()
            Log.d(TAG, "Tor saw pluggable transport event: $logMessage")
        }
    }

    @JvmStatic
    fun onTorStatus(status: String?) {
        torStatus = status ?: TorService.STATUS_OFF
        if (torStatus == TorService.STATUS_ON) {
            sawPluggableTransportConnected = false
            ptConnectedTimestamp = 0L
        }
    }

    @JvmStatic
    fun onTorProgress(progress: Int) {
        torProgress = progress
        if (progress >= 100) {
            sawPluggableTransportConnected = false
            ptConnectedTimestamp = 0L
        }
    }

    @JvmStatic
    fun start(context: Context) {
        if (isMonitoring) return
        isMonitoring = true

        val appContext = context.applicationContext

        job = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                try {
                    delay(60_000L) // Ping cycle once every minute
                    checkNetwork(appContext)
                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Error in network blackout monitor cycle", e)
                }
            }
        }
    }

    private fun checkNetwork(context: Context) {
        // Skip check if already running EasyVPN
        if (Prefs.isGlobalVpnEnabled && Prefs.vpnProvider == "easyvpn") {
            return
        }

        // Check if device has active network connection
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val activeNetwork = cm.activeNetwork ?: return
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return

        // 1. Ping test: check non-whitelisted hosts in Russia (github.com / gitlab.com)
        val site1Ok = isHostReachable("github.com", 443, 4000)
        var blackoutDetected = false

        if (!site1Ok) {
            Log.d(TAG, "Ping to primary non-whitelisted site (github.com) failed. Checking secondary site (gitlab.com)...")
            val site2Ok = isHostReachable("gitlab.com", 443, 4000)
            if (!site2Ok) {
                Log.d(TAG, "Ping to secondary site (gitlab.com) also failed. Non-whitelisted hosts unreachable!")
                blackoutDetected = true
            }
        }

        if (!blackoutDetected) {
            return
        }

        // 2. Check Tor connection through bridge
        val isTorWithBridge = Prefs.torEnabled && (
                (Prefs.transport != Transport.NONE && Prefs.transport != Transport.BYEDPI) || Prefs.bridgesList.isNotEmpty()
        )

        val isTorBridgeStuck = isTorWithBridge &&
                torStatus != TorService.STATUS_ON &&
                torProgress < 100 &&
                sawPluggableTransportConnected &&
                (System.currentTimeMillis() - ptConnectedTimestamp >= 20_000L)

        if (isTorBridgeStuck) {
            Log.w(TAG, "Blackout detected: Non-whitelisted sites unreachable AND Tor stuck on Pluggable Transport!")
            sendAlertNotification(context)
        }
    }

    private fun isHostReachable(host: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        } catch (e: Exception) {
            Log.d(TAG, "Failed to connect to $host:$port: ${e.message}")
            false
        }
    }

    private fun sendAlertNotification(context: Context) {
        try {
            val now = System.currentTimeMillis()
            if (now - lastAlertTime < ALERT_COOLDOWN_MS) {
                Log.d(TAG, "Alert cooldown active, skipping notification.")
                return
            }
            lastAlertTime = now

            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Оповещения связи",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Предупреждения об ограничении мобильной связи"
                    enableLights(true)
                    enableVibration(true)
                }
                nm.createNotificationChannel(channel)
            }

            val contentIntent = PendingIntent.getActivity(
                context,
                0,
                Intent(context, ToInetActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val connectIntent = PendingIntent.getBroadcast(
                context,
                0,
                Intent(context, EasyVpnActionReceiver::class.java).apply {
                    action = EasyVpnActionReceiver.ACTION_CONNECT_EASYVPN
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_generic_info)
                .setContentTitle("ToInet")
                .setContentText("Похоже в вашем регионе ограничена мобильная связь, включить обход через EasyVPN")
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText("Похоже в вашем регионе ограничена мобильная связь, включить обход через EasyVPN")
                )
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(contentIntent)
                .addAction(R.drawable.ic_power, "Подключиться", connectIntent)
                .build()

            nm.notify(NOTIFICATION_ID, notification)
            Log.i(TAG, "Sent blackout alert notification with Connect button.")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to send blackout alert notification", e)
        }
    }
}

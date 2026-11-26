package ru.toinet.android.vpn.utility

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.torproject.jni.TorService
import ru.toinet.android.R
import ru.toinet.android.ToInetActivity
import ru.toinet.android.service.ToInetConstants
import ru.toinet.android.service.ToInetService
import ru.toinet.android.util.Prefs
import ru.toinet.android.util.isServiceRunningInPackage
import ru.toinet.android.util.sendIntentToService
import ru.toinet.android.vpn.data.STOP_ACTION

private const val NOTIF_TAG = "ToInetNotif"

const val SHARED_FOREGROUND_ID = 1
const val PRIMARY_CHANNEL_ID = "toinet_service_primary"
const val SILENT_CHANNEL_ID = "toinet_service_silent"
const val NOTIFICATION_GROUP_KEY = "toinet_services"
const val ACTION_REFRESH_FOREGROUND_NOTIFICATION = "ru.toinet.android.ACTION_REFRESH_FOREGROUND_NOTIFICATION"

private val LEGACY_CHANNELS_TO_DELETE = listOf(
    "ToInetVpn_silent", "ToInetVpn_mini", "ToInetVpn_low",
    "ByeDPI Proxy_silent", "ByeDPI Proxy_mini", "ByeDPI Proxy_low",
    "ByeDPIInstance_silent", "ByeDPIInstance_mini", "ByeDPIInstance_low",
    "EasyVpnProxy_silent", "EasyVpnProxy_mini", "EasyVpnProxy_low",
    "operaproxy_channel_silent", "operaproxy_channel_mini", "operaproxy_channel_low",
    "svpn_channel_silent", "svpn_channel_mini", "svpn_channel_low",
    "tgws_channel_silent", "tgws_channel_mini", "tgws_channel_low",
    "toinet_channel_1", "toinet_channel_1_min", "toinet_channel_mini", "toinet_channel_silent",
)

private val SERVICE_PRIORITY_ORDER = listOf(
    "vpn",
    "byedpi",
    "easyvpn",
    "operaproxy",
    "svpn",
    "tor",
    "tgws",
)

private object ForegroundNotificationCoordinator {
    val lock = Any()
    val activeKeys = linkedSetOf<String>()
    val providers = mutableMapOf<String, () -> Notification>()
    var primaryOwnerKey: String? = null
    var primaryNotification: Notification? = null
    @Volatile
    var channelsInitialized = false
    @Volatile
    var refreshReceiverRegistered = false

    fun pickOwnerKeyLocked(): String? {
        if (Prefs.isGlobalVpnEnabled && activeKeys.contains("vpn")) {
            return "vpn"
        }
        for (key in SERVICE_PRIORITY_ORDER) {
            if (activeKeys.contains(key) && Prefs.isPrimaryNotificationService(key)) {
                return key
            }
        }
        for (key in SERVICE_PRIORITY_ORDER) {
            if (activeKeys.contains(key)) {
                return key
            }
        }
        return activeKeys.firstOrNull()
    }
}

fun ensureSharedNotificationChannels(context: Context) {
    val manager = context.getSystemService(NotificationManager::class.java) ?: return
    val appName = context.getString(R.string.app_name)
    val channelDescription = context.getString(R.string.app_description)

    val primaryChannel = NotificationChannel(
        PRIMARY_CHANNEL_ID,
        appName,
        NotificationManager.IMPORTANCE_LOW
    ).apply {
        description = channelDescription
        enableLights(false)
        enableVibration(false)
        setShowBadge(false)
        lockscreenVisibility = Notification.VISIBILITY_SECRET
    }

    val silentChannel = NotificationChannel(
        SILENT_CHANNEL_ID,
        appName,
        NotificationManager.IMPORTANCE_MIN
    ).apply {
        description = channelDescription
        enableLights(false)
        enableVibration(false)
        setShowBadge(false)
        lockscreenVisibility = Notification.VISIBILITY_SECRET
    }

    manager.createNotificationChannel(primaryChannel)
    manager.createNotificationChannel(silentChannel)

    if (!ForegroundNotificationCoordinator.channelsInitialized) {
        ForegroundNotificationCoordinator.channelsInitialized = true
        for (legacyId in LEGACY_CHANNELS_TO_DELETE) {
            try {
                manager.deleteNotificationChannel(legacyId)
            } catch (_: Exception) {}
        }
    }
}

fun registerNotificationChannel(context: Context, @Suppress("UNUSED_PARAMETER") id: String, @Suppress("UNUSED_PARAMETER") channelName: String) {
    ensureSharedNotificationChannels(context)
}

fun registerNotificationChannel(context: Context, @Suppress("UNUSED_PARAMETER") id: String, @Suppress("UNUSED_PARAMETER") @StringRes name: Int) {
    ensureSharedNotificationChannels(context)
}

fun resolveNotificationChannelAndPriority(serviceKey: String, @Suppress("UNUSED_PARAMETER") baseChannelId: String): Pair<String, Int> {
    if (!Prefs.shouldServiceShowNotification(serviceKey)) {
        return Pair(SILENT_CHANNEL_ID, NotificationCompat.PRIORITY_MIN)
    }
    return Pair(PRIMARY_CHANNEL_ID, NotificationCompat.PRIORITY_LOW)
}

fun buildSilentFallbackNotification(context: Context): Notification {
    ensureSharedNotificationChannels(context)
    return NotificationCompat.Builder(context, SILENT_CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_stat_tor)
        .setContentTitle("ToInet")
        .setPriority(NotificationCompat.PRIORITY_MIN)
        .setSilent(true)
        .setOnlyAlertOnce(true)
        .setOngoing(true)
        .setGroup(NOTIFICATION_GROUP_KEY)
        .setContentIntent(
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, ToInetActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
        )
        .build()
}

private fun ensureRefreshReceiverRegistered(context: Context) {
    if (ForegroundNotificationCoordinator.refreshReceiverRegistered) return
    val appContext = context.applicationContext ?: context
    synchronized(ForegroundNotificationCoordinator.lock) {
        if (ForegroundNotificationCoordinator.refreshReceiverRegistered) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                refreshActiveForegroundNotification(ctx)
            }
        }
        try {
            ContextCompat.registerReceiver(
                appContext,
                receiver,
                IntentFilter(ACTION_REFRESH_FOREGROUND_NOTIFICATION),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            ForegroundNotificationCoordinator.refreshReceiverRegistered = true
        } catch (e: Exception) {
            Log.w(NOTIF_TAG, "Failed registering foreground refresh receiver", e)
        }
    }
}

private fun startForegroundCompat(service: Service, notification: Notification) {
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            service.startForeground(
                SHARED_FOREGROUND_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            service.startForeground(SHARED_FOREGROUND_ID, notification)
        }
    } catch (e: Exception) {
        Log.w(NOTIF_TAG, "Failed specialUse startForeground in ${service.javaClass.simpleName}, fallback", e)
        try {
            service.startForeground(SHARED_FOREGROUND_ID, notification)
        } catch (e2: Exception) {
            Log.e(NOTIF_TAG, "Failed fallback startForeground in ${service.javaClass.simpleName}", e2)
        }
    }
}

/**
 * Starts a main-process service in foreground on the single shared SHARED_FOREGROUND_ID.
 * Secondary services adopt the active primary notification rather than creating extra cards
 * or stripping the primary notification's actions.
 */
fun startSharedForeground(service: Service, serviceKey: String, provider: () -> Notification) {
    ensureSharedNotificationChannels(service)
    ensureRefreshReceiverRegistered(service)
    val notificationToPost = synchronized(ForegroundNotificationCoordinator.lock) {
        ForegroundNotificationCoordinator.activeKeys.add(serviceKey)
        ForegroundNotificationCoordinator.providers[serviceKey] = provider
        val ownerKey = ForegroundNotificationCoordinator.pickOwnerKeyLocked()
        if (ownerKey == serviceKey || ForegroundNotificationCoordinator.primaryNotification == null) {
            val built = provider()
            ForegroundNotificationCoordinator.primaryOwnerKey = ownerKey ?: serviceKey
            ForegroundNotificationCoordinator.primaryNotification = built
            built
        } else {
            ForegroundNotificationCoordinator.primaryNotification!!
        }
    }
    startForegroundCompat(service, notificationToPost)
}

/**
 * Updates the shared foreground notification only if [serviceKey] is the current primary owner.
 */
fun updateSharedForegroundNotification(context: Context, serviceKey: String, provider: (() -> Notification)? = null) {
    ensureSharedNotificationChannels(context)
    val notificationToPost = synchronized(ForegroundNotificationCoordinator.lock) {
        if (provider != null) {
            ForegroundNotificationCoordinator.providers[serviceKey] = provider
        }
        if (!ForegroundNotificationCoordinator.activeKeys.contains(serviceKey)) {
            return
        }
        val ownerKey = ForegroundNotificationCoordinator.pickOwnerKeyLocked()
        if (ownerKey != serviceKey) {
            return
        }
        val effectiveProvider = provider ?: ForegroundNotificationCoordinator.providers[serviceKey] ?: return
        val built = effectiveProvider()
        ForegroundNotificationCoordinator.primaryOwnerKey = serviceKey
        ForegroundNotificationCoordinator.primaryNotification = built
        built
    }
    try {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm?.notify(SHARED_FOREGROUND_ID, notificationToPost)
    } catch (_: Exception) {}
}

/**
 * Re-evaluates primary ownership and repaints SHARED_FOREGROUND_ID if a main-process FGS is active.
 */
fun refreshActiveForegroundNotification(context: Context) {
    ensureSharedNotificationChannels(context)
    val notificationToPost = synchronized(ForegroundNotificationCoordinator.lock) {
        val ownerKey = ForegroundNotificationCoordinator.pickOwnerKeyLocked() ?: return
        val provider = ForegroundNotificationCoordinator.providers[ownerKey] ?: return
        val built = provider()
        ForegroundNotificationCoordinator.primaryOwnerKey = ownerKey
        ForegroundNotificationCoordinator.primaryNotification = built
        built
    }
    try {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm?.notify(SHARED_FOREGROUND_ID, notificationToPost)
    } catch (_: Exception) {}
}

/**
 * Stops a service's participation in the shared foreground notification.
 * Detaches without removing SHARED_FOREGROUND_ID whenever another FGS (main-process or active :tor) remains.
 */
fun stopSharedForeground(service: Service, serviceKey: String, wasForeground: Boolean = true) {
    var removed = false
    var hasNextMainOwner = false
    var nextNotif: Notification? = null
    synchronized(ForegroundNotificationCoordinator.lock) {
        removed = ForegroundNotificationCoordinator.activeKeys.remove(serviceKey)
        ForegroundNotificationCoordinator.providers.remove(serviceKey)
        if (!wasForeground && !removed) {
            return
        }
        val nextOwner = ForegroundNotificationCoordinator.pickOwnerKeyLocked()
        if (nextOwner != null) {
            hasNextMainOwner = true
            if (ForegroundNotificationCoordinator.primaryOwnerKey == serviceKey ||
                ForegroundNotificationCoordinator.primaryNotification == null
            ) {
                val built = ForegroundNotificationCoordinator.providers[nextOwner]?.invoke()
                ForegroundNotificationCoordinator.primaryOwnerKey = nextOwner
                ForegroundNotificationCoordinator.primaryNotification = built
                nextNotif = built
            }
        } else {
            ForegroundNotificationCoordinator.primaryOwnerKey = null
            ForegroundNotificationCoordinator.primaryNotification = null
        }
    }

    if (hasNextMainOwner) {
        if (wasForeground) {
            ServiceCompat.stopForeground(service, ServiceCompat.STOP_FOREGROUND_DETACH)
        }
        nextNotif?.let { notif ->
            try {
                val nm = service.getSystemService(NotificationManager::class.java)
                nm?.notify(SHARED_FOREGROUND_ID, notif)
            } catch (_: Exception) {}
        }
        return
    }

    val torStillActive = Prefs.torRuntimeStatus != TorService.STATUS_OFF &&
        service.isServiceRunningInPackage(ToInetService::class.java)
    if (torStillActive) {
        if (wasForeground) {
            ServiceCompat.stopForeground(service, ServiceCompat.STOP_FOREGROUND_DETACH)
        }
        service.sendIntentToService(
            Intent(service, ToInetService::class.java).apply {
                action = ToInetConstants.CMD_ACTIVE
                putExtra("is_foreground", false)
            },
            isForeground = false
        )
    } else {
        if (wasForeground) {
            ServiceCompat.stopForeground(service, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
        try {
            val nm = service.getSystemService(NotificationManager::class.java)
            nm?.cancel(SHARED_FOREGROUND_ID)
        } catch (_: Exception) {}
    }
}

fun createNotificationStopAllPendingIntent(context: Context): PendingIntent {
    val intent = Intent(context, ru.toinet.android.receivers.NotificationActionReceiver::class.java).apply {
        action = ru.toinet.android.receivers.NotificationActionReceiver.ACTION_STOP_ALL
    }
    return PendingIntent.getBroadcast(
        context,
        0,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

fun createConnectionNotification(
    context: Context,
    channelId: String,
    @Suppress("UNUSED_PARAMETER") @StringRes title: Int,
    @Suppress("UNUSED_PARAMETER") @StringRes content: Int,
    @Suppress("UNUSED_PARAMETER") service: Class<*>,
    serviceKey: String = "byedpi",
): Notification {
    ensureSharedNotificationChannels(context)

    val (actualId, priority) = resolveNotificationChannelAndPriority(serviceKey, channelId)

    val builder = NotificationCompat.Builder(context, actualId)
        .setSmallIcon(R.drawable.ic_stat_tor)
        .setSilent(true)
        .setOnlyAlertOnce(true)
        .setOngoing(true)
        .setGroup(NOTIFICATION_GROUP_KEY)
        .setPriority(priority)
        .setContentIntent(
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, ToInetActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        )
        .setContentTitle("ToInet")
        .setContentText(null)
        .setSubText(null)

    builder.addAction(
        0,
        context.getString(R.string.disable),
        createNotificationStopAllPendingIntent(context),
    )

    return builder.build()
}

fun createConnectionNotificationString(
    context: Context,
    channelId: String,
    @Suppress("UNUSED_PARAMETER") title: String,
    @Suppress("UNUSED_PARAMETER") content: String? = null,
    @Suppress("UNUSED_PARAMETER") service: Class<*>,
    switchServerIntent: PendingIntent? = null,
    switchTorIntent: PendingIntent? = null,
    serviceKey: String = "vpn",
): Notification {
    ensureSharedNotificationChannels(context)

    val (actualId, priority) = resolveNotificationChannelAndPriority(serviceKey, channelId)

    val builder = NotificationCompat.Builder(context, actualId)
        .setSmallIcon(R.drawable.ic_stat_tor)
        .setSilent(true)
        .setOnlyAlertOnce(true)
        .setOngoing(true)
        .setGroup(NOTIFICATION_GROUP_KEY)
        .setPriority(priority)
        .setContentIntent(
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, ToInetActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        )
        .setContentTitle("ToInet")
        .setContentText(null)
        .setSubText(null)

    if (switchServerIntent != null) {
        builder.addAction(
            0,
            "Сменить EasyVPN",
            switchServerIntent
        )
    }

    if (switchTorIntent != null) {
        builder.addAction(
            0,
            "Сменить ноду Tor",
            switchTorIntent
        )
    }

    builder.addAction(
        0,
        context.getString(R.string.disable),
        createNotificationStopAllPendingIntent(context),
    )

    return builder.build()
}

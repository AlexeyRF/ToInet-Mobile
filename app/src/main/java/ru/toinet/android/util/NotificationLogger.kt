package ru.toinet.android.util

import android.content.Context
import android.content.Intent
import ru.toinet.android.service.ToInetConstants

object NotificationLogger {
    @Volatile
    var lastActiveVpnLog: String? = null

    private val logHistory = java.util.concurrent.ConcurrentLinkedQueue<String>()
    private const val MAX_LOG_LINES = 1000

    @Volatile
    private var lastBroadcastTime = 0L

    @JvmStatic
    fun addLog(msg: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val line = "[$time] $msg"
        logHistory.add(line)
        while (logHistory.size > MAX_LOG_LINES) {
            logHistory.poll()
        }
    }

    @JvmStatic
    fun getAllLogs(): String {
        return logHistory.joinToString("\n")
    }

    fun log(context: Context, providerName: String, message: String) {
        val cleanMsg = message.trim()
        if (cleanMsg.isEmpty()) return

        addLog("[$providerName] $cleanMsg")

        val now = System.currentTimeMillis()

        // Send log broadcast to update logBottomSheet and UI (throttled for noisy streams, never dropping lifecycle/errors)
        val isImportant = cleanMsg.contains("Ошибка", ignoreCase = true) ||
            cleanMsg.contains("failed", ignoreCase = true) ||
            cleanMsg.contains("заблокирован", ignoreCase = true) ||
            cleanMsg.contains("Не удалось", ignoreCase = true) ||
            cleanMsg.contains("Нет активн", ignoreCase = true) ||
            cleanMsg.contains("Подключение", ignoreCase = true) ||
            cleanMsg.contains("Запуск", ignoreCase = true)
        if (isImportant || now - lastBroadcastTime > 80L) {
            lastBroadcastTime = now
            try {
                val logIntent = Intent(ToInetConstants.LOCAL_ACTION_LOG).apply {
                    putExtra(ToInetConstants.LOCAL_EXTRA_LOG, "[$providerName] $cleanMsg")
                    setPackage(context.packageName)
                }
                context.sendBroadcast(logIntent)
            } catch (_: Exception) {}
        }

        val isVpn = Prefs.isGlobalVpnEnabled
        val vpnProvider = Prefs.vpnProvider
        val isCurrentVpn = isVpn && (
            providerName == vpnProvider ||
            (providerName in listOf("rehab", "custom") && vpnProvider in listOf("rehab", "custom"))
        )

        if (isCurrentVpn) {
            lastActiveVpnLog = cleanMsg
        }
    }
}

package ru.toinet.android.util

import android.content.Context
import androidx.appcompat.app.AlertDialog

object ProxyShareSecurityHelper {

    fun checkAndConfirmProxyShare(
        context: Context,
        onConfirmed: () -> Unit,
        onCancelled: (() -> Unit)? = null
    ) {
        if (Prefs.proxyShareWarningDismissed) {
            onConfirmed()
            return
        }

        AlertDialog.Builder(context)
            .setTitle("Предупреждение безопасности")
            .setMessage("Включение раздачи прокси открывает сетевые порты на адресе 0.0.0.0. Любое устройство в вашей локальной сети (Wi-Fi) сможет подключиться к вашему прокси без авторизации, что может привести к перехвату трафика или несанкционированному доступу к сети через ваше устройство.")
            .setPositiveButton("Включить") { _, _ ->
                onConfirmed()
            }
            .setNeutralButton("Больше не предупреждать") { _, _ ->
                Prefs.proxyShareWarningDismissed = true
                onConfirmed()
            }
            .setNegativeButton("Отмена") { _, _ ->
                onCancelled?.invoke()
            }
            .setOnCancelListener {
                onCancelled?.invoke()
            }
            .show()
    }
}

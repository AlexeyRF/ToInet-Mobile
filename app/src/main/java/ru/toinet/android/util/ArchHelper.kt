package ru.toinet.android.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.appcompat.app.AlertDialog

object ArchHelper {
    val primaryAbi: String
        get() = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"

    val isX86_64: Boolean
        get() = primaryAbi.contains("x86_64", ignoreCase = true)
                || Build.SUPPORTED_ABIS.any { it.equals("x86_64", ignoreCase = true) }

    val isX86_32: Boolean
        get() = !isX86_64 && (primaryAbi.equals("x86", ignoreCase = true) || Build.SUPPORTED_ABIS.any { it.equals("x86", ignoreCase = true) })

    val isX86Family: Boolean
        get() = isX86_64 || isX86_32 || primaryAbi.contains("x86", ignoreCase = true)

    val isOperaSupported: Boolean
        get() = !isX86Family

    var warningShown: Boolean = false

    fun checkAndShowWarning(context: Context) {
        if (warningShown) return
        if (!isX86Family) return
        warningShown = true

        val builder = AlertDialog.Builder(context)
            .setCancelable(false)

        if (isX86_64) {
            builder.setTitle("Архитектура x86-64")
            builder.setMessage(
                "Данная архитектура не поддерживает 100% функций, неподдерживаемые функции убраны из списка.\n\n" +
                "Похоже вы хотите запустить программу через эмулятор или на десктопе, вместо android приложения используйте ToInet для 64 битной Windows:\nhttps://github.com/AlexeyRF/ToInet"
            )
            builder.setPositiveButton("Скачать для Windows") { dialog, _ ->
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/AlexeyRF/ToInet"))
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                } catch (_: Exception) {}
                dialog.dismiss()
            }
            builder.setNegativeButton("Продолжить") { dialog, _ ->
                dialog.dismiss()
            }
        } else {
            builder.setTitle("Архитектура x86")
            builder.setMessage(
                "Данная архитектура не поддерживает 100% функций, неподдерживаемые функции убраны из списка."
            )
            builder.setPositiveButton("Понятно") { dialog, _ ->
                dialog.dismiss()
            }
        }

        builder.show()
    }
}

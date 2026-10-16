package ru.toinet.android.ui.connect

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import ru.toinet.android.R
import ru.toinet.android.ui.ToInetBottomSheetDialogFragment
import ru.toinet.android.ui.ToInetMenuAction
import ru.toinet.android.util.ArchHelper
import ru.toinet.android.util.Prefs

class AppSettingsBottomSheet : ToInetBottomSheetDialogFragment() {

    override fun getHeightRatio(): Float = -1f

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.app_settings_bottom_sheet, container, false)
        setupMenu(view)
        return view
    }

    private fun setupMenu(view: View) {
        val actions = arrayListOf(
            ToInetMenuAction(
                0,
                R.drawable.ic_settings_gear,
                statusString = "Тема оформления: ${getThemeString()}"
            ) {
                showThemeSettings { setupMenu(view) }
            },
            ToInetMenuAction(
                0,
                R.drawable.ic_settings_gear,
                statusString = "Цвета Material You: ${if (Prefs.useDynamicColors) "Вкл" else "Выкл"}"
            ) {
                Prefs.useDynamicColors = !Prefs.useDynamicColors
                requireActivity().recreate()
                dismiss()
            },
            ToInetMenuAction(
                0,
                R.drawable.ic_menu_onion,
                statusString = "Раздавать все прокси на локальную сеть: ${if (Prefs.openProxyOnAllInterfaces) "Вкл" else "Выкл"}\n(0.0.0.0 вместо 127.0.0.1)"
            ) {
                Prefs.openProxyOnAllInterfaces = !Prefs.openProxyOnAllInterfaces
                Toast.makeText(
                    requireContext(),
                    "Раздача прокси: ${if (Prefs.openProxyOnAllInterfaces) "Вкл" else "Выкл"}. Перезапустите прокси для применения.",
                    Toast.LENGTH_LONG
                ).show()
                setupMenu(view)
            },
            ToInetMenuAction(
                0,
                R.drawable.ic_log,
                statusString = "Уведомления (лог): ${getLogProviderString()}"
            ) {
                showNotificationSettings { setupMenu(view) }
            },
            ToInetMenuAction(
                0,
                R.drawable.ic_autorenew,
                statusString = "Сценарии перезапуска (Wi-Fi / Мобильная)\nРежим: ${
                    when (Prefs.networkSwitchMode) {
                        "disabled" -> "Отключено"
                        "restart_all" -> "Жесткий (Перезапуск)"
                        else -> "Мягкий (Умный)"
                    }
                }"
            ) {
                NetworkPresetsBottomSheet().show(parentFragmentManager, NetworkPresetsBottomSheet.TAG)
                dismiss()
            }
        )

        val lvSettings = view.findViewById<ListView>(R.id.lvSettings)
        lvSettings.adapter = ConnectMenuActionAdapter(requireContext(), actions)
    }

    private fun getThemeString(): String {
        return when (Prefs.themeMode) {
            AppCompatDelegate.MODE_NIGHT_NO -> "Светлая"
            AppCompatDelegate.MODE_NIGHT_YES -> "Тёмная"
            else -> "Системная"
        }
    }

    private fun getLogProviderString(): String {
        val providers = resources.getStringArray(R.array.log_providers)
        val providerValues = resources.getStringArray(R.array.log_providers_values)
        val currentProvider = Prefs.notificationLogProvider
        val checkedItem = providerValues.indexOf(currentProvider).takeIf { it >= 0 } ?: 0
        return if (checkedItem in providers.indices) providers[checkedItem] else "ToInet"
    }

    private fun showThemeSettings(onUpdated: () -> Unit) {
        val themes = arrayOf("Системная", "Светлая", "Тёмная")
        val themeValues = arrayOf(
            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
            AppCompatDelegate.MODE_NIGHT_NO,
            AppCompatDelegate.MODE_NIGHT_YES
        )
        val currentTheme = Prefs.themeMode
        val checkedItem = themeValues.indexOf(currentTheme).takeIf { it >= 0 } ?: 0

        AlertDialog.Builder(requireContext())
            .setTitle("Тема оформления")
            .setSingleChoiceItems(themes, checkedItem) { dialog, which ->
                Prefs.themeMode = themeValues[which]
                AppCompatDelegate.setDefaultNightMode(themeValues[which])
                onUpdated()
                dialog.dismiss()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showNotificationSettings(onUpdated: () -> Unit) {
        val allProviders = resources.getStringArray(R.array.log_providers).toMutableList()
        val allValues = resources.getStringArray(R.array.log_providers_values).toMutableList()

        if (!ArchHelper.isOperaSupported) {
            val operaIdx = allValues.indexOf("operaproxy")
            if (operaIdx != -1) {
                allProviders.removeAt(operaIdx)
                allValues.removeAt(operaIdx)
            }
            if (Prefs.notificationLogProvider == "operaproxy") {
                Prefs.notificationLogProvider = "tor"
            }
        }

        val currentProvider = Prefs.notificationLogProvider
        val checkedItem = allValues.indexOf(currentProvider).takeIf { it >= 0 } ?: 0

        AlertDialog.Builder(requireContext())
            .setTitle("Провайдер логов в уведомлении")
            .setSingleChoiceItems(allProviders.toTypedArray(), checkedItem) { dialog, which ->
                Prefs.notificationLogProvider = allValues[which]
                onUpdated()
                dialog.dismiss()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    companion object {
        const val TAG = "AppSettingsBottomSheet"
    }
}

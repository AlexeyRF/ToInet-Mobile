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
import ru.toinet.android.ToInetActivity
import ru.toinet.android.service.ToInetConstants
import ru.toinet.android.ui.ToInetBottomSheetDialogFragment
import ru.toinet.android.ui.ToInetMenuAction
import ru.toinet.android.util.ArchHelper
import ru.toinet.android.util.Prefs
import ru.toinet.android.util.sendIntentToService

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
                statusString = if (Prefs.openProxyOnAllInterfaces) {
                    "Раздавать все прокси на локальную сеть: Вкл\nIP для подключения: ${ru.toinet.android.util.NetworkUtils.getLocalIpAddress()}"
                } else {
                    "Раздавать все прокси на локальную сеть: Выкл\n(только для этого устройства, 127.0.0.1)"
                }
            ) {
                if (!Prefs.openProxyOnAllInterfaces) {
                    ru.toinet.android.util.ProxyShareSecurityHelper.checkAndConfirmProxyShare(
                        requireContext(),
                        onConfirmed = {
                            Prefs.openProxyOnAllInterfaces = true
                            val ipInfo = " (IP: ${ru.toinet.android.util.NetworkUtils.getLocalIpAddress()})"
                            Toast.makeText(
                                requireContext(),
                                "Раздача прокси: Вкл$ipInfo. Перезапустите прокси для применения.",
                                Toast.LENGTH_LONG
                            ).show()
                            setupMenu(view)
                        }
                    )
                } else {
                    Prefs.openProxyOnAllInterfaces = false
                    Toast.makeText(
                        requireContext(),
                        "Раздача прокси: Выкл. Перезапустите прокси для применения.",
                        Toast.LENGTH_LONG
                    ).show()
                    setupMenu(view)
                }
            },
            ToInetMenuAction(
                0,
                R.drawable.ic_server,
                statusString = "Пропуск локальной сети (LAN Bypass): ${if (Prefs.vpnBypassLan) "Вкл" else "Выкл"}\n(192.168.*, 10.*, 172.16.* в обход VPN)"
            ) {
                Prefs.vpnBypassLan = !Prefs.vpnBypassLan
                Toast.makeText(
                    requireContext(),
                    "Пропуск локальной сети: ${if (Prefs.vpnBypassLan) "Вкл" else "Выкл"}. Перезапустите VPN для применения.",
                    Toast.LENGTH_LONG
                ).show()
                setupMenu(view)
            },
            ToInetMenuAction(
                0,
                R.drawable.ic_log,
                statusString = "Уведомления: ${getLogProviderString()}"
            ) {
                showNotificationSettings { setupMenu(view) }
            },
            ToInetMenuAction(
                0,
                R.drawable.ic_log,
                statusString = "Общий лог работы"
            ) {
                showGeneralLog()
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

    private fun showGeneralLog() {
        val act = activity as? ToInetActivity
        if (act != null) {
            act.showLog()
        } else {
            ru.toinet.android.ui.more.LogBottomSheet().show(parentFragmentManager, "LogBottomSheet")
        }
    }

    private fun showNotificationSettings(onUpdated: () -> Unit) {
        val allProviders = resources.getStringArray(R.array.log_providers)
        val allValues = resources.getStringArray(R.array.log_providers_values)

        val currentProvider = Prefs.notificationLogProvider
        var checkedItem = allValues.indexOf(currentProvider)
        if (checkedItem < 0) {
            checkedItem = 0
            Prefs.notificationLogProvider = allValues[0]
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Провайдер уведомлений")
            .setSingleChoiceItems(allProviders, checkedItem) { dialog, which ->
                Prefs.notificationLogProvider = allValues[which]
                requireContext().sendIntentToService(ToInetConstants.CMD_ACTIVE)
                ru.toinet.android.vpn.services.AppVpnService.updateLogNotification(requireContext(), "")
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

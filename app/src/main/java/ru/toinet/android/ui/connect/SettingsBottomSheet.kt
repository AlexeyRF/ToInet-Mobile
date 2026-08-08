package ru.toinet.android.ui.connect

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ListView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import ru.toinet.android.R
import ru.toinet.android.ui.OrbotMenuAction
import ru.toinet.android.util.Prefs
import ru.toinet.android.tgws.UniversalTgProxy
import android.content.ClipboardManager
import android.content.ClipData
import android.content.Context
import android.widget.Toast

class SettingsBottomSheet : BottomSheetDialogFragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.settings_bottom_sheet, container, false)
        val lvSettings = view.findViewById<ListView>(R.id.lvSettings)

        val listItems = arrayListOf(
            OrbotMenuAction(
                0,
                R.drawable.ic_settings_gear,
                statusString = if (Prefs.torEnabled) "Конфигурация Tor (мосты и прокси)" else "Конфигурация Tor: ВЫКЛ"
            ) {
                TorConfigBottomSheet().show(
                    requireActivity().supportFragmentManager,
                    TorConfigBottomSheet.TAG
                )
                dismiss()
            },
            OrbotMenuAction(
                0,
                R.drawable.ic_server,
                statusString = "ByeDPI: ${if (Prefs.byedpiEnabled) "ВКЛ" else "ВЫКЛ"}\n(прокси)"
            ) {
                ByeDpiBottomSheet().show(
                    requireActivity().supportFragmentManager,
                    ByeDpiBottomSheet.TAG
                )
                dismiss()
            },
            OrbotMenuAction(
                0,
                R.drawable.ic_send_airplane,
                statusString = "TGWS: ${if (Prefs.tgwsEnabled) "ВКЛ" else "ВЫКЛ"}\n(порт, маппинги)"
            ) {
                TgwsBottomSheet().show(
                    requireActivity().supportFragmentManager,
                    TgwsBottomSheet.TAG
                )
                dismiss()
            },
            OrbotMenuAction(
                0,
                R.drawable.ic_add_plus,
                statusString = "SocksRehabilitator: ${if (Prefs.rehabilitatorEnabled) "Вкл" else "Выкл"}\n(SOCKS5 через ByeDPI)"
            ) {
                RehabilitatorBottomSheet().show(
                    requireActivity().supportFragmentManager,
                    RehabilitatorBottomSheet.TAG
                )
                dismiss()
            },
            OrbotMenuAction(
                0,
                R.drawable.ic_flight_off,
                statusString = "TurnProxy: ${if (Prefs.turnProxyEnabled) "Вкл" else "Выкл"}\n(Обход по TURN)"
            ) {
                TurnProxyBottomSheet().show(
                    requireActivity().supportFragmentManager,
                    TurnProxyBottomSheet.TAG
                )
                dismiss()
            },
            OrbotMenuAction(
                0,
                R.drawable.ic_settings_gear,
                statusString = "FakeVPN: ${if (Prefs.fakeVpnFakeTlsEnabled) "FakeTLS Вкл" else "Обычный"}"
            ) {
                FakeVpnBottomSheet().show(
                    requireActivity().supportFragmentManager,
                    FakeVpnBottomSheet.TAG
                )
                dismiss()
            },
            OrbotMenuAction(
                0,
                R.drawable.ic_settings_gear,
                statusString = "Opera Proxy: ${if (Prefs.operaProxyEnabled) "Вкл" else "Выкл"}"
            ) {
                OperaProxyBottomSheet().show(
                    requireActivity().supportFragmentManager,
                    OperaProxyBottomSheet.TAG
                )
                dismiss()
            },
            OrbotMenuAction(
                0,
                R.drawable.ic_settings_gear,
                statusString = "Тема: ${getThemeString()}"
            ) {
                showThemeSettings()
                dismiss()
            },
            OrbotMenuAction(
                0,
                R.drawable.ic_settings_gear,
                statusString = "Цвета Material You: ${if (Prefs.useDynamicColors) "Вкл" else "Выкл"}"
            ) {
                Prefs.useDynamicColors = !Prefs.useDynamicColors
                requireActivity().recreate()
                dismiss()
            },
            /*
            OrbotMenuAction(
                0,
                R.drawable.ic_settings_gear,
                statusString = "Universal TG Proxy: ${if (Prefs.universalTgProxyEnabled) "Вкл" else "Выкл"}"
            ) {
                Prefs.universalTgProxyEnabled = !Prefs.universalTgProxyEnabled
                if (Prefs.universalTgProxyEnabled) {
                    // UniversalTgProxy.start()
                } else {
                    // UniversalTgProxy.stop()
                }
                dismiss()
            },
            OrbotMenuAction(
                0,
                R.drawable.ic_settings_gear,
                statusString = "Universal TG Proxy провайдер: ${Prefs.universalTgProxyProvider}"
            ) {
                showUniversalTgProxyProviderSettings()
                dismiss()
            },
            */
            OrbotMenuAction(
                0,
                R.drawable.ic_settings_gear,
                statusString = "Добавить прокси в Telegram"
            ) {
                val ctx = requireContext()
                val proxies = arrayOf(
                    "TOR (SOCKS5)",
                    //"TGWS (MTPROTO)",
                    "SR (SOCKS5)",
                    "OP (SOCKS5)",
                    "TGWS (SOCKS5)"
                    //"Gatik (SOCKS5)"
                )
                val proxyLinks = arrayOf(
                    "tg://socks?server=127.0.0.1&port=5242",
                    //"tg://proxy?server=127.0.0.1&port=1480&secret=${Prefs.tgwsSecret}",
                    "tg://socks?server=127.0.0.1&port=1788",
                    "tg://socks?server=127.0.0.1&port=1888",
                    "tg://socks?server=127.0.0.1&port=1480"
                    //"tg://socks?server=127.0.0.1&port=1777"
                )
                androidx.appcompat.app.AlertDialog.Builder(ctx)
                    .setTitle("Выберите прокси для добавления")
                    .setItems(proxies) { _, which ->
                        val link = proxyLinks[which]
                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(link))
                        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        try {
                            ctx.startActivity(intent)
                        } catch (e: Exception) {
                            // fallback to copy if Telegram not installed
                            val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("TG Proxy", link))
                            android.widget.Toast.makeText(ctx, "Telegram не найден. Ссылка скопирована.", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                    .show()
                dismiss()
            },
            OrbotMenuAction(
                0,
                R.drawable.ic_menu_onion, // proxy/share icon
                statusString = "Раздавать все прокси на локальную сеть: " + if (Prefs.openProxyOnAllInterfaces) "Вкл" else "Выкл"
            ) {
                Prefs.openProxyOnAllInterfaces = !Prefs.openProxyOnAllInterfaces
                android.widget.Toast.makeText(
                    requireContext(),
                    "Раздача прокси: ${if (Prefs.openProxyOnAllInterfaces) "Вкл" else "Выкл"}. Перезапустите прокси для применения.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                dismiss()
            },
            OrbotMenuAction(
                0,
                R.drawable.ic_settings_gear,
                statusString = "Настройки уведомлений"
            ) {
                showNotificationSettings()
                dismiss()
            },
            OrbotMenuAction(
                0,
                R.drawable.ic_settings_gear,
                statusString = "Сценарии сети (Wi-Fi / Мобильная)"
            ) {
                NetworkPresetsBottomSheet().show(parentFragmentManager, "presets")
                dismiss()
            }
        )
        lvSettings.adapter = ConnectMenuActionAdapter(requireContext(), listItems)
        return view
    }

    private fun getThemeString(): String {
        return when (Prefs.themeMode) {
            androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO -> "Светлая"
            androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES -> "Тёмная"
            else -> "Системная"
        }
    }

    private fun showThemeSettings() {
        val themes = arrayOf("Системная", "Светлая", "Тёмная")
        val themeValues = arrayOf(
            androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
            androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO,
            androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
        )
        val currentTheme = Prefs.themeMode
        val checkedItem = themeValues.indexOf(currentTheme).takeIf { it >= 0 } ?: 0
        
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Тема оформления")
            .setSingleChoiceItems(themes, checkedItem) { dialog, which ->
                Prefs.themeMode = themeValues[which]
                androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(themeValues[which])
                dialog.dismiss()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showNotificationSettings() {
        val providers = resources.getStringArray(R.array.log_providers)
        val providerValues = resources.getStringArray(R.array.log_providers_values)
        val currentProvider = Prefs.notificationLogProvider
        val checkedItem = providerValues.indexOf(currentProvider).takeIf { it >= 0 } ?: 0
        
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Провайдер логов в уведомлении")
            .setSingleChoiceItems(providers, checkedItem) { dialog, which ->
                Prefs.notificationLogProvider = providerValues[which]
                dialog.dismiss()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showUniversalTgProxyProviderSettings() {
        val providers = arrayOf("tor", "tgws", "byedpi", "turnproxy", "fakevpn", "operaproxy", "custom")
        val currentProvider = Prefs.universalTgProxyProvider
        val checkedItem = providers.indexOf(currentProvider).takeIf { it >= 0 } ?: 0

        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Провайдер для Universal TG Proxy")
            .setSingleChoiceItems(providers, checkedItem) { dialog, which ->
                Prefs.universalTgProxyProvider = providers[which]
                if (Prefs.universalTgProxyEnabled) {
                    ru.toinet.android.tgws.UniversalTgProxy.stop()
                    ru.toinet.android.tgws.UniversalTgProxy.start()
                }
                dialog.dismiss()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    companion object {
        const val TAG = "SettingsBottomSheet"
    }
}

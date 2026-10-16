package ru.toinet.android.ui.connect

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import ru.toinet.android.R
import ru.toinet.android.ui.ToInetBottomSheetDialogFragment
import ru.toinet.android.ui.ToInetMenuAction
import ru.toinet.android.util.ArchHelper
import ru.toinet.android.util.Prefs

class SettingsBottomSheet : ToInetBottomSheetDialogFragment() {

    override fun getHeightRatio(): Float = -1f

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.settings_bottom_sheet, container, false)
        val lvSettings = view.findViewById<ListView>(R.id.lvSettings)

        val listItems = arrayListOf(
            ToInetMenuAction(
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
            ToInetMenuAction(
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
            ToInetMenuAction(
                0,
                R.drawable.ic_server,
                statusString = "Zapret: ${if (Prefs.zapretEnabled) "ВКЛ" else "ВЫКЛ"}\n(прокси tpws)"
            ) {
                ZapretBottomSheet().show(
                    requireActivity().supportFragmentManager,
                    ZapretBottomSheet.TAG
                )
                dismiss()
            },
            ToInetMenuAction(
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
            ToInetMenuAction(
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
            /*
            ToInetMenuAction(
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
            */
            ToInetMenuAction(
                0,
                R.drawable.ic_settings_gear,
                statusString = "FakeVPN: ${if (Prefs.fakeVpnFakeTlsEnabled) "FakeTLS Вкл" else "Обычный"}"
            ) {
                FakeVpnBottomSheet().show(
                    requireActivity().supportFragmentManager,
                    FakeVpnBottomSheet.TAG
                )
                dismiss()
            }
        )

        if (ArchHelper.isOperaSupported) {
            listItems.add(
                ToInetMenuAction(
                    0,
                    R.drawable.ic_settings_gear,
                    statusString = "Opera Proxy: ${if (Prefs.operaProxyEnabled) "Вкл" else "Выкл"}"
                ) {
                    OperaProxyBottomSheet().show(
                        requireActivity().supportFragmentManager,
                        OperaProxyBottomSheet.TAG
                    )
                    dismiss()
                }
            )
        }

        listItems.add(
            ToInetMenuAction(
                0,
                R.drawable.ic_settings_gear,
                statusString = "Добавить прокси в Telegram"
            ) {
                val ctx = requireContext()
                val proxies = arrayListOf(
                    "TOR (SOCKS5)",
                    "SR (SOCKS5)"
                )
                val proxyLinks = arrayListOf(
                    "tg://socks?server=127.0.0.1&port=5242",
                    "tg://socks?server=127.0.0.1&port=1788"
                )
                if (ArchHelper.isOperaSupported) {
                    proxies.add("OP (SOCKS5)")
                    proxyLinks.add("tg://socks?server=127.0.0.1&port=1888")
                }
                proxies.add("TGWS (SOCKS5)")
                proxyLinks.add("tg://socks?server=127.0.0.1&port=1480")

                AlertDialog.Builder(ctx)
                    .setTitle("Выберите прокси для добавления")
                    .setItems(proxies.toTypedArray()) { _, which ->
                        val link = proxyLinks[which]
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link))
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        try {
                            ctx.startActivity(intent)
                        } catch (e: Exception) {
                            val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("TG Proxy", link))
                            Toast.makeText(ctx, "Telegram не найден. Ссылка скопирована.", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .show()
                dismiss()
            }
        )

        listItems.add(
            ToInetMenuAction(
                0,
                R.drawable.ic_settings_gear,
                statusString = "Настройки приложения\n(тема, оформление, уведомления, сценарии)"
            ) {
                AppSettingsBottomSheet().show(
                    parentFragmentManager,
                    AppSettingsBottomSheet.TAG
                )
                dismiss()
            }
        )
        lvSettings.adapter = ConnectMenuActionAdapter(requireContext(), listItems)
        return view
    }

    companion object {
        const val TAG = "SettingsBottomSheet"
    }
}

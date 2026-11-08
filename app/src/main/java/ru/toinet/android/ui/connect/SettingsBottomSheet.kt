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
                R.drawable.ic_tor,
                statusString = if (Prefs.torEnabled) "Tor (мосты и прокси)" else "Tor: ВЫКЛ"
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
                statusString = "zDPI: ${if (Prefs.byedpiEnabled) "ВКЛ" else "ВЫКЛ"}\n(прокси)"
            ) {
                ByeDpiBottomSheet().show(
                    requireActivity().supportFragmentManager,
                    ByeDpiBottomSheet.TAG
                )
                dismiss()
            }
        )

        if (ArchHelper.isOperaSupported) {
            listItems.add(
                ToInetMenuAction(
                    0,
                    R.drawable.ic_opera,
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
                R.drawable.ic_server,
                statusString = "EasyVPN\n(прокси, подписки)" // : ${if (Prefs.easyVpnProxyEnabled) "ВКЛ" else "ВЫКЛ"}
            ) {
                EasyVpnBottomSheet().show(
                    requireActivity().supportFragmentManager,
                    EasyVpnBottomSheet.TAG
                )
                dismiss()
            }
        )

        listItems.add(
            ToInetMenuAction(
                0,
                R.drawable.ic_server,
                statusString = "SVPN (SSH): ${if (Prefs.svpnEnabled) "ВКЛ" else "ВЫКЛ"}\n(туннель, сервер, ключ)"
            ) {
                SvpnBottomSheet().show(
                    requireActivity().supportFragmentManager,
                    SvpnBottomSheet.TAG
                )
                dismiss()
            }
        )

        listItems.add(
            ToInetMenuAction(
                0,
                R.drawable.ic_send_airplane,
                statusString = "Добавить прокси в Telegram"
            ) {
                val ctx = requireContext()
                val tgwsPort = Prefs.tgwsPort
                val easyVpnPort = Prefs.easyVpnProxyPort
                val svpnPort = Prefs.svpnLocalPort

                val proxies = arrayListOf(
                    "TGWS (порт $tgwsPort)",
                    "zDPI (порт 1780)",
                    "Easy-VPN (порт $easyVpnPort)",
                    "TOR (порт 9853)",
                    "SVPN / SSH (порт $svpnPort)"
                )
                val proxyLinks = arrayListOf(
                    "tg://socks?server=127.0.0.1&port=$tgwsPort",
                    "tg://socks?server=127.0.0.1&port=1780",
                    "tg://socks?server=127.0.0.1&port=$easyVpnPort",
                    "tg://socks?server=127.0.0.1&port=9853",
                    "tg://socks?server=127.0.0.1&port=$svpnPort"
                )

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

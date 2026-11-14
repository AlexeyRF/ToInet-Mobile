package ru.toinet.android.ui.connect

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ListView
import android.widget.TextView
import ru.toinet.android.R
import ru.toinet.android.ui.ToInetBottomSheetDialogFragment
import ru.toinet.android.ui.ToInetMenuAction
import ru.toinet.android.util.ArchHelper
import ru.toinet.android.util.Prefs

class ComponentsSettingsBottomSheet : ToInetBottomSheetDialogFragment() {

    override fun getHeightRatio(): Float = -1f

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.settings_bottom_sheet, container, false)
        view.findViewById<TextView>(R.id.tvTitle)?.text = "Настройки компонентов"
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
                statusString = "EasyVPN\n(прокси, подписки)"
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
                statusString = "TGWS: ${if (Prefs.tgwsEnabled) "ВКЛ" else "ВЫКЛ"}\n(порт, FakeTLS, CF)"
            ) {
                TgwsBottomSheet().show(
                    requireActivity().supportFragmentManager,
                    TgwsBottomSheet.TAG
                )
                dismiss()
            }
        )

        lvSettings.adapter = ConnectMenuActionAdapter(requireContext(), listItems)
        return view
    }

    companion object {
        const val TAG = "ComponentsSettingsBottomSheet"
    }
}

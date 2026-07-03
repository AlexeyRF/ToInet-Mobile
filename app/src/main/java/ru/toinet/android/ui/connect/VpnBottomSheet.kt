package ru.toinet.android.ui.connect

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.activityViewModels
import ru.toinet.android.databinding.VpnBottomSheetBinding
import ru.toinet.android.ui.OrbotBottomSheetDialogFragment
import ru.toinet.android.util.Prefs

class VpnBottomSheet : OrbotBottomSheetDialogFragment() {

    private lateinit var binding: VpnBottomSheetBinding
    private val viewModel: ConnectViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = VpnBottomSheetBinding.inflate(inflater, container, false)

        binding.swVpnEnabled.isChecked = Prefs.isGlobalVpnEnabled

        when (Prefs.vpnProvider) {
            "byedpi" -> binding.rbByedpi.isChecked = true
            "tor" -> binding.rbTor.isChecked = true
            "tgws" -> binding.rbTgws.isChecked = true
            "rehab" -> binding.rbRehab.isChecked = true
            "turnproxy" -> binding.rbTurnProxy.isChecked = true
            "fakevpn" -> binding.rbFakeVpn.isChecked = true
            "operaproxy" -> binding.rbOperaProxy.isChecked = true
            "custom" -> {
                binding.rbCustom.isChecked = true
                binding.llCustomSocks.visibility = View.VISIBLE
            }
            else -> binding.rbByedpi.isChecked = true
        }

        binding.etCustomIp.setText(Prefs.customSocksIp)
        binding.etCustomPort.setText(Prefs.customSocksPort.toString())

        binding.rgVpnProvider.setOnCheckedChangeListener { _, checkedId ->
            binding.llCustomSocks.visibility = if (checkedId == binding.rbCustom.id) View.VISIBLE else View.GONE
            
            if (checkedId == binding.rbTgws.id) {
                AlertDialog.Builder(requireContext())
                    .setTitle("Уведомление TGWS")
                    .setMessage("Это прокси только для Telegram. С остальным трафиком никаких манипуляций проводиться не будет!")
                    .setPositiveButton("ОК", null)
                    .show()
            } else if (checkedId == binding.rbTurnProxy.id) {
                AlertDialog.Builder(requireContext())
                    .setTitle("Уведомление vk-turn")
                    .setMessage("На удалённом сервере должен быть запущен socks, а в настройках сервера и локального клиента должен быть vless режим.")
                    .setPositiveButton("ОК", null)
                    .show()
            }
        }

        binding.btnApps.setOnClickListener {
            val intent = android.content.Intent(requireContext(), ru.toinet.android.ui.vpnapps.VpnAppsActivity::class.java)
            startActivity(intent)
        }

        binding.btnSave.setOnClickListener {
            Prefs.isGlobalVpnEnabled = binding.swVpnEnabled.isChecked
            Prefs.vpnProvider = when (binding.rgVpnProvider.checkedRadioButtonId) {
                binding.rbByedpi.id -> "byedpi"
                binding.rbTor.id -> "tor"
                binding.rbTgws.id -> "tgws"
                binding.rbRehab.id -> "rehab"
                binding.rbTurnProxy.id -> "turnproxy"
                binding.rbFakeVpn.id -> "fakevpn"
                binding.rbOperaProxy.id -> "operaproxy"
                binding.rbCustom.id -> "custom"
                else -> "byedpi"
            }
            
            if (binding.rgVpnProvider.checkedRadioButtonId == binding.rbCustom.id) {
                Prefs.customSocksIp = binding.etCustomIp.text.toString().trim().takeIf { it.isNotBlank() } ?: "127.0.0.1"
                Prefs.customSocksPort = binding.etCustomPort.text.toString().trim().toIntOrNull() ?: 1080
            }
            
            viewModel.triggerRefreshMenuList()
            dismiss()
        }

        return binding.root
    }

    override fun getHeightRatio(): Float = -1f

    companion object {
        const val TAG = "VpnBottomSheet"
    }
}

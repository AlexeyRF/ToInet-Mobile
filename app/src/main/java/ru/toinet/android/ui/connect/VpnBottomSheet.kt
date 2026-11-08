package ru.toinet.android.ui.connect

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.activityViewModels
import ru.toinet.android.databinding.VpnBottomSheetBinding
import ru.toinet.android.ui.ToInetBottomSheetDialogFragment
import ru.toinet.android.util.ArchHelper
import ru.toinet.android.util.Prefs
import ru.toinet.android.util.sendIntentToService

class VpnBottomSheet : ToInetBottomSheetDialogFragment() {

    private lateinit var binding: VpnBottomSheetBinding
    private val viewModel: ConnectViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = VpnBottomSheetBinding.inflate(inflater, container, false)

        if (!ArchHelper.isOperaSupported) {
            binding.rbOperaProxy.visibility = View.GONE
            if (Prefs.vpnProvider == "operaproxy") {
                Prefs.vpnProvider = "byedpi"
            }
        }

        binding.rbTgws.visibility = View.GONE
        if (Prefs.vpnProvider == "tgws") {
            Prefs.vpnProvider = "byedpi"
        }

        binding.swVpnEnabled.isChecked = Prefs.isGlobalVpnEnabled

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            binding.llAppRouting.visibility = View.VISIBLE
            binding.swAppRouting.isChecked = Prefs.appRoutingEnabled
            binding.btnOpenProxifierRules.visibility = if (binding.swAppRouting.isChecked) View.VISIBLE else View.GONE
            binding.swAppRouting.setOnCheckedChangeListener { _, isChecked ->
                binding.btnOpenProxifierRules.visibility = if (isChecked) View.VISIBLE else View.GONE
            }
            binding.btnOpenProxifierRules.setOnClickListener {
                dismiss()
                ProxifierRulesBottomSheet().show(parentFragmentManager, ProxifierRulesBottomSheet.TAG)
            }
        } else {
            binding.llAppRouting.visibility = View.GONE
        }

        when (Prefs.vpnProvider) {
            "byedpi" -> binding.rbByedpi.isChecked = true
            "easyvpn" -> binding.rbEasyVpn.isChecked = true
            "operaproxy" -> binding.rbOperaProxy.isChecked = true
            "tor" -> binding.rbTor.isChecked = true
            "svpn", "ssh" -> binding.rbSvpn.isChecked = true
            // "tgws" -> binding.rbTgws.isChecked = true
            else -> binding.rbByedpi.isChecked = true
        }
        binding.rgVpnProvider.setOnCheckedChangeListener { _, checkedId ->
            
            /*
            if (checkedId == binding.rbTgws.id) {
                AlertDialog.Builder(requireContext())
                    .setTitle("Уведомление TGWS")
                    .setMessage("Это прокси только для Telegram. С остальным трафиком никаких манипуляций проводиться не будет!")
                    .setPositiveButton("ОК", null)
                    .show()
            }
            */
        }

        binding.btnApps.setOnClickListener {
            val intent = android.content.Intent(requireContext(), ru.toinet.android.ui.vpnapps.VpnAppsActivity::class.java)
            startActivity(intent)
        }

        binding.btnSave.setOnClickListener {
            Prefs.isGlobalVpnEnabled = binding.swVpnEnabled.isChecked
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                Prefs.appRoutingEnabled = binding.swAppRouting.isChecked
            }
            Prefs.vpnProvider = when (binding.rgVpnProvider.checkedRadioButtonId) {
                binding.rbByedpi.id -> "byedpi"
                binding.rbEasyVpn.id -> "easyvpn"
                binding.rbOperaProxy.id -> "operaproxy"
                binding.rbTor.id -> "tor"
                // binding.rbTgws.id -> "tgws"
                binding.rbSvpn.id -> "svpn"
                else -> "byedpi"
            }            
            if (Prefs.isGlobalVpnEnabled && Prefs.vpnProvider == "easyvpn") {
                requireContext().sendIntentToService("ACTIVE")
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

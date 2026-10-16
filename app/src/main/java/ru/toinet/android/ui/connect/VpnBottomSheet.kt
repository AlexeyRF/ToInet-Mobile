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

        binding.swVpnEnabled.isChecked = Prefs.isGlobalVpnEnabled

        when (Prefs.vpnProvider) {
            "byedpi" -> binding.rbByedpi.isChecked = true
            "easyvpn" -> {
                binding.rbEasyVpn.isChecked = true
                binding.llEasyVpnSettings.visibility = View.VISIBLE
            }
            "operaproxy" -> binding.rbOperaProxy.isChecked = true
            "tor" -> binding.rbTor.isChecked = true
            "zapret" -> binding.rbZapret.isChecked = true
            "rehab", "custom" -> binding.rbRehab.isChecked = true
            "tgws" -> binding.rbTgws.isChecked = true
            "fakevpn" -> {
                binding.rbFakeVpn.isChecked = true
                binding.llFakeVpnSettings.visibility = View.VISIBLE
            }
            else -> binding.rbByedpi.isChecked = true
        }

        binding.etEasyVpnSubs.setText(Prefs.easyVpnSubs)

        binding.swFakeTlsEnabled.isChecked = Prefs.fakeVpnFakeTlsEnabled
        binding.etFakeVpnDomains.setText(Prefs.fakeVpnFakeTlsDomains)
        binding.tilFakeVpnDomains.visibility = if (binding.swFakeTlsEnabled.isChecked) View.VISIBLE else View.GONE
        binding.swFakeTlsEnabled.setOnCheckedChangeListener { _, isChecked ->
            binding.tilFakeVpnDomains.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        var lastUpdate = Prefs.lastEasyVpnUpdate
        if (lastUpdate == 0L) {
            val file = java.io.File(requireContext().filesDir, "easyvpn_user_nodes.json")
            if (file.exists()) {
                try {
                    val json = org.json.JSONObject(file.readText())
                    val tsSec = json.optDouble("timestamp", 0.0)
                    if (tsSec > 0) {
                        lastUpdate = (tsSec * 1000).toLong()
                        Prefs.lastEasyVpnUpdate = lastUpdate
                    }
                } catch (_: Exception) {}
            }
        }

        if (lastUpdate > 0L) {
            val sdf = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault())
            binding.tvEasyVpnSubsHeader.text = "Стандартные подписки (обновлено: ${sdf.format(java.util.Date(lastUpdate))})"
        } else {
            binding.tvEasyVpnSubsHeader.text = "Ссылки на подписки"
        }

        binding.rgVpnProvider.setOnCheckedChangeListener { _, checkedId ->
            binding.llEasyVpnSettings.visibility = if (checkedId == binding.rbEasyVpn.id) View.VISIBLE else View.GONE
            binding.llFakeVpnSettings.visibility = if (checkedId == binding.rbFakeVpn.id) View.VISIBLE else View.GONE
            
            if (checkedId == binding.rbTgws.id) {
                AlertDialog.Builder(requireContext())
                    .setTitle("Уведомление TGWS")
                    .setMessage("Это прокси только для Telegram. С остальным трафиком никаких манипуляций проводиться не будет!")
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
                binding.rbEasyVpn.id -> "easyvpn"
                binding.rbOperaProxy.id -> "operaproxy"
                binding.rbTor.id -> "tor"
                binding.rbZapret.id -> "zapret"
                binding.rbRehab.id -> "rehab"
                binding.rbTgws.id -> "tgws"
                binding.rbFakeVpn.id -> "fakevpn"
                else -> "byedpi"
            }
            
            if (binding.rgVpnProvider.checkedRadioButtonId == binding.rbEasyVpn.id) {
                Prefs.easyVpnSubs = binding.etEasyVpnSubs.text.toString().trim()
            }

            if (binding.rgVpnProvider.checkedRadioButtonId == binding.rbFakeVpn.id) {
                Prefs.fakeVpnFakeTlsEnabled = binding.swFakeTlsEnabled.isChecked
                Prefs.fakeVpnFakeTlsDomains = binding.etFakeVpnDomains.text.toString().trim()
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

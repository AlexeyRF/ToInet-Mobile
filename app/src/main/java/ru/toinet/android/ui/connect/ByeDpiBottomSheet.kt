package ru.toinet.android.ui.connect

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.activityViewModels
import ru.toinet.android.byedpi.services.ByeDpiProxyService
import ru.toinet.android.databinding.ByedpiBottomSheetBinding
import ru.toinet.android.ui.ToInetBottomSheetDialogFragment
import ru.toinet.android.util.NetworkUtils
import ru.toinet.android.util.Prefs
import ru.toinet.android.util.UpstreamProxyCoordinator
import ru.toinet.android.util.sendIntentToService
import ru.toinet.android.vpn.data.ServiceStatus

class ByeDpiBottomSheet : ToInetBottomSheetDialogFragment() {

    private lateinit var binding: ByedpiBottomSheetBinding
    private val viewModel: ConnectViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = ByedpiBottomSheetBinding.inflate(inflater, container, false)

        val isWifi = NetworkUtils.isWifiConnected(requireContext())
        binding.tvCurrentNetwork.text = if (isWifi) {
            "Текущая активная сеть: Wi-Fi (действует стратегия Wi-Fi)"
        } else {
            "Текущая активная сеть: Мобильная сеть (действует мобильная стратегия)"
        }

        binding.swEnabled.isChecked = Prefs.byedpiEnabled
        binding.etWifiArgs.setText(Prefs.byedpiWifiArgs)
        binding.etMobileArgs.setText(Prefs.byedpiMobileArgs)
        configureMultilineEditTextScrollEvent(binding.etWifiArgs)
        configureMultilineEditTextScrollEvent(binding.etMobileArgs)

        binding.swByeDpiUpstreamEnabled.isChecked = Prefs.byedpiUpstreamEnabled
        binding.llByeDpiUpstream.visibility = if (binding.swByeDpiUpstreamEnabled.isChecked) View.VISIBLE else View.GONE
        binding.swByeDpiUpstreamEnabled.setOnCheckedChangeListener { _, isChecked ->
            binding.llByeDpiUpstream.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        val upstreamOptions = listOf(
            UpstreamProxyCoordinator.PROVIDER_EASYVPN to "Easy-VPN (127.0.0.1:${Prefs.easyVpnProxyPort})",
            UpstreamProxyCoordinator.PROVIDER_TOR to "TOR (127.0.0.1:9853)",
        )
        val upstreamTitles = upstreamOptions.map { it.second }
        val upstreamAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, upstreamTitles)
        binding.spByeDpiUpstreamProvider.adapter = upstreamAdapter

        val curProvider = Prefs.byedpiUpstreamProvider
        val curIndex = upstreamOptions.indexOfFirst { it.first == curProvider }.coerceAtLeast(0)
        binding.spByeDpiUpstreamProvider.setSelection(curIndex)

        fun saveCommonSettings() {
            Prefs.byedpiEnabled = binding.swEnabled.isChecked
            Prefs.byedpiEnableCmdSettings = true
            Prefs.byedpiUpstreamEnabled = binding.swByeDpiUpstreamEnabled.isChecked
            val chosen = upstreamOptions[binding.spByeDpiUpstreamProvider.selectedItemPosition].first
            Prefs.byedpiUpstreamProvider = chosen
            Prefs.byedpiUpstreamHost = "127.0.0.1"
            Prefs.byedpiUpstreamPort = UpstreamProxyCoordinator.getPortForProvider(chosen)
        }

        val testActivityLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { _ ->
            binding.etWifiArgs.setText(Prefs.byedpiWifiArgs)
            binding.etMobileArgs.setText(Prefs.byedpiMobileArgs)
        }

        binding.btnTest.setOnClickListener {
            val intent = Intent(requireContext(), ru.toinet.android.byedpi.ui.TestActivity::class.java)
            testActivityLauncher.launch(intent)
        }

        binding.btnSave.setOnClickListener {
            val oldWifiArgs = Prefs.byedpiWifiArgs
            val oldMobileArgs = Prefs.byedpiMobileArgs

            val newWifiArgs = binding.etWifiArgs.text.toString().trim()
            val newMobileArgs = binding.etMobileArgs.text.toString().trim()

            Prefs.byedpiWifiArgs = newWifiArgs
            Prefs.byedpiMobileArgs = newMobileArgs
            Prefs.byedpiArgs = Prefs.getCurrentByedpiArgs(requireContext())
            saveCommonSettings()

            // If zDPI is running and the active strategy changed, reload it
            val isCurrentWifi = NetworkUtils.isWifiConnected(requireContext())
            val activeChanged = if (isCurrentWifi) oldWifiArgs != newWifiArgs else oldMobileArgs != newMobileArgs
            if (ByeDpiProxyService.status == ServiceStatus.Connected && activeChanged) {
                val restartIntent = Intent(requireContext(), ByeDpiProxyService::class.java).apply {
                    action = ByeDpiProxyService.ACTION_RESTART
                    putExtra("is_foreground", !Prefs.isGlobalVpnEnabled && Prefs.isPrimaryNotificationService("byedpi"))
                }
                requireContext().sendIntentToService(restartIntent, isForeground = false)
            }

            Toast.makeText(requireContext(), "Настройки zDPI сохранены", Toast.LENGTH_SHORT).show()
            viewModel.triggerRefreshMenuList()
            dismiss()
        }

        return binding.root
    }

    override fun getHeightRatio(): Float = -1f

    companion object {
        const val TAG = "ByeDpiBottomSheet"
    }
}

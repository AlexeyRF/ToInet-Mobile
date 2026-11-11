package ru.toinet.android.ui.connect

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.activityViewModels
import ru.toinet.android.byedpi.services.ByeDpiProxyService
import ru.toinet.android.databinding.ByedpiBottomSheetBinding
import ru.toinet.android.ui.ToInetBottomSheetDialogFragment
import ru.toinet.android.util.NetworkUtils
import ru.toinet.android.util.Prefs
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
            ru.toinet.android.util.UpstreamProxyCoordinator.PROVIDER_EASYVPN to "Easy-VPN (127.0.0.1:${Prefs.easyVpnProxyPort})",
            ru.toinet.android.util.UpstreamProxyCoordinator.PROVIDER_TOR to "TOR (127.0.0.1:9853)",
        )
        val upstreamTitles = upstreamOptions.map { it.second }
        val upstreamAdapter = android.widget.ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, upstreamTitles)
        binding.spByeDpiUpstreamProvider.adapter = upstreamAdapter

        val curProvider = Prefs.byedpiUpstreamProvider
        val curIndex = upstreamOptions.indexOfFirst { it.first == curProvider }.coerceAtLeast(0)
        binding.spByeDpiUpstreamProvider.setSelection(curIndex)

        val testActivityLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                val strategy = result.data?.getStringExtra("strategy")
                if (strategy != null) {
                    val isCurrentWifi = NetworkUtils.isWifiConnected(requireContext())
                    val options = arrayOf(
                        "Для Wi-Fi${if (isCurrentWifi) " (текущая сеть)" else ""}",
                        "Для Мобильной сети${if (!isCurrentWifi) " (текущая сеть)" else ""}",
                        "Для обеих сетей"
                    )
                    AlertDialog.Builder(requireContext())
                        .setTitle("Куда применить найденную стратегию?")
                        .setItems(options) { _, which ->
                            when (which) {
                                0 -> binding.etWifiArgs.setText(strategy)
                                1 -> binding.etMobileArgs.setText(strategy)
                                2 -> {
                                    binding.etWifiArgs.setText(strategy)
                                    binding.etMobileArgs.setText(strategy)
                                }
                            }
                        }
                        .setNegativeButton("Отмена", null)
                        .show()
                }
            }
        }

        binding.btnTest.setOnClickListener {
            val intent = android.content.Intent(requireContext(), ru.toinet.android.byedpi.ui.TestActivity::class.java)
            testActivityLauncher.launch(intent)
        }

        binding.btnSave.setOnClickListener {
            val oldWifiArgs = Prefs.byedpiWifiArgs
            val oldMobileArgs = Prefs.byedpiMobileArgs

            val newWifiArgs = binding.etWifiArgs.text.toString().trim()
            val newMobileArgs = binding.etMobileArgs.text.toString().trim()

            Prefs.byedpiEnabled = binding.swEnabled.isChecked
            Prefs.byedpiWifiArgs = newWifiArgs
            Prefs.byedpiMobileArgs = newMobileArgs
            Prefs.byedpiArgs = Prefs.getCurrentByedpiArgs(requireContext())
            Prefs.byedpiEnableCmdSettings = true

            Prefs.byedpiUpstreamEnabled = binding.swByeDpiUpstreamEnabled.isChecked
            val chosen = upstreamOptions[binding.spByeDpiUpstreamProvider.selectedItemPosition].first
            Prefs.byedpiUpstreamProvider = chosen
            Prefs.byedpiUpstreamHost = "127.0.0.1"
            Prefs.byedpiUpstreamPort = ru.toinet.android.util.UpstreamProxyCoordinator.getPortForProvider(chosen)

            // If zDPI is running and the active strategy changed, reload it
            val isCurrentWifi = NetworkUtils.isWifiConnected(requireContext())
            val activeChanged = if (isCurrentWifi) oldWifiArgs != newWifiArgs else oldMobileArgs != newMobileArgs
            if (ByeDpiProxyService.status == ServiceStatus.Connected && activeChanged) {
                val restartIntent = android.content.Intent(requireContext(), ByeDpiProxyService::class.java).apply {
                    action = ByeDpiProxyService.ACTION_RESTART
                    putExtra("is_foreground", !Prefs.isGlobalVpnEnabled && Prefs.isPrimaryNotificationService("byedpi"))
                }
                requireContext().sendIntentToService(restartIntent, isForeground = false)
            }

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

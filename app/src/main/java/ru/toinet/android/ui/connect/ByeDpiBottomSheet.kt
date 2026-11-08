package ru.toinet.android.ui.connect

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import ru.toinet.android.databinding.ByedpiBottomSheetBinding
import ru.toinet.android.ui.ToInetBottomSheetDialogFragment
import ru.toinet.android.util.Prefs

class ByeDpiBottomSheet : ToInetBottomSheetDialogFragment() {

    private lateinit var binding: ByedpiBottomSheetBinding
    private val viewModel: ConnectViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = ByedpiBottomSheetBinding.inflate(inflater, container, false)

        binding.swEnabled.isChecked = Prefs.byedpiEnabled
        binding.etArgs.setText(Prefs.byedpiArgs)
        configureMultilineEditTextScrollEvent(binding.etArgs)

        binding.swByeDpiUpstreamEnabled.isChecked = Prefs.byedpiUpstreamEnabled
        binding.llByeDpiUpstream.visibility = if (binding.swByeDpiUpstreamEnabled.isChecked) View.VISIBLE else View.GONE
        binding.swByeDpiUpstreamEnabled.setOnCheckedChangeListener { _, isChecked ->
            binding.llByeDpiUpstream.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        val upstreamOptions = listOf(
            ru.toinet.android.util.UpstreamProxyCoordinator.PROVIDER_EASYVPN to "Easy-VPN (127.0.0.1:${Prefs.easyVpnProxyPort})",
            ru.toinet.android.util.UpstreamProxyCoordinator.PROVIDER_TOR to "TOR (127.0.0.1:9853)",
            ru.toinet.android.util.UpstreamProxyCoordinator.PROVIDER_ZAPRET to "Zapret (127.0.0.1:${Prefs.zapretPort})",
            ru.toinet.android.util.UpstreamProxyCoordinator.PROVIDER_REHAB to "Socks Rehab (127.0.0.1:1788)"
        )
        val upstreamTitles = upstreamOptions.map { it.second }
        val upstreamAdapter = android.widget.ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, upstreamTitles)
        binding.spByeDpiUpstreamProvider.adapter = upstreamAdapter

        val curProvider = Prefs.byedpiUpstreamProvider
        val curIndex = upstreamOptions.indexOfFirst { it.first == curProvider }.coerceAtLeast(0)
        binding.spByeDpiUpstreamProvider.setSelection(curIndex)

        binding.btnSave.setOnClickListener {
            Prefs.byedpiEnabled = binding.swEnabled.isChecked
            Prefs.byedpiArgs = binding.etArgs.text.toString().trim()
            Prefs.byedpiEnableCmdSettings = true

            Prefs.byedpiUpstreamEnabled = binding.swByeDpiUpstreamEnabled.isChecked
            val chosen = upstreamOptions[binding.spByeDpiUpstreamProvider.selectedItemPosition].first
            Prefs.byedpiUpstreamProvider = chosen
            Prefs.byedpiUpstreamHost = "127.0.0.1"
            Prefs.byedpiUpstreamPort = ru.toinet.android.util.UpstreamProxyCoordinator.getPortForProvider(chosen)

            viewModel.triggerRefreshMenuList()
            dismiss()
        }

        val testActivityLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                val strategy = result.data?.getStringExtra("strategy")
                if (strategy != null) {
                    binding.etArgs.setText(strategy)
                }
            }
        }

        binding.btnTest.setOnClickListener {
            val intent = android.content.Intent(requireContext(), ru.toinet.android.byedpi.ui.TestActivity::class.java)
            testActivityLauncher.launch(intent)
        }

        return binding.root
    }

    override fun getHeightRatio(): Float = -1f

    companion object {
        const val TAG = "ByeDpiBottomSheet"
    }
}

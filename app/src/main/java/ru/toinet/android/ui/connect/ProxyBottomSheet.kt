package ru.toinet.android.ui.connect

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import ru.toinet.android.R
import ru.toinet.android.databinding.ProxyBottomSheetBinding
import ru.toinet.android.ui.ToInetBottomSheetDialogFragment
import ru.toinet.android.util.Prefs
import ru.toinet.android.util.UpstreamProxyCoordinator
import ru.toinet.android.util.getPrefString
import ru.toinet.android.util.putPref

class ProxyBottomSheet : ToInetBottomSheetDialogFragment() {

    private lateinit var binding: ProxyBottomSheetBinding

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = ProxyBottomSheetBinding.inflate(inflater, container, false)

        binding.swProxyEnabled.isChecked = Prefs.proxyEnabled
        
        val proxyType = requireContext().contentResolver.getPrefString("pref_proxy_type") ?: "SOCKS5"
        val proxyTypes = resources.getStringArray(R.array.proxy_types)
        
        val typeAdapter = ArrayAdapter(
            requireContext(),
            R.layout.item_spinner,
            proxyTypes
        )
        typeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spProxyType.adapter = typeAdapter

        val selection = proxyTypes.indexOf(proxyType).coerceAtLeast(0)
        binding.spProxyType.setSelection(selection)

        binding.etProxyHost.setText(requireContext().contentResolver.getPrefString("pref_proxy_host"))
        binding.etProxyPort.setText(requireContext().contentResolver.getPrefString("pref_proxy_port"))
        binding.etProxyUser.setText(requireContext().contentResolver.getPrefString("pref_proxy_username"))
        binding.etProxyPassword.setText(requireContext().contentResolver.getPrefString("pref_proxy_password"))

        // Bundled providers setup
        val bundledOptions = listOf(
            UpstreamProxyCoordinator.PROVIDER_CUSTOM to "Пользовательский (ввести вручную)",
            UpstreamProxyCoordinator.PROVIDER_EASYVPN to "Easy-VPN (127.0.0.1:${Prefs.easyVpnProxyPort}) — для белых списков",
            UpstreamProxyCoordinator.PROVIDER_BYEDPI to "zDPI (127.0.0.1:1780)",
            UpstreamProxyCoordinator.PROVIDER_ZAPRET to "Zapret (127.0.0.1:${Prefs.zapretPort})",
            UpstreamProxyCoordinator.PROVIDER_REHAB to "Socks Rehab (127.0.0.1:1788)"
        )
        val bundledTitles = bundledOptions.map { it.second }
        val bundledAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, bundledTitles)
        binding.spBundledProvider.adapter = bundledAdapter

        val currentUpstream = Prefs.torUpstreamProvider
        val currentIdx = bundledOptions.indexOfFirst { it.first == currentUpstream }.coerceAtLeast(0)
        binding.spBundledProvider.setSelection(currentIdx)

        binding.spBundledProvider.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedKey = bundledOptions[position].first
                if (selectedKey != UpstreamProxyCoordinator.PROVIDER_CUSTOM) {
                    val socksIdx = proxyTypes.indexOf("SOCKS5").coerceAtLeast(0)
                    binding.spProxyType.setSelection(socksIdx)
                    binding.etProxyHost.setText("127.0.0.1")
                    val port = UpstreamProxyCoordinator.getPortForProvider(selectedKey)
                    binding.etProxyPort.setText(port.toString())
                    binding.etProxyUser.setText("")
                    binding.etProxyPassword.setText("")
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        binding.btnSave.setOnClickListener {
            saveAndDismiss(bundledOptions[binding.spBundledProvider.selectedItemPosition].first)
        }

        return binding.root
    }

    private fun saveAndDismiss(chosenProvider: String) {
        Prefs.proxyEnabled = binding.swProxyEnabled.isChecked
        Prefs.torUpstreamProvider = chosenProvider
        
        val proxyTypes = resources.getStringArray(R.array.proxy_types)
        val selectedType = proxyTypes[binding.spProxyType.selectedItemPosition]
        requireContext().contentResolver.putPref("pref_proxy_type", selectedType)

        requireContext().contentResolver.putPref("pref_proxy_host", binding.etProxyHost.text.toString().trim())
        requireContext().contentResolver.putPref("pref_proxy_port", binding.etProxyPort.text.toString().trim())
        requireContext().contentResolver.putPref("pref_proxy_username", binding.etProxyUser.text.toString().trim())
        requireContext().contentResolver.putPref("pref_proxy_password", binding.etProxyPassword.text.toString().trim())

        dismiss()
    }

    override fun getHeightRatio(): Float = -1f

    companion object {
        const val TAG = "ProxyBottomSheet"
    }
}

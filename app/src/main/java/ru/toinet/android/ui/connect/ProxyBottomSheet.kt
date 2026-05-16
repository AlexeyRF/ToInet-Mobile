package ru.toinet.android.ui.connect

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import ru.toinet.android.R
import ru.toinet.android.databinding.ProxyBottomSheetBinding
import ru.toinet.android.ui.OrbotBottomSheetDialogFragment
import ru.toinet.android.util.Prefs
import ru.toinet.android.util.getPrefString
import ru.toinet.android.util.putPref

class ProxyBottomSheet : OrbotBottomSheetDialogFragment() {

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
        
        val adapter = ArrayAdapter(
            requireContext(),
            R.layout.item_spinner,
            proxyTypes
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spProxyType.adapter = adapter

        val selection = proxyTypes.indexOf(proxyType).coerceAtLeast(0)
        binding.spProxyType.setSelection(selection)

        binding.etProxyHost.setText(requireContext().contentResolver.getPrefString("pref_proxy_host"))
        binding.etProxyPort.setText(requireContext().contentResolver.getPrefString("pref_proxy_port"))
        binding.etProxyUser.setText(requireContext().contentResolver.getPrefString("pref_proxy_username"))
        binding.etProxyPassword.setText(requireContext().contentResolver.getPrefString("pref_proxy_password"))

        binding.btnSave.setOnClickListener {
            saveAndDismiss()
        }

        return binding.root
    }

    private fun saveAndDismiss() {
        Prefs.proxyEnabled = binding.swProxyEnabled.isChecked
        
        val proxyTypes = resources.getStringArray(R.array.proxy_types)
        val selectedType = proxyTypes[binding.spProxyType.selectedItemPosition]
        requireContext().contentResolver.putPref("pref_proxy_type", selectedType)

        requireContext().contentResolver.putPref("pref_proxy_host", binding.etProxyHost.text.toString().trim())
        requireContext().contentResolver.putPref("pref_proxy_port", binding.etProxyPort.text.toString().trim())
        requireContext().contentResolver.putPref("pref_proxy_username", binding.etProxyUser.text.toString().trim())
        requireContext().contentResolver.putPref("pref_proxy_password", binding.etProxyPassword.text.toString().trim())

        dismiss()
    }

    override fun getHeightRatio(): Float = 0.7f

    companion object {
        const val TAG = "ProxyBottomSheet"
    }
}

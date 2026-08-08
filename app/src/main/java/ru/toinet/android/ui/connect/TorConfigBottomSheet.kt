package ru.toinet.android.ui.connect

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import ru.toinet.android.databinding.TorConfigBottomSheetBinding
import ru.toinet.android.ui.OrbotBottomSheetDialogFragment

import androidx.fragment.app.activityViewModels
import ru.toinet.android.util.Prefs

class TorConfigBottomSheet : OrbotBottomSheetDialogFragment() {

    private lateinit var binding: TorConfigBottomSheetBinding
    private val viewModel: ConnectViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = TorConfigBottomSheetBinding.inflate(inflater, container, false)

        binding.swTorEnabled.isChecked = Prefs.torEnabled
        val isCustom = Prefs.transport == ru.toinet.android.service.circumvention.Transport.CUSTOM
        binding.btnBridges.isEnabled = Prefs.torEnabled && isCustom
        binding.btnProxy.isEnabled = Prefs.torEnabled
        binding.btnExitNode.isEnabled = Prefs.torEnabled

        binding.swTorEnabled.setOnCheckedChangeListener { _, isChecked ->
            Prefs.torEnabled = isChecked
            val isCustomChecked = binding.rbCustom.isChecked
            binding.btnBridges.isEnabled = isChecked && isCustomChecked
            binding.btnProxy.isEnabled = isChecked
            binding.btnExitNode.isEnabled = isChecked
            for (i in 0 until binding.rgTorMode.childCount) {
                binding.rgTorMode.getChildAt(i).isEnabled = isChecked
            }
            binding.cbIgnoreEmptyUrl.isEnabled = isChecked
            viewModel.triggerRefreshMenuList()
        }

        val transport = Prefs.transport
        when (transport) {
            ru.toinet.android.service.circumvention.Transport.NONE -> binding.rbDirect.isChecked = true
            ru.toinet.android.service.circumvention.Transport.OBFS4 -> binding.rbObfs4.isChecked = true
            ru.toinet.android.service.circumvention.Transport.WEBTUNNEL -> binding.rbWebtunnel.isChecked = true
            ru.toinet.android.service.circumvention.Transport.DNSTT -> binding.rbDnstt.isChecked = true
            ru.toinet.android.service.circumvention.Transport.CUSTOM -> binding.rbCustom.isChecked = true
            else -> binding.rbDirect.isChecked = true
        }

        binding.cbIgnoreEmptyUrl.isChecked = Prefs.torIgnoreEmptyUrl

        for (i in 0 until binding.rgTorMode.childCount) {
            binding.rgTorMode.getChildAt(i).isEnabled = Prefs.torEnabled
        }
        binding.cbIgnoreEmptyUrl.isEnabled = Prefs.torEnabled

        binding.rgTorMode.setOnCheckedChangeListener { _, checkedId ->
            Prefs.transport = when (checkedId) {
                binding.rbDirect.id -> ru.toinet.android.service.circumvention.Transport.NONE
                binding.rbObfs4.id -> ru.toinet.android.service.circumvention.Transport.OBFS4
                binding.rbWebtunnel.id -> ru.toinet.android.service.circumvention.Transport.WEBTUNNEL
                binding.rbDnstt.id -> {
                    android.app.AlertDialog.Builder(requireContext())
                        .setTitle(ru.toinet.android.R.string.limit_dns_tunnel_use)
                        .setMessage(ru.toinet.android.R.string.dns_tunnel_usage_description)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                    ru.toinet.android.service.circumvention.Transport.DNSTT
                }
                binding.rbCustom.id -> ru.toinet.android.service.circumvention.Transport.CUSTOM
                else -> ru.toinet.android.service.circumvention.Transport.NONE
            }
            val isCustomMode = checkedId == binding.rbCustom.id
            binding.btnBridges.isEnabled = Prefs.torEnabled && isCustomMode
        }

        binding.cbIgnoreEmptyUrl.setOnCheckedChangeListener { _, isChecked ->
            Prefs.torIgnoreEmptyUrl = isChecked
        }

        binding.btnBridges.setOnClickListener {
            CustomBridgeBottomSheet().show(
                requireActivity().supportFragmentManager,
                CustomBridgeBottomSheet.TAG
            )
        }

        binding.btnProxy.setOnClickListener {
            ProxyBottomSheet().show(
                requireActivity().supportFragmentManager,
                ProxyBottomSheet.TAG
            )
        }

        binding.btnExitNode.setOnClickListener {
            ExitNodeBottomSheet().show(
                requireActivity().supportFragmentManager,
                "ExitNodeBottomSheet"
            )
        }

        binding.btnClose.setOnClickListener {
            dismiss()
        }

        return binding.root
    }

    override fun getHeightRatio(): Float = -1f

    companion object {
        const val TAG = "TorConfigBottomSheet"
    }
}

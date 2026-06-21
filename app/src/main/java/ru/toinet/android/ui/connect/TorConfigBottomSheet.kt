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
        binding.btnBridges.isEnabled = Prefs.torEnabled
        binding.btnProxy.isEnabled = Prefs.torEnabled

        binding.swTorEnabled.setOnCheckedChangeListener { _, isChecked ->
            Prefs.torEnabled = isChecked
            binding.btnBridges.isEnabled = isChecked
            binding.btnProxy.isEnabled = isChecked
            viewModel.triggerRefreshMenuList()
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

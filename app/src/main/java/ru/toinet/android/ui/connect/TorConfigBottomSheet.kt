package ru.toinet.android.ui.connect

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import ru.toinet.android.databinding.TorConfigBottomSheetBinding
import ru.toinet.android.ui.OrbotBottomSheetDialogFragment

class TorConfigBottomSheet : OrbotBottomSheetDialogFragment() {

    private lateinit var binding: TorConfigBottomSheetBinding

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = TorConfigBottomSheetBinding.inflate(inflater, container, false)

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

    override fun getHeightRatio(): Float = 0.5f

    companion object {
        const val TAG = "TorConfigBottomSheet"
    }
}

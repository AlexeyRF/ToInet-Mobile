package ru.toinet.android.ui.connect

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import ru.toinet.android.databinding.ByedpiBottomSheetBinding
import ru.toinet.android.ui.OrbotBottomSheetDialogFragment
import ru.toinet.android.util.Prefs

class ByeDpiBottomSheet : OrbotBottomSheetDialogFragment() {

    private lateinit var binding: ByedpiBottomSheetBinding
    private val viewModel: ConnectViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = ByedpiBottomSheetBinding.inflate(inflater, container, false)

        binding.swEnabled.isChecked = Prefs.byedpiEnabled
        if (Prefs.byedpiMode == "VPN") {
            binding.rbVpn.isChecked = true
        } else {
            binding.rbProxy.isChecked = true
        }
        binding.etArgs.setText(Prefs.byedpiArgs)

        binding.btnSave.setOnClickListener {
            Prefs.byedpiEnabled = binding.swEnabled.isChecked
            Prefs.byedpiMode = if (binding.rbVpn.isChecked) "VPN" else "Proxy"
            Prefs.byedpiArgs = binding.etArgs.text.toString().trim()
            viewModel.triggerRefreshMenuList()
            dismiss()
        }

        return binding.root
    }

    override fun getHeightRatio(): Float = 0.6f

    companion object {
        const val TAG = "ByeDpiBottomSheet"
    }
}

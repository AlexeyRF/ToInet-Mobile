package ru.toinet.android.ui.connect

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import ru.toinet.android.databinding.OperaProxyBottomSheetBinding
import ru.toinet.android.ui.OrbotBottomSheetDialogFragment
import ru.toinet.android.util.Prefs
import android.os.Build
import android.widget.Toast

class OperaProxyBottomSheet : OrbotBottomSheetDialogFragment() {

    private lateinit var binding: OperaProxyBottomSheetBinding
    private val viewModel: ConnectViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = OperaProxyBottomSheetBinding.inflate(inflater, container, false)

        binding.swEnabled.isChecked = Prefs.operaProxyEnabled
        binding.etBindAddress.setText(Prefs.operaProxyBindAddress)
        binding.etUpstream.setText(Prefs.operaProxyUpstream)
        binding.swVerbose.isChecked = Prefs.operaProxyVerbose
        binding.swUseByeDpi.isChecked = Prefs.operaProxyUseByeDpi
        
        val supportedAbis = Build.SUPPORTED_ABIS
        val isArm = supportedAbis.any { it.contains("arm") }
        if (!isArm) {
            binding.swEnabled.isEnabled = false
            binding.swEnabled.text = "Включить Opera Proxy (Не поддерживается на x86)"
            if (Prefs.operaProxyEnabled) {
                Prefs.operaProxyEnabled = false
                binding.swEnabled.isChecked = false
            }
        }

        binding.btnSave.setOnClickListener {
            Prefs.operaProxyEnabled = binding.swEnabled.isChecked
            Prefs.operaProxyBindAddress = binding.etBindAddress.text.toString()
            Prefs.operaProxyUpstream = binding.etUpstream.text.toString()
            Prefs.operaProxyVerbose = binding.swVerbose.isChecked
            Prefs.operaProxyUseByeDpi = binding.swUseByeDpi.isChecked
            
            viewModel.triggerRefreshMenuList()
            dismiss()
        }

        return binding.root
    }

    override fun getHeightRatio(): Float = -1f

    companion object {
        const val TAG = "OperaProxyBottomSheet"
    }
}

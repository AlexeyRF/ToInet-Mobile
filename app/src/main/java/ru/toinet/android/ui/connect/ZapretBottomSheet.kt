package ru.toinet.android.ui.connect

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import ru.toinet.android.databinding.ZapretBottomSheetBinding
import ru.toinet.android.ui.ToInetBottomSheetDialogFragment
import ru.toinet.android.util.Prefs

class ZapretBottomSheet : ToInetBottomSheetDialogFragment() {

    private lateinit var binding: ZapretBottomSheetBinding
    private val viewModel: ConnectViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = ZapretBottomSheetBinding.inflate(inflater, container, false)

        binding.swEnabled.isChecked = Prefs.zapretEnabled
        binding.etPort.setText(Prefs.zapretPort.toString())
        binding.etArgs.setText(Prefs.zapretArgs)
        configureMultilineEditTextScrollEvent(binding.etArgs)

        binding.btnPresetSplit2.setOnClickListener {
            binding.etArgs.setText("--split-pos=2")
        }
        binding.btnPresetSplitSni.setOnClickListener {
            binding.etArgs.setText("--split-pos=sni")
        }
        binding.btnPresetDisorder.setOnClickListener {
            binding.etArgs.setText("--split-pos=2 --disorder")
        }
        binding.btnPresetHostcase.setOnClickListener {
            binding.etArgs.setText("--split-pos=2 --hostcase")
        }

        binding.btnSave.setOnClickListener {
            Prefs.zapretEnabled = binding.swEnabled.isChecked
            Prefs.zapretPort = binding.etPort.text.toString().trim().toIntOrNull() ?: 1081
            Prefs.zapretArgs = binding.etArgs.text.toString().trim()
            viewModel.triggerRefreshMenuList()
            dismiss()
        }

        return binding.root
    }

    override fun getHeightRatio(): Float = -1f

    companion object {
        const val TAG = "ZapretBottomSheet"
    }
}

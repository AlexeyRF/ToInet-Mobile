package ru.toinet.android.ui.connect

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import ru.toinet.android.databinding.TgwsBottomSheetBinding
import ru.toinet.android.ui.OrbotBottomSheetDialogFragment
import ru.toinet.android.util.Prefs
import ru.toinet.android.util.putPref

class TgwsBottomSheet : OrbotBottomSheetDialogFragment() {

    private lateinit var binding: TgwsBottomSheetBinding
    private val viewModel: ConnectViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = TgwsBottomSheetBinding.inflate(inflater, container, false)

        binding.swEnabled.isChecked = Prefs.tgwsEnabled
        binding.etPort.setText(Prefs.tgwsPort.toString())
        
        val mappingStr = Prefs.tgwsDcMappings.map { "${it.key}:${it.value}" }.joinToString("\n")
        binding.etMappings.setText(mappingStr)
        configureMultilineEditTextScrollEvent(binding.etMappings)

        binding.btnSave.setOnClickListener {
            Prefs.tgwsEnabled = binding.swEnabled.isChecked
            val port = binding.etPort.text.toString().toIntOrNull() ?: 1480
            Prefs.tgwsPort = port
            
            val mappings = binding.etMappings.text.toString().split("\n")
                .filter { it.contains(":") }
                .associate {
                    val parts = it.split(":")
                    parts[0].trim().toInt() to parts[1].trim()
                }
            Prefs.tgwsDcMappings = mappings
            
            viewModel.triggerRefreshMenuList()
            dismiss()
        }

        return binding.root
    }

    override fun getHeightRatio(): Float = 0.7f

    companion object {
        const val TAG = "TgwsBottomSheet"
    }
}

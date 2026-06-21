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

    private val PRESETS = listOf(
        "Официальные IP" to "1:149.154.175.50\n2:149.154.167.51\n3:149.154.175.100\n4:149.154.167.91\n5:91.108.56.100\n203:91.105.192.100",
        "Альтернативные IP 1" to "1:149.154.175.53\n2:149.154.167.50\n3:149.154.175.101\n4:149.154.167.92\n5:91.108.56.116\n203:91.105.192.100",
        "Альтернативные IP 2" to "1:185.76.151.1\n2:185.76.151.2\n3:185.76.151.3\n4:185.76.151.4\n5:185.76.151.5\n203:185.76.151.203",
        "Ростелеком" to "2:149.154.167.220\n4:149.154.167.220",
        "Свой вариант" to ""
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = TgwsBottomSheetBinding.inflate(inflater, container, false)

        binding.swEnabled.isChecked = Prefs.tgwsEnabled
        binding.swUseByeDpi.isChecked = Prefs.tgwsUseByeDpi
        binding.etPort.setText(Prefs.tgwsPort.toString())
        
        val mappingStr = Prefs.tgwsDcMappings.map { "${it.key}:${it.value}" }.joinToString("\n")
        binding.etMappings.setText(mappingStr)
        configureMultilineEditTextScrollEvent(binding.etMappings)

        val adapter = android.widget.ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            PRESETS.map { it.first }
        )
        binding.spPresets.adapter = adapter
        
        // Find matching preset
        val matchIndex = PRESETS.indexOfFirst { it.second == mappingStr }
        if (matchIndex != -1) {
            binding.spPresets.setSelection(matchIndex)
        } else {
            binding.spPresets.setSelection(PRESETS.size - 1)
        }

        binding.spPresets.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position != PRESETS.size - 1) {
                    binding.etMappings.setText(PRESETS[position].second)
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        binding.btnSave.setOnClickListener {
            Prefs.tgwsEnabled = binding.swEnabled.isChecked
            Prefs.tgwsUseByeDpi = binding.swUseByeDpi.isChecked
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

    override fun getHeightRatio(): Float = -1f

    companion object {
        const val TAG = "TgwsBottomSheet"
    }
}

package ru.toinet.android.ui.connect

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import ru.toinet.android.databinding.TgwsBottomSheetBinding
import ru.toinet.android.ui.ToInetBottomSheetDialogFragment
import ru.toinet.android.util.Prefs
import ru.toinet.android.util.putPref
import ru.toinet.android.util.syncStandaloneComponent
import android.widget.Toast
import java.security.SecureRandom

class TgwsBottomSheet : ToInetBottomSheetDialogFragment() {

    private lateinit var binding: TgwsBottomSheetBinding
    private val viewModel: ConnectViewModel by activityViewModels()

    private val PRESETS = listOf(
        "Официальные IP" to "1:149.154.175.50\n2:149.154.167.51\n3:149.154.175.100\n4:149.154.167.91\n5:149.154.171.5\n203:91.105.192.100",
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
        binding.etPort.setText(Prefs.tgwsPort.toString())
        
        binding.etSecret.setText(Prefs.tgwsSecret)
        binding.etFakeTls.setText(Prefs.tgwsFakeTls)
        binding.etCfWorkerDomains.setText(Prefs.tgwsCfWorkerDomains.joinToString(","))
        binding.etCfProxyDomains.setText(Prefs.tgwsCfProxyDomains.joinToString(","))
        binding.etFrontingSni.setText(Prefs.tgwsFrontingSni)
        binding.swFallbackCfProxy.isChecked = Prefs.tgwsFallbackCfProxy
        binding.swCfProxyH2Media.isChecked = Prefs.tgwsCfProxyH2Media
        binding.swDisableSecure.isChecked = Prefs.tgwsDisableSecure
        binding.swForceTestDc.isChecked = Prefs.tgwsForceTestDc
        
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

        
        binding.btnGenerateSecret.setOnClickListener {
            val randomBytes = ByteArray(16)
            SecureRandom().nextBytes(randomBytes)
            val randomHex = randomBytes.joinToString("") { "%02x".format(it) }
            
            val fakeTls = binding.etFakeTls.text.toString().trim()
            if (fakeTls.isNotEmpty()) {
                val domainHex = fakeTls.toByteArray().joinToString("") { "%02x".format(it) }
                binding.etSecret.setText("ee$randomHex$domainHex")
            } else {
                binding.etSecret.setText("dd$randomHex")
            }
            Toast.makeText(requireContext(), "Секрет сгенерирован", Toast.LENGTH_SHORT).show()
        }

        binding.btnSave.setOnClickListener {
            val newEnabled = binding.swEnabled.isChecked
            val oldPort = Prefs.tgwsPort
            val oldSecret = Prefs.tgwsSecret
            val oldFakeTls = Prefs.tgwsFakeTls
            val oldCfWorkers = Prefs.tgwsCfWorkerDomains
            val oldCfProxies = Prefs.tgwsCfProxyDomains
            val oldFrontingSni = Prefs.tgwsFrontingSni
            val oldFallbackCfProxy = Prefs.tgwsFallbackCfProxy
            val oldCfProxyH2Media = Prefs.tgwsCfProxyH2Media
            val oldDisableSecure = Prefs.tgwsDisableSecure
            val oldForceTestDc = Prefs.tgwsForceTestDc
            val oldMappings = Prefs.tgwsDcMappings

            Prefs.tgwsEnabled = newEnabled
            val port = binding.etPort.text.toString().toIntOrNull() ?: 1480
            Prefs.tgwsPort = port
            
            Prefs.tgwsSecret = binding.etSecret.text.toString().trim()
            Prefs.tgwsFakeTls = binding.etFakeTls.text.toString().trim()
            Prefs.tgwsCfWorkerDomains = binding.etCfWorkerDomains.text.toString().split(",").map { it.trim() }.filter { it.isNotBlank() }
            Prefs.tgwsCfProxyDomains = binding.etCfProxyDomains.text.toString().split(",").map { it.trim() }.filter { it.isNotBlank() }
            Prefs.tgwsFrontingSni = binding.etFrontingSni.text.toString().trim()
            Prefs.tgwsFallbackCfProxy = binding.swFallbackCfProxy.isChecked
            Prefs.tgwsCfProxyH2Media = binding.swCfProxyH2Media.isChecked
            Prefs.tgwsDisableSecure = binding.swDisableSecure.isChecked
            Prefs.tgwsForceTestDc = binding.swForceTestDc.isChecked
            
            val mappings = binding.etMappings.text.toString().split("\n")
                .filter { it.contains(":") }
                .mapNotNull {
                    val parts = it.split(":", limit = 2)
                    if (parts.size == 2) {
                        val dc = parts[0].trim().toIntOrNull()
                        if (dc != null) dc to parts[1].trim() else null
                    } else null
                }.toMap()
            Prefs.tgwsDcMappings = mappings

            val configChanged = (oldPort != Prefs.tgwsPort) ||
                (oldSecret != Prefs.tgwsSecret) ||
                (oldFakeTls != Prefs.tgwsFakeTls) ||
                (oldCfWorkers != Prefs.tgwsCfWorkerDomains) ||
                (oldCfProxies != Prefs.tgwsCfProxyDomains) ||
                (oldFrontingSni != Prefs.tgwsFrontingSni) ||
                (oldFallbackCfProxy != Prefs.tgwsFallbackCfProxy) ||
                (oldCfProxyH2Media != Prefs.tgwsCfProxyH2Media) ||
                (oldDisableSecure != Prefs.tgwsDisableSecure) ||
                (oldForceTestDc != Prefs.tgwsForceTestDc) ||
                (oldMappings != Prefs.tgwsDcMappings)

            requireContext().syncStandaloneComponent("tgws", newEnabled, configChanged)
            
            viewModel.triggerRefreshMenuList()
            dismiss()
        }

        binding.tvLogs.movementMethod = android.text.method.ScrollingMovementMethod()
        val existingLogs = synchronized(ru.toinet.android.tgws.TgwsService.logHistory) {
            ru.toinet.android.tgws.TgwsService.logHistory.toList()
        }
        if (existingLogs.isNotEmpty()) {
            binding.tvLogs.text = existingLogs.joinToString("\n") + "\n"
            binding.tvLogs.post {
                val layout = binding.tvLogs.layout
                if (layout != null) {
                    val scrollAmount = layout.getLineTop(binding.tvLogs.lineCount) - binding.tvLogs.height
                    if (scrollAmount > 0) {
                        binding.tvLogs.scrollTo(0, scrollAmount)
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launchWhenStarted {
            ru.toinet.android.tgws.TgwsService.logs.collect { logMsg ->
                if (binding.tvLogs.text.toString().trim() == "Ожидание логов...") {
                    binding.tvLogs.text = ""
                }
                binding.tvLogs.append("$logMsg\n")
                binding.tvLogs.post {
                    val layout = binding.tvLogs.layout
                    if (layout != null) {
                        val scrollAmount = layout.getLineTop(binding.tvLogs.lineCount) - binding.tvLogs.height
                        if (scrollAmount > 0) {
                            binding.tvLogs.scrollTo(0, scrollAmount)
                        }
                    }
                }
            }
        }

        return binding.root
    }

    override fun getHeightRatio(): Float = -1f

    companion object {
        const val TAG = "TgwsBottomSheet"
    }
}

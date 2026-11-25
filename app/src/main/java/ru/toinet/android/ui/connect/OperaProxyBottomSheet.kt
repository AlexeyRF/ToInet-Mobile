package ru.toinet.android.ui.connect

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import ru.toinet.android.databinding.OperaProxyBottomSheetBinding
import ru.toinet.android.ui.ToInetBottomSheetDialogFragment
import ru.toinet.android.util.Prefs
import ru.toinet.android.util.syncStandaloneComponent
import android.os.Build
import android.widget.Toast

class OperaProxyBottomSheet : ToInetBottomSheetDialogFragment() {

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
        binding.etPreset.setText(Prefs.operaProxyPreset)
        binding.swVerbose.isChecked = Prefs.operaProxyVerbose
        
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
            val oldBind = Prefs.operaProxyBindAddress
            val oldUpstream = Prefs.operaProxyUpstream
            val oldPreset = Prefs.operaProxyPreset
            val oldVerbose = Prefs.operaProxyVerbose

            val newEnabled = binding.swEnabled.isChecked
            Prefs.operaProxyEnabled = newEnabled
            Prefs.operaProxyBindAddress = binding.etBindAddress.text.toString()
            Prefs.operaProxyUpstream = binding.etUpstream.text.toString()
            Prefs.operaProxyPreset = binding.etPreset.text.toString().trim().ifEmpty { "random" }
            Prefs.operaProxyVerbose = binding.swVerbose.isChecked

            val configChanged = (oldBind != Prefs.operaProxyBindAddress) ||
                (oldUpstream != Prefs.operaProxyUpstream) ||
                (oldPreset != Prefs.operaProxyPreset) ||
                (oldVerbose != Prefs.operaProxyVerbose)

            requireContext().syncStandaloneComponent("operaproxy", newEnabled, configChanged)
            viewModel.triggerRefreshMenuList()
            dismiss()
        }

        binding.tvLogs.movementMethod = android.text.method.ScrollingMovementMethod()
        val existingLogs = synchronized(ru.toinet.android.operaproxy.OperaProxyServer.logHistory) {
            ru.toinet.android.operaproxy.OperaProxyServer.logHistory.toList()
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

        lifecycleScope.launchWhenStarted {
            ru.toinet.android.operaproxy.OperaProxyServer.logs.collect { logMsg ->
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
        const val TAG = "OperaProxyBottomSheet"
    }
}

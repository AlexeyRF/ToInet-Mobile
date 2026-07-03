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
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import java.security.SecureRandom

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
        /*
        binding.etSecret.setText(Prefs.tgwsSecret)
        binding.etFakeTls.setText(Prefs.tgwsFakeTls)
        */
        
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

        /*
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

        binding.btnCopyLinks.setOnClickListener {
            val port = binding.etPort.text.toString().toIntOrNull() ?: 1480
            val secret = binding.etSecret.text.toString().trim()
            val socksLink = "tg://socks?server=127.0.0.1&port=$port"
            
            if (secret.isNotEmpty()) {
                val mtprotoLink = "tg://proxy?server=127.0.0.1&port=$port&secret=$secret"
                androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle("Какую ссылку скопировать?")
                    .setItems(arrayOf("MTProto", "SOCKS5")) { _, which ->
                        val link = if (which == 0) mtprotoLink else socksLink
                        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("TG Proxy", link))
                        Toast.makeText(requireContext(), "Ссылка скопирована!", Toast.LENGTH_SHORT).show()
                    }
                    .show()
            } else {
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("TG Proxy", socksLink))
                Toast.makeText(requireContext(), "SOCKS5 ссылка скопирована!", Toast.LENGTH_SHORT).show()
            }
        }
        */

        binding.btnSave.setOnClickListener {
            Prefs.tgwsEnabled = binding.swEnabled.isChecked
            Prefs.tgwsUseByeDpi = binding.swUseByeDpi.isChecked
            val port = binding.etPort.text.toString().toIntOrNull() ?: 1480
            Prefs.tgwsPort = port
            /*
            Prefs.tgwsSecret = binding.etSecret.text.toString().trim()
            Prefs.tgwsFakeTls = binding.etFakeTls.text.toString().trim()
            */
            
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

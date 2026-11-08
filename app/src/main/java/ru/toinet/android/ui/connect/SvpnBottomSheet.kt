package ru.toinet.android.ui.connect

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.toinet.android.databinding.SvpnBottomSheetBinding
import ru.toinet.android.svpn.SshTunnel
import ru.toinet.android.svpn.SvpnService
import ru.toinet.android.ui.ToInetBottomSheetDialogFragment
import ru.toinet.android.util.Prefs

class SvpnBottomSheet : ToInetBottomSheetDialogFragment() {

    private lateinit var binding: SvpnBottomSheetBinding
    private val viewModel: ConnectViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = SvpnBottomSheetBinding.inflate(inflater, container, false)

        binding.swSvpnEnabled.isChecked = Prefs.svpnEnabled
        binding.etHost.setText(Prefs.svpnHost)
        binding.etPort.setText(Prefs.svpnPort.toString())
        binding.etUser.setText(Prefs.svpnUser)

        val isKeyAuth = Prefs.svpnAuthType == "key"
        if (isKeyAuth) {
            binding.rbAuthKey.isChecked = true
            binding.tilPassword.visibility = View.GONE
            binding.tilPrivateKey.visibility = View.VISIBLE
            binding.tilKeyPassphrase.visibility = View.VISIBLE
        } else {
            binding.rbAuthPassword.isChecked = true
            binding.tilPassword.visibility = View.VISIBLE
            binding.tilPrivateKey.visibility = View.GONE
            binding.tilKeyPassphrase.visibility = View.GONE
        }

        binding.etPassword.setText(Prefs.svpnPassword)
        binding.etPrivateKey.setText(Prefs.svpnPrivateKey)
        binding.etKeyPassphrase.setText(Prefs.svpnKeyPassphrase)

        binding.etLocalPort.setText(Prefs.svpnLocalPort.toString())
        binding.etDnsServer.setText(Prefs.svpnDnsServer)
        binding.etDnsPort.setText(Prefs.svpnDnsPort.toString())

        binding.rgAuthType.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == binding.rbAuthKey.id) {
                binding.tilPassword.visibility = View.GONE
                binding.tilPrivateKey.visibility = View.VISIBLE
                binding.tilKeyPassphrase.visibility = View.VISIBLE
            } else {
                binding.tilPassword.visibility = View.VISIBLE
                binding.tilPrivateKey.visibility = View.GONE
                binding.tilKeyPassphrase.visibility = View.GONE
            }
        }

        binding.btnTestConnection.setOnClickListener {
            val host = binding.etHost.text.toString().trim()
            if (host.isEmpty()) {
                binding.tvTestStatus.visibility = View.VISIBLE
                binding.tvTestStatus.setTextColor(0xFFFF4444.toInt())
                binding.tvTestStatus.text = "Укажите адрес SSH сервера"
                return@setOnClickListener
            }

            val port = binding.etPort.text.toString().trim().toIntOrNull() ?: 22
            val user = binding.etUser.text.toString().trim().ifBlank { "root" }
            val isKey = binding.rbAuthKey.isChecked
            val password = if (!isKey) binding.etPassword.text.toString() else null
            val privateKey = if (isKey) binding.etPrivateKey.text.toString().trim() else null
            val passphrase = if (isKey) binding.etKeyPassphrase.text.toString() else null

            binding.btnTestConnection.isEnabled = false
            binding.tvTestStatus.visibility = View.VISIBLE
            binding.tvTestStatus.setTextColor(0xFF888888.toInt())
            binding.tvTestStatus.text = "Проверка соединения..."

            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val (success, message) = SshTunnel.testConnection(
                    host = host,
                    port = port,
                    user = user,
                    password = password,
                    privateKey = privateKey,
                    passphrase = passphrase,
                    timeoutMs = 12000
                )

                withContext(Dispatchers.Main) {
                    binding.btnTestConnection.isEnabled = true
                    binding.tvTestStatus.text = message
                    if (success) {
                        binding.tvTestStatus.setTextColor(0xFF4CAF50.toInt())
                    } else {
                        binding.tvTestStatus.setTextColor(0xFFFF4444.toInt())
                    }
                }
            }
        }

        binding.btnSave.setOnClickListener {
            val oldEnabled = Prefs.svpnEnabled
            val newEnabled = binding.swSvpnEnabled.isChecked

            Prefs.svpnEnabled = newEnabled
            Prefs.svpnHost = binding.etHost.text.toString().trim()
            Prefs.svpnPort = binding.etPort.text.toString().trim().toIntOrNull() ?: 22
            Prefs.svpnUser = binding.etUser.text.toString().trim().ifBlank { "root" }
            Prefs.svpnAuthType = if (binding.rbAuthKey.isChecked) "key" else "password"
            Prefs.svpnPassword = binding.etPassword.text.toString()
            Prefs.svpnPrivateKey = binding.etPrivateKey.text.toString().trim()
            Prefs.svpnKeyPassphrase = binding.etKeyPassphrase.text.toString()

            val localPort = binding.etLocalPort.text.toString().trim().toIntOrNull() ?: 1770
            Prefs.svpnLocalPort = localPort

            val dnsServer = binding.etDnsServer.text.toString().trim().ifBlank { "77.88.8.8" }
            Prefs.svpnDnsServer = dnsServer
            Prefs.svpnDnsPort = binding.etDnsPort.text.toString().trim().toIntOrNull() ?: 53

            if (!Prefs.isGlobalVpnEnabled) {
                if (newEnabled) {
                    SvpnService.start(requireContext())
                } else if (oldEnabled) {
                    SvpnService.stop(requireContext())
                }
            }

            viewModel.triggerRefreshMenuList()
            Toast.makeText(requireContext(), "Настройки SVPN сохранены", Toast.LENGTH_SHORT).show()
            dismiss()
        }

        return binding.root
    }

    override fun getHeightRatio(): Float = -1f

    companion object {
        const val TAG = "SvpnBottomSheet"
    }
}

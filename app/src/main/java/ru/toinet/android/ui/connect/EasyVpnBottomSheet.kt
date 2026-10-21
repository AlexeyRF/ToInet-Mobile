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
import ru.toinet.android.databinding.EasyvpnBottomSheetBinding
import ru.toinet.android.easyvpn.EasyVpnUpdater
import ru.toinet.android.ui.ToInetBottomSheetDialogFragment
import ru.toinet.android.util.Prefs
import ru.toinet.android.util.sendIntentToService
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class EasyVpnBottomSheet : ToInetBottomSheetDialogFragment() {

    private lateinit var binding: EasyvpnBottomSheetBinding
    private val viewModel: ConnectViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = EasyvpnBottomSheetBinding.inflate(inflater, container, false)

        binding.swEasyVpnProxyEnabled.isChecked = Prefs.easyVpnProxyEnabled
        binding.swEasyVpnNumberOnly.isChecked = Prefs.easyVpnServerNumberOnly
        binding.etEasyVpnSubs.setText(Prefs.easyVpnSubs)

        updateTimestampHeader()

        binding.btnEasyVpnUpdateNow.setOnClickListener {
            binding.btnEasyVpnUpdateNow.isEnabled = false
            binding.btnEasyVpnUpdateNow.text = "Обновление..."
            val appContext = requireContext().applicationContext
            lifecycleScope.launch(Dispatchers.IO) {
                val currentSubs = withContext(Dispatchers.Main) {
                    binding.etEasyVpnSubs.text.toString().trim()
                }
                Prefs.easyVpnSubs = currentSubs

                val nodes = EasyVpnUpdater.updateAndTestConfigs(appContext)
                withContext(Dispatchers.Main) {
                    if (!isAdded || context == null) return@withContext
                    binding.btnEasyVpnUpdateNow.isEnabled = true
                    binding.btnEasyVpnUpdateNow.text = "Обновить подписки сейчас"
                    if (nodes.isNotEmpty()) {
                        Toast.makeText(requireContext(), "Подписки успешно обновлены (${nodes.size} серверов)", Toast.LENGTH_SHORT).show()
                        updateTimestampHeader()
                    } else {
                        Toast.makeText(requireContext(), "Не удалось обновить подписки", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        binding.btnSave.setOnClickListener {
            val wasEnabled = Prefs.easyVpnProxyEnabled
            val newEnabled = binding.swEasyVpnProxyEnabled.isChecked

            Prefs.easyVpnProxyEnabled = newEnabled
            Prefs.easyVpnServerNumberOnly = binding.swEasyVpnNumberOnly.isChecked
            Prefs.easyVpnSubs = binding.etEasyVpnSubs.text.toString().trim()

            if (newEnabled && !wasEnabled) {
                if (!Prefs.isGlobalVpnEnabled || Prefs.vpnProvider != "easyvpn") {
                    ru.toinet.android.easyvpn.EasyVpnService.start(requireContext())
                }
            } else if (newEnabled) {
                requireContext().sendIntentToService("ACTIVE")
            } else if (wasEnabled) {
                ru.toinet.android.easyvpn.EasyVpnService.stop(requireContext())
            }

            viewModel.triggerRefreshMenuList()
            dismiss()
        }

        return binding.root
    }

    private fun updateTimestampHeader() {
        var lastUpdate = Prefs.lastEasyVpnUpdate
        if (lastUpdate <= 0L) {
            val file = File(requireContext().filesDir, "easyvpn_user_nodes.json")
            if (file.exists()) {
                try {
                    val json = org.json.JSONObject(file.readText())
                    val tsSec = json.optDouble("timestamp", 0.0)
                    if (tsSec > 0) {
                        lastUpdate = (tsSec * 1000).toLong()
                        Prefs.lastEasyVpnUpdate = lastUpdate
                    }
                } catch (_: Exception) {}
            }
        }

        if (lastUpdate > 0L) {
            val sdf = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
            binding.tvEasyVpnSubsHeader.text = "Подписки обновлены: ${sdf.format(Date(lastUpdate))}"
        } else {
            binding.tvEasyVpnSubsHeader.text = "Ссылки на подписки"
        }
    }

    override fun getHeightRatio(): Float = -1f

    companion object {
        const val TAG = "EasyVpnBottomSheet"
    }
}

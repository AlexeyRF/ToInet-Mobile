package ru.toinet.android.ui.connect

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import ru.toinet.android.databinding.RehabilitatorBottomSheetBinding
import ru.toinet.android.ui.OrbotBottomSheetDialogFragment
import ru.toinet.android.util.Prefs

class RehabilitatorBottomSheet : OrbotBottomSheetDialogFragment() {

    private lateinit var binding: RehabilitatorBottomSheetBinding
    private val viewModel: ConnectViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = RehabilitatorBottomSheetBinding.inflate(inflater, container, false)

        binding.swEnabled.isChecked = Prefs.rehabilitatorEnabled
        binding.etUpstreamHost.setText(Prefs.rehabilitatorUpstreamHost)
        binding.etUpstreamPort.setText(Prefs.rehabilitatorUpstreamPort.toString())
        binding.etUsername.setText(Prefs.rehabilitatorUsername)
        binding.etPassword.setText(Prefs.rehabilitatorPassword)
        binding.etArgs.setText(Prefs.rehabilitatorArgs)

        binding.btnSave.setOnClickListener {
            Prefs.rehabilitatorEnabled = binding.swEnabled.isChecked
            Prefs.rehabilitatorUpstreamHost = binding.etUpstreamHost.text.toString()
            Prefs.rehabilitatorUpstreamPort = binding.etUpstreamPort.text.toString().toIntOrNull() ?: 1080
            Prefs.rehabilitatorUsername = binding.etUsername.text.toString()
            Prefs.rehabilitatorPassword = binding.etPassword.text.toString()
            Prefs.rehabilitatorArgs = binding.etArgs.text.toString()
            
            viewModel.triggerRefreshMenuList()
            dismiss()
        }

        val testActivityLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                val strategy = result.data?.getStringExtra("strategy")
                if (strategy != null) {
                    binding.etArgs.setText(strategy)
                }
            }
        }

        binding.btnTestStrategy.setOnClickListener {
            val intent = android.content.Intent(requireContext(), ru.toinet.android.byedpi.ui.TestActivity::class.java)
            testActivityLauncher.launch(intent)
        }

        return binding.root
    }

    override fun getHeightRatio(): Float = -1f

    companion object {
        const val TAG = "RehabilitatorBottomSheet"
    }
}

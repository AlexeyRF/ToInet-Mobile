package ru.toinet.android.ui.connect

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import ru.toinet.android.databinding.ByedpiBottomSheetBinding
import ru.toinet.android.ui.OrbotBottomSheetDialogFragment
import ru.toinet.android.util.Prefs

class ByeDpiBottomSheet : OrbotBottomSheetDialogFragment() {

    private lateinit var binding: ByedpiBottomSheetBinding
    private val viewModel: ConnectViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = ByedpiBottomSheetBinding.inflate(inflater, container, false)

        binding.swEnabled.isChecked = Prefs.byedpiEnabled
        binding.etArgs.setText(Prefs.byedpiArgs)
        configureMultilineEditTextScrollEvent(binding.etArgs)

        binding.btnSave.setOnClickListener {
            Prefs.byedpiEnabled = binding.swEnabled.isChecked
            Prefs.byedpiArgs = binding.etArgs.text.toString().trim()
            Prefs.byedpiEnableCmdSettings = true
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

        binding.btnTest.setOnClickListener {
            val intent = android.content.Intent(requireContext(), ru.toinet.android.byedpi.ui.TestActivity::class.java)
            testActivityLauncher.launch(intent)
        }

        return binding.root
    }

    override fun getHeightRatio(): Float = -1f

    companion object {
        const val TAG = "ByeDpiBottomSheet"
    }
}

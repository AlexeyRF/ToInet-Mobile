package ru.toinet.android.ui.connect

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import ru.toinet.android.databinding.FakeVpnBottomSheetBinding
import ru.toinet.android.ui.OrbotBottomSheetDialogFragment
import ru.toinet.android.util.Prefs

class FakeVpnBottomSheet : OrbotBottomSheetDialogFragment() {

    private lateinit var binding: FakeVpnBottomSheetBinding
    private val viewModel: ConnectViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FakeVpnBottomSheetBinding.inflate(inflater, container, false)

        binding.swFakeTlsEnabled.isChecked = Prefs.fakeVpnFakeTlsEnabled
        binding.etDomains.setText(Prefs.fakeVpnFakeTlsDomains)

        binding.btnSave.setOnClickListener {
            Prefs.fakeVpnFakeTlsEnabled = binding.swFakeTlsEnabled.isChecked
            Prefs.fakeVpnFakeTlsDomains = binding.etDomains.text.toString().trim()
            viewModel.triggerRefreshMenuList()
            dismiss()
        }

        return binding.root
    }

    override fun getHeightRatio(): Float = -1f

    companion object {
        const val TAG = "FakeVpnBottomSheet"
    }
}

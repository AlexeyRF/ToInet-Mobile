package ru.toinet.android.ui.connect

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import ru.toinet.android.databinding.TurnProxyBottomSheetBinding
import ru.toinet.android.ui.OrbotBottomSheetDialogFragment
import ru.toinet.android.util.Prefs

class TurnProxyBottomSheet : OrbotBottomSheetDialogFragment() {

    private lateinit var binding: TurnProxyBottomSheetBinding
    private val viewModel: ConnectViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = TurnProxyBottomSheetBinding.inflate(inflater, container, false)

        binding.swEnabled.isChecked = Prefs.turnProxyEnabled
        binding.swUseByeDpi.isChecked = Prefs.turnProxyUseByeDpi
        binding.swRawMode.isChecked = Prefs.turnProxyRawMode
        binding.etRawCommand.setText(Prefs.turnProxyRawCommand)
        binding.etProvider.setText(Prefs.turnProxyProvider)
        binding.etVkLink.setText(Prefs.turnProxyVkLink)
        binding.etServerAddr.setText(Prefs.turnProxyServerAddr)
        binding.etLocalPort.setText(Prefs.turnProxyLocalPort.toString())
        binding.etThreads.setText(Prefs.turnProxyThreads.toString())
        binding.etStreamsPerCred.setText(Prefs.turnProxyStreamsPerCred.toString())
        binding.swTcpForward.isChecked = Prefs.turnProxyTcpForward
        binding.swBond.isChecked = Prefs.turnProxyBond
        binding.swUseUdp.isChecked = Prefs.turnProxyUseUdp
        binding.etObfProfile.setText(Prefs.turnProxyObfProfile)
        binding.etObfKey.setText(Prefs.turnProxyObfKey)
        binding.swManualCaptcha.isChecked = Prefs.turnProxyManualCaptcha
        binding.etBrowser.setText(Prefs.turnProxyBrowser)
        binding.etDnsServers.setText(Prefs.turnProxyDnsServers)
        binding.etDnsMode.setText(Prefs.turnProxyDnsMode)
        binding.etMagicTurn.setText(Prefs.turnProxyMagicTurn)
        binding.etClientId.setText(Prefs.turnProxyClientId)

        binding.btnSave.setOnClickListener {
            Prefs.turnProxyEnabled = binding.swEnabled.isChecked
            Prefs.turnProxyUseByeDpi = binding.swUseByeDpi.isChecked
            Prefs.turnProxyRawMode = binding.swRawMode.isChecked
            Prefs.turnProxyRawCommand = binding.etRawCommand.text.toString()
            Prefs.turnProxyProvider = binding.etProvider.text.toString()
            Prefs.turnProxyVkLink = binding.etVkLink.text.toString()
            Prefs.turnProxyServerAddr = binding.etServerAddr.text.toString()
            Prefs.turnProxyLocalPort = binding.etLocalPort.text.toString().toIntOrNull() ?: 9000
            Prefs.turnProxyThreads = binding.etThreads.text.toString().toIntOrNull() ?: 12
            Prefs.turnProxyStreamsPerCred = binding.etStreamsPerCred.text.toString().toIntOrNull() ?: 6
            Prefs.turnProxyTcpForward = binding.swTcpForward.isChecked
            Prefs.turnProxyBond = binding.swBond.isChecked
            Prefs.turnProxyUseUdp = binding.swUseUdp.isChecked
            Prefs.turnProxyObfProfile = binding.etObfProfile.text.toString()
            Prefs.turnProxyObfKey = binding.etObfKey.text.toString()
            Prefs.turnProxyManualCaptcha = binding.swManualCaptcha.isChecked
            Prefs.turnProxyBrowser = binding.etBrowser.text.toString()
            Prefs.turnProxyDnsServers = binding.etDnsServers.text.toString()
            Prefs.turnProxyDnsMode = binding.etDnsMode.text.toString()
            Prefs.turnProxyMagicTurn = binding.etMagicTurn.text.toString()
            Prefs.turnProxyClientId = binding.etClientId.text.toString()
            
            viewModel.triggerRefreshMenuList()
            dismiss()
        }

        return binding.root
    }

    override fun getHeightRatio(): Float = -1f

    companion object {
        const val TAG = "TurnProxyBottomSheet"
    }
}

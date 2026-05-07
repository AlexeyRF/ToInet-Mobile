package ru.toinet.android.ui.connect

import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.CompoundButton
import android.widget.RadioButton
import androidx.navigation.fragment.NavHostFragment
import ru.toinet.android.R
import ru.toinet.android.databinding.ConfigConnectionBottomSheetBinding
import ru.toinet.android.ui.OrbotBottomSheetDialogFragment

class ConfigConnectionBottomSheet :
    OrbotBottomSheetDialogFragment(), CompoundButton.OnCheckedChangeListener,
    View.OnClickListener, View.OnKeyListener, View.OnFocusChangeListener,
    AdapterView.OnItemClickListener {

    private lateinit var binding: ConfigConnectionBottomSheetBinding

    private lateinit var radios: List<RadioButton>
    private lateinit var radioSubtitleMap: Map<CompoundButton, View>
    private lateinit var allSubtitles: List<View>

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        binding = ConfigConnectionBottomSheetBinding.inflate(inflater, container, false)


        radios = arrayListOf(
            binding.rbCustom
        )

        radioSubtitleMap = mapOf<CompoundButton, View>(
            binding.rbCustom to binding.tvCustomSubtitle
        )

        allSubtitles = arrayListOf(
            binding.tvCustomSubtitle
        )

        binding.customContainer.setOnClickListener { binding.rbCustom.isChecked = true }
        binding.tvCancel.setOnClickListener { dismiss() }

        binding.rbCustom.setOnCheckedChangeListener(this)

        binding.btnAction.setOnClickListener {
            if (binding.rbCustom.isChecked) {
                CustomBridgeBottomSheet().show(
                    requireActivity().supportFragmentManager,
                    CustomBridgeBottomSheet.TAG
                )
            }
        }

        return binding.root
    }

    override fun onCheckedChanged(buttonView: CompoundButton, isChecked: Boolean) {
        if (isChecked) {
            for (radio in radios) {
                if (radio != buttonView) radio.isChecked = false
            }

            radioSubtitleMap[buttonView]?.let {
                for (subtitle in allSubtitles) {
                    subtitle.visibility = if (subtitle == it) View.VISIBLE else View.GONE
                }
            }
        }

        binding.btnAction.text = getString(R.string.next)
    }

    override fun onClick(view: View?) {
    }

    override fun onKey(view: View?, keyCode: Int, event: KeyEvent?): Boolean {
        return false
    }

    override fun onFocusChange(view: View?, hasFocus: Boolean) {
    }

    override fun onItemClick(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
    }

    fun closeAndConnect() {
        dismiss()
        val navHostFragment =
            requireActivity().supportFragmentManager.fragments[0] as NavHostFragment
        val connectFrag = navHostFragment.childFragmentManager.fragments.last() as ConnectFragment
        if (connectFrag.viewModel.uiState == ConnectUiState.Off) {
            connectFrag.refreshMenuList(requireContext())
            connectFrag.stopTorAndVpn()
            Thread.sleep(3000)
        }
        connectFrag.attemptToStartTor()
    }
}
package ru.toinet.android.ui.connect

import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.CompoundButton
import android.widget.RadioButton
import androidx.core.widget.addTextChangedListener
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

        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext())
        binding.swByeDpi.isChecked = ru.toinet.android.util.Prefs.byedpiEnabled
        binding.etByeDpiArgs.setText(prefs.getString("byedpi_cmd_args", ""))

        binding.swByeDpi.setOnCheckedChangeListener { _, isChecked ->
            ru.toinet.android.util.Prefs.byedpiEnabled = isChecked
        }

        binding.etByeDpiArgs.addTextChangedListener {
            prefs.edit()
                .putString("byedpi_cmd_args", it.toString())
                .apply()
        }

        radios = arrayListOf(
            binding.rbCustom,
            binding.rbByeDpi
        )

        radioSubtitleMap = mapOf<CompoundButton, View>(
            binding.rbCustom to binding.tvCustomSubtitle,
            binding.rbByeDpi to binding.tvByeDpiSubtitle
        )

        allSubtitles = arrayListOf(
            binding.tvCustomSubtitle,
            binding.tvByeDpiSubtitle
        )

        binding.customContainer.setOnClickListener { binding.rbCustom.isChecked = true }
        binding.byedpiContainer.setOnClickListener { binding.rbByeDpi.isChecked = true }
        binding.tvCancel.setOnClickListener { dismiss() }

        binding.rbCustom.setOnCheckedChangeListener(this)
        binding.rbByeDpi.setOnCheckedChangeListener(this)

        binding.btnAction.setOnClickListener {
            if (binding.rbCustom.isChecked) {
                CustomBridgeBottomSheet().show(
                    requireActivity().supportFragmentManager,
                    CustomBridgeBottomSheet.TAG
                )
            } else if (binding.rbByeDpi.isChecked) {
                ru.toinet.android.util.Prefs.transport = ru.toinet.android.service.circumvention.Transport.BYEDPI
                closeAndConnect()
            } else {
                ru.toinet.android.util.Prefs.transport = ru.toinet.android.service.circumvention.Transport.NONE
                closeAndConnect()
            }
        }

        initRadios()

        return binding.root
    }

    private fun initRadios() {
        when (ru.toinet.android.util.Prefs.transport) {
            ru.toinet.android.service.circumvention.Transport.CUSTOM -> binding.rbCustom.isChecked = true
            ru.toinet.android.service.circumvention.Transport.BYEDPI -> binding.rbByeDpi.isChecked = true
            else -> {
                // If direct, no bridge radio checked
            }
        }
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

        binding.btnAction.text = if (binding.rbCustom.isChecked) getString(R.string.next) else getString(R.string.connect)
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
        val activity = activity ?: return
        val navHostFragment = activity.supportFragmentManager.findFragmentById(R.id.nav_fragment) as? NavHostFragment
        val connectFrag = navHostFragment?.childFragmentManager?.fragments?.find { it is ConnectFragment } as? ConnectFragment
        
        dismiss()

        connectFrag?.let { frag ->
            if (frag.viewModel.uiState.value == ConnectUiState.Off) {
                frag.refreshMenuList(activity)
                frag.stopTorAndVpn()
                // Avoid sleeping on main thread if possible, but keeping it for now as per original logic if needed
                // Thread.sleep(3000) 
            }
            frag.attemptToStartTor()
        }
    }
}
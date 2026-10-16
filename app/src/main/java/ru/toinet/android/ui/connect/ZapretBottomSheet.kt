package ru.toinet.android.ui.connect

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.activityViewModels
import ru.toinet.android.R
import ru.toinet.android.databinding.ZapretBottomSheetBinding
import ru.toinet.android.ui.ToInetBottomSheetDialogFragment
import ru.toinet.android.util.Prefs
import ru.toinet.android.zapret.ui.ZapretTestActivity

class ZapretBottomSheet : ToInetBottomSheetDialogFragment() {

    private lateinit var binding: ZapretBottomSheetBinding
    private val viewModel: ConnectViewModel by activityViewModels()
    private var isProgrammaticChange = false

    private data class StrategyItem(val name: String, val args: String)

    private val FLOWSEAL_STRATEGIES = listOf(
        StrategyItem("general", "--split-pos=1"),
        StrategyItem("general (ALT)", "--split-pos=1 --disorder"),
        StrategyItem("general (ALT2)", "--split-pos=2"),
        StrategyItem("general (ALT3)", "--split-pos=2 --hostcase --domcase"),
        StrategyItem("general (ALT4)", "--split-pos=1 --disorder --oob"),
        StrategyItem("general (ALT5)", "--split-pos=2 --disorder"),
        StrategyItem("general (ALT6)", "--split-pos=1 --hostcase"),
        StrategyItem("general (ALT7)", "--split-pos=2,sniext+1"),
        StrategyItem("general (ALT8)", "--split-pos=2 --oob"),
        StrategyItem("general (ALT9)", "--split-pos=sni --hostcase"),
        StrategyItem("general (ALT10)", "--split-pos=sni --disorder"),
        StrategyItem("general (ALT11)", "--split-pos=1 --tlsrec=sni"),
        StrategyItem("general (ALT12)", "--split-pos=1 --tlsrec=sni --hostcase"),
        StrategyItem("general (ALT13)", "--split-pos=2 --tlsrec=sni --hostcase"),
        StrategyItem("general (EXP)", "--split-pos=midsld --disorder"),
        StrategyItem("general (FAKE TLS AUTO)", "--split-pos=1,midsld --disorder"),
        StrategyItem("general (FAKE TLS AUTO ALT)", "--split-pos=1 --disorder"),
        StrategyItem("general (FAKE TLS AUTO ALT2)", "--split-pos=1 --oob"),
        StrategyItem("general (FAKE TLS AUTO ALT3)", "--split-pos=1 --disorder --oob"),
        StrategyItem("general (SIMPLE FAKE)", "--split-pos=sni --hostcase"),
        StrategyItem("general (SIMPLE FAKE ALT)", "--split-pos=2 --disorder"),
        StrategyItem("general (SIMPLE FAKE ALT2)", "--split-pos=sni --disorder"),
        StrategyItem("Пользовательская", "")
    )

    private val testActivityLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val strategy = result.data?.getStringExtra("strategy")
            if (strategy != null) {
                binding.etArgs.setText(strategy)
                selectMatchingStrategy(strategy)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = ZapretBottomSheetBinding.inflate(inflater, container, false)

        binding.swEnabled.isChecked = Prefs.zapretEnabled
        binding.etPort.setText(Prefs.zapretPort.toString())
        binding.etArgs.setText(Prefs.zapretArgs)
        configureMultilineEditTextScrollEvent(binding.etArgs)

        val adapter = ArrayAdapter(
            requireContext(),
            R.layout.item_spinner,
            FLOWSEAL_STRATEGIES.map { it.name }
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spFlowseal.adapter = adapter

        selectMatchingStrategy(Prefs.zapretArgs)

        binding.spFlowseal.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position in 0 until FLOWSEAL_STRATEGIES.size - 1) {
                    val strat = FLOWSEAL_STRATEGIES[position]
                    if (binding.etArgs.text.toString().trim() != strat.args) {
                        isProgrammaticChange = true
                        binding.etArgs.setText(strat.args)
                        isProgrammaticChange = false
                    }
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        binding.etArgs.addTextChangedListener { s ->
            if (!isProgrammaticChange) {
                selectMatchingStrategy(s?.toString().orEmpty())
            }
        }

        binding.btnPresetSplit2.setOnClickListener {
            binding.etArgs.setText("--split-pos=2")
        }
        binding.btnPresetSplitSni.setOnClickListener {
            binding.etArgs.setText("--split-pos=sni")
        }
        binding.btnPresetDisorder.setOnClickListener {
            binding.etArgs.setText("--split-pos=2 --disorder")
        }
        binding.btnPresetHostcase.setOnClickListener {
            binding.etArgs.setText("--split-pos=2 --hostcase")
        }

        binding.btnTest.setOnClickListener {
            val intent = Intent(requireContext(), ZapretTestActivity::class.java)
            testActivityLauncher.launch(intent)
        }

        binding.btnSave.setOnClickListener {
            Prefs.zapretEnabled = binding.swEnabled.isChecked
            Prefs.zapretPort = binding.etPort.text.toString().trim().toIntOrNull() ?: 1081
            Prefs.zapretArgs = binding.etArgs.text.toString().trim()
            viewModel.triggerRefreshMenuList()
            dismiss()
        }

        return binding.root
    }

    private fun selectMatchingStrategy(args: String) {
        val trimmed = args.trim()
        val index = FLOWSEAL_STRATEGIES.indexOfFirst { it.args == trimmed }
        if (index != -1) {
            binding.spFlowseal.setSelection(index)
        } else {
            binding.spFlowseal.setSelection(FLOWSEAL_STRATEGIES.size - 1)
        }
    }

    override fun getHeightRatio(): Float = -1f

    companion object {
        const val TAG = "ZapretBottomSheet"
    }
}

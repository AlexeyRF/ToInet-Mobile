package ru.toinet.android.ui.connect

import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import ru.toinet.android.R
import ru.toinet.android.ui.ToInetBottomSheetDialogFragment
import ru.toinet.android.util.Prefs
import ru.toinet.android.util.sendIntentToService
import ru.toinet.android.vpn.rules.ProxifierRule
import ru.toinet.android.vpn.rules.ProxifierRulesManager

class ProxifierRulesBottomSheet : ToInetBottomSheetDialogFragment() {

    private val viewModel: ConnectViewModel by activityViewModels()
    private val rulesList = mutableListOf<ProxifierRule>()
    private lateinit var adapter: RulesAdapter
    private var defaultAction = ProxifierRule.ACTION_DIRECT

    override fun getHeightRatio(): Float = -1f

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.proxifier_rules_bottom_sheet, container, false)

        val swAppRouting = view.findViewById<MaterialSwitch>(R.id.swAppRouting)
        val btnStandardSettings = view.findViewById<ImageButton>(R.id.btnStandardSettings)
        val rvRules = view.findViewById<RecyclerView>(R.id.rvRules)
        val btnDefaultAction = view.findViewById<MaterialButton>(R.id.btnDefaultAction)
        val btnAddRule = view.findViewById<MaterialButton>(R.id.btnAddRule)
        val btnSave = view.findViewById<MaterialButton>(R.id.btnSave)

        swAppRouting.isChecked = Prefs.appRoutingEnabled
        swAppRouting.setOnCheckedChangeListener { _, isChecked ->
            Prefs.appRoutingEnabled = isChecked
            if (!isChecked) {
                Toast.makeText(requireContext(), "Правила отключены. Используется один выбранный VPN провайдер.", Toast.LENGTH_SHORT).show()
            }
        }

        btnStandardSettings.setOnClickListener {
            dismiss()
            VpnBottomSheet().show(parentFragmentManager, VpnBottomSheet.TAG)
        }

        defaultAction = Prefs.appRoutingDefaultAction
        btnDefaultAction.text = ProxifierRule.getActionTitle(defaultAction)
        btnDefaultAction.setOnClickListener {
            showActionPicker(it) { chosenAction ->
                defaultAction = chosenAction
                btnDefaultAction.text = ProxifierRule.getActionTitle(chosenAction)
            }
        }

        rulesList.clear()
        rulesList.addAll(ProxifierRulesManager.getRules())

        rvRules.layoutManager = LinearLayoutManager(requireContext())
        adapter = RulesAdapter()
        rvRules.adapter = adapter

        btnAddRule.setOnClickListener {
            showRuleEditor(null)
        }

        btnSave.setOnClickListener {
            Prefs.appRoutingEnabled = swAppRouting.isChecked
            Prefs.appRoutingDefaultAction = defaultAction
            ProxifierRulesManager.saveRules(rulesList)

            if (Prefs.isGlobalVpnEnabled) {
                requireContext().sendIntentToService("ACTIVE")
            }
            viewModel.triggerRefreshMenuList()
            Toast.makeText(requireContext(), "Правила сохранены", Toast.LENGTH_SHORT).show()
            dismiss()
        }

        return view
    }

    private fun showActionPicker(anchor: View, onChosen: (String) -> Unit) {
        val actions = ProxifierRulesManager.getAvailableActions()
        val popup = PopupMenu(requireContext(), anchor)
        actions.forEachIndexed { index, pair ->
            popup.menu.add(0, index, index, pair.second)
        }
        popup.setOnMenuItemClickListener { item ->
            val selected = actions[item.itemId].first
            onChosen(selected)
            true
        }
        popup.show()
    }

    private fun showRuleEditor(existingRule: ProxifierRule?) {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_edit_rule, null)
        val tvTitle = dialogView.findViewById<TextView>(R.id.tvDialogTitle)
        val etRuleName = dialogView.findViewById<EditText>(R.id.etRuleName)
        val btnChooseApps = dialogView.findViewById<MaterialButton>(R.id.btnChooseApps)
        val spAction = dialogView.findViewById<Spinner>(R.id.spAction)

        val isEdit = existingRule != null
        tvTitle.text = if (isEdit) "Редактирование правила" else "Новое правило"

        val selectedPackages = existingRule?.targetPackages?.toMutableList() ?: mutableListOf()
        etRuleName.setText(existingRule?.name ?: "")

        fun updateAppsButtonText() {
            btnChooseApps.text = if (selectedPackages.isEmpty()) {
                "Выбрать приложения (0 выбрано)"
            } else {
                "Приложений выбрано: ${selectedPackages.size}"
            }
        }
        updateAppsButtonText()

        btnChooseApps.setOnClickListener {
            AppSelectDialog(selectedPackages) { newSelection ->
                selectedPackages.clear()
                selectedPackages.addAll(newSelection)
                updateAppsButtonText()
            }.show(childFragmentManager, AppSelectDialog.TAG)
        }

        val availableActions = ProxifierRulesManager.getAvailableActions()
        val actionTitles = availableActions.map { it.second }
        val spinnerAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, actionTitles)
        spAction.adapter = spinnerAdapter

        val initialAction = existingRule?.action ?: ProxifierRule.ACTION_BYEDPI
        val initialIndex = availableActions.indexOfFirst { it.first == initialAction }.coerceAtLeast(0)
        spAction.setSelection(initialIndex)

        AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setPositiveButton("Сохранить") { _, _ ->
                val name = etRuleName.text.toString().trim().ifEmpty { "Правило" }
                val chosenAction = availableActions[spAction.selectedItemPosition].first

                if (existingRule != null) {
                    existingRule.name = name
                    existingRule.action = chosenAction
                    existingRule.targetPackages.clear()
                    existingRule.targetPackages.addAll(selectedPackages)
                } else {
                    val newRule = ProxifierRule(
                        name = name,
                        enabled = true,
                        targetPackages = selectedPackages,
                        action = chosenAction
                    )
                    rulesList.add(newRule)
                }
                adapter.notifyDataSetChanged()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    inner class RulesAdapter : RecyclerView.Adapter<RulesAdapter.VH>() {
        inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val cbEnabled: CheckBox = itemView.findViewById(R.id.cbEnabled)
            val llRuleInfo: View = itemView.findViewById(R.id.llRuleInfo)
            val tvRuleName: TextView = itemView.findViewById(R.id.tvRuleName)
            val tvApps: TextView = itemView.findViewById(R.id.tvApps)
            val btnAction: MaterialButton = itemView.findViewById(R.id.btnAction)
            val btnDelete: ImageButton = itemView.findViewById(R.id.btnDelete)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_proxifier_rule, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val rule = rulesList[position]
            holder.tvRuleName.text = rule.name
            holder.cbEnabled.isChecked = rule.enabled

            holder.cbEnabled.setOnCheckedChangeListener { _, isChecked ->
                rule.enabled = isChecked
            }

            val pm = holder.itemView.context.packageManager
            val appLabels = rule.targetPackages.take(3).map { pkg ->
                try {
                    val info = pm.getApplicationInfo(pkg, 0)
                    pm.getApplicationLabel(info).toString()
                } catch (_: Exception) {
                    pkg.substringAfterLast('.')
                }
            }
            val appsText = when {
                rule.targetPackages.isEmpty() -> "Приложения не выбраны"
                rule.targetPackages.size <= 3 -> appLabels.joinToString(", ")
                else -> "${appLabels.joinToString(", ")} +${rule.targetPackages.size - 3}"
            }
            holder.tvApps.text = appsText

            holder.btnAction.text = ProxifierRule.getActionTitle(rule.action).substringBefore(" (")
            holder.btnAction.setOnClickListener {
                showActionPicker(it) { newAction ->
                    rule.action = newAction
                    holder.btnAction.text = ProxifierRule.getActionTitle(newAction).substringBefore(" (")
                }
            }

            holder.llRuleInfo.setOnClickListener {
                showRuleEditor(rule)
            }

            holder.btnDelete.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    rulesList.removeAt(pos)
                    notifyItemRemoved(pos)
                }
            }
        }

        override fun getItemCount(): Int = rulesList.size
    }

    companion object {
        const val TAG = "ProxifierRulesBottomSheet"
    }
}

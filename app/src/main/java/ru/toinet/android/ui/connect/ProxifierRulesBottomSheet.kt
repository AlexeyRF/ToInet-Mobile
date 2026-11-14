package ru.toinet.android.ui.connect

import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.ItemTouchHelper
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
import java.util.Collections

class ProxifierRulesBottomSheet : ToInetBottomSheetDialogFragment() {

    private val viewModel: ConnectViewModel by activityViewModels()
    private val rulesList = mutableListOf<ProxifierRule>()
    private lateinit var adapter: RulesAdapter
    private lateinit var itemTouchHelper: ItemTouchHelper
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

        // Proxifier rules are now the main VPN operation mode
        swAppRouting.visibility = View.GONE
        swAppRouting.isChecked = true
        btnStandardSettings.visibility = View.GONE

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

        val touchCallback = object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val fromPos = viewHolder.bindingAdapterPosition
                val toPos = target.bindingAdapterPosition
                if (fromPos != RecyclerView.NO_POSITION && toPos != RecyclerView.NO_POSITION && fromPos != toPos) {
                    Collections.swap(rulesList, fromPos, toPos)
                    adapter.notifyItemMoved(fromPos, toPos)
                    adapter.notifyItemChanged(fromPos)
                    adapter.notifyItemChanged(toPos)
                }
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
        }
        itemTouchHelper = ItemTouchHelper(touchCallback)
        itemTouchHelper.attachToRecyclerView(rvRules)

        btnAddRule.setOnClickListener {
            showRuleEditor(null)
        }

        btnSave.setOnClickListener {
            Prefs.appRoutingEnabled = true
            Prefs.appRoutingDefaultAction = defaultAction
            ProxifierRulesManager.saveRules(rulesList)

            if (Prefs.isGlobalVpnEnabled) {
                requireContext().sendIntentToService("ACTIVE")
            }
            viewModel.triggerRefreshMenuList()
            Toast.makeText(requireContext(), "Правила и приоритеты сохранены", Toast.LENGTH_SHORT).show()
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
        val etCustomDomains = dialogView.findViewById<EditText>(R.id.etCustomDomains)
        val spAction = dialogView.findViewById<Spinner>(R.id.spAction)

        val isEdit = existingRule != null
        tvTitle.text = if (isEdit) "Редактирование правила" else "Новое правило"

        val selectedPackages = existingRule?.targetPackages?.toMutableList() ?: mutableListOf()
        etRuleName.setText(existingRule?.name ?: "")

        val initialDomains = selectedPackages.filter { it.startsWith("*") || (!it.startsWith("com.") && !it.startsWith("org.") && !it.startsWith("app.") && it.contains(".")) }
        etCustomDomains.setText(initialDomains.joinToString(", "))

        fun updateAppsButtonText() {
            val appCount = selectedPackages.count { !it.startsWith("*") && (it.startsWith("com.") || it.startsWith("org.") || it.startsWith("app.") || !it.contains(".")) }
            btnChooseApps.text = if (appCount == 0) {
                "Выбрать приложения (0 выбрано)"
            } else {
                "Приложений выбрано: $appCount"
            }
        }
        updateAppsButtonText()

        btnChooseApps.setOnClickListener {
            val pm = requireContext().packageManager
            val appPkgsOnly = selectedPackages.filter {
                !it.startsWith("*") && (it.startsWith("com.") || it.startsWith("org.") || it.startsWith("app.") || !it.contains("."))
            }.filter { pkg ->
                try {
                    pm.getApplicationInfo(pkg, 0)
                    true
                } catch (_: Exception) {
                    false
                }
            }.toMutableList()
            AppSelectDialog(appPkgsOnly) { newSelection ->
                val domains = etCustomDomains.text.toString().split(',', ' ', '\n').map { it.trim().lowercase() }.filter { it.isNotBlank() }
                selectedPackages.clear()
                selectedPackages.addAll(newSelection)
                selectedPackages.addAll(domains)
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

                val customDomains = etCustomDomains.text.toString()
                    .split(',', ' ', '\n')
                    .map { it.trim().lowercase() }
                    .filter { it.isNotBlank() }

                val finalTargets = selectedPackages.filter { !customDomains.contains(it) }.toMutableList()
                customDomains.forEach { if (!finalTargets.contains(it)) finalTargets.add(it) }

                if (existingRule != null) {
                    existingRule.name = name
                    existingRule.action = chosenAction
                    existingRule.targetPackages.clear()
                    existingRule.targetPackages.addAll(finalTargets)
                    val pos = rulesList.indexOf(existingRule)
                    if (pos != -1) {
                        adapter.notifyItemChanged(pos)
                    } else {
                        adapter.notifyDataSetChanged()
                    }
                } else {
                    val newRule = ProxifierRule(
                        name = name,
                        enabled = true,
                        targetPackages = finalTargets,
                        action = chosenAction
                    )
                    rulesList.add(newRule)
                    adapter.notifyItemInserted(rulesList.size - 1)
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    inner class RulesAdapter : RecyclerView.Adapter<RulesAdapter.VH>() {
        inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val btnMoveUp: ImageButton = itemView.findViewById(R.id.btnMoveUp)
            val btnMoveDown: ImageButton = itemView.findViewById(R.id.btnMoveDown)
            val cbEnabled: CheckBox = itemView.findViewById(R.id.cbEnabled)
            val llRuleInfo: View = itemView.findViewById(R.id.llRuleInfo)
            val tvRuleName: TextView = itemView.findViewById(R.id.tvRuleName)
            val tvApps: TextView = itemView.findViewById(R.id.tvApps)
            val btnAction: MaterialButton = itemView.findViewById(R.id.btnAction)
            val btnDelete: ImageButton = itemView.findViewById(R.id.btnDelete)
            val ivDragHandle: ImageView = itemView.findViewById(R.id.ivDragHandle)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_proxifier_rule, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val rule = rulesList[position]
            holder.tvRuleName.text = "#${position + 1}  ${rule.name}"
            holder.cbEnabled.setOnCheckedChangeListener(null)
            holder.cbEnabled.isChecked = rule.enabled
            holder.cbEnabled.setOnCheckedChangeListener { _, isChecked ->
                rule.enabled = isChecked
            }

            // Up / Down Priority buttons
            holder.btnMoveUp.visibility = if (position > 0) View.VISIBLE else View.INVISIBLE
            holder.btnMoveDown.visibility = if (position < rulesList.size - 1) View.VISIBLE else View.INVISIBLE

            holder.btnMoveUp.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos > 0) {
                    Collections.swap(rulesList, pos, pos - 1)
                    notifyItemMoved(pos, pos - 1)
                    notifyItemChanged(pos)
                    notifyItemChanged(pos - 1)
                }
            }

            holder.btnMoveDown.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION && pos < rulesList.size - 1) {
                    Collections.swap(rulesList, pos, pos + 1)
                    notifyItemMoved(pos, pos + 1)
                    notifyItemChanged(pos)
                    notifyItemChanged(pos + 1)
                }
            }

            holder.ivDragHandle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    itemTouchHelper.startDrag(holder)
                }
                false
            }

            val pm = holder.itemView.context.packageManager
            val installedOrDomainList = rule.targetPackages.mapNotNull { item ->
                if (item.contains('.') && (item.startsWith("com.") || item.startsWith("org.") || item.startsWith("app."))) {
                    try {
                        val info = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                            pm.getApplicationInfo(item, PackageManager.ApplicationInfoFlags.of(0))
                        } else {
                            @Suppress("DEPRECATION")
                            pm.getApplicationInfo(item, 0)
                        }
                        pm.getApplicationLabel(info).toString()
                    } catch (_: Exception) {
                        null
                    }
                } else {
                    item
                }
            }
            val displayItems = installedOrDomainList.take(3)
            val appsText = when {
                installedOrDomainList.isEmpty() -> "Цели не выбраны"
                installedOrDomainList.size <= 3 -> displayItems.joinToString(", ")
                else -> "${displayItems.joinToString(", ")} +${installedOrDomainList.size - 3}"
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
                    notifyItemRangeChanged(pos, rulesList.size - pos)
                }
            }
        }

        override fun getItemCount(): Int = rulesList.size
    }

    companion object {
        const val TAG = "ProxifierRulesBottomSheet"
    }
}

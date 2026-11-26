package ru.toinet.android.ui.connect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch
import ru.toinet.android.R
import ru.toinet.android.ui.ToInetBottomSheetDialogFragment
import ru.toinet.android.util.ACTION_VPN_PROFILE_CHANGED
import ru.toinet.android.util.NetworkUtils
import ru.toinet.android.util.Prefs
import ru.toinet.android.util.sendIntentToService
import ru.toinet.android.util.setGlobalVpnEnabled
import ru.toinet.android.util.setSeparateVpnProfilesEnabled
import ru.toinet.android.vpn.rules.ProxifierRule
import ru.toinet.android.vpn.rules.ProxifierRulesManager
import ru.toinet.android.vpn.services.AppVpnService
import java.util.Collections

class ProxifierRulesBottomSheet : ToInetBottomSheetDialogFragment() {

    private val viewModel: ConnectViewModel by activityViewModels()
    private val rulesList = mutableListOf<ProxifierRule>()
    private lateinit var adapter: RulesAdapter
    private lateinit var itemTouchHelper: ItemTouchHelper
    private var defaultAction = ProxifierRule.ACTION_DIRECT
    private var selectedProfileKey = Prefs.VPN_PROFILE_UNIFIED
    private var isUpdatingVpnSwitch = false
    private var isUpdatingProfileUi = false
    private var swVpnEnabledView: MaterialSwitch? = null
    private var tvCurrentNetworkView: TextView? = null
    private var btnDefaultActionView: MaterialButton? = null

    private val profileChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_VPN_PROFILE_CHANGED) {
                updateCurrentNetworkBanner(Prefs.currentVpnNetworkType == Prefs.VPN_PROFILE_WIFI)
            }
        }
    }

    private val vpnPrepareLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val ctx = context ?: return@registerForActivityResult
        if (result.resultCode == AppCompatActivity.RESULT_OK) {
            ctx.setGlobalVpnEnabled(true)
            viewModel.triggerRefreshMenuList()
        } else {
            isUpdatingVpnSwitch = true
            swVpnEnabledView?.isChecked = false
            isUpdatingVpnSwitch = false
            ctx.setGlobalVpnEnabled(false)
            viewModel.triggerRefreshMenuList()
        }
    }

    override fun getHeightRatio(): Float = -1f

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_SELECTED_PROFILE, selectedProfileKey)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.proxifier_rules_bottom_sheet, container, false)

        val tvCurrentNetwork = view.findViewById<TextView>(R.id.tvCurrentNetwork)
        val swSeparateVpnProfiles = view.findViewById<MaterialSwitch>(R.id.swSeparateVpnProfiles)
        val toggleNetworkProfile = view.findViewById<MaterialButtonToggleGroup>(R.id.toggleNetworkProfile)
        val swVpnEnabled = view.findViewById<MaterialSwitch>(R.id.swVpnEnabled)
        val rvRules = view.findViewById<RecyclerView>(R.id.rvRules)
        val btnDefaultAction = view.findViewById<MaterialButton>(R.id.btnDefaultAction)
        val btnAddRule = view.findViewById<MaterialButton>(R.id.btnAddRule)
        val btnSave = view.findViewById<MaterialButton>(R.id.btnSave)

        tvCurrentNetworkView = tvCurrentNetwork
        swVpnEnabledView = swVpnEnabled
        btnDefaultActionView = btnDefaultAction

        rvRules.layoutManager = LinearLayoutManager(requireContext())
        adapter = RulesAdapter()
        rvRules.adapter = adapter

        val separateEnabled = Prefs.vpnSeparateNetworkProfiles
        val isWifi = NetworkUtils.isWifiConnected(requireContext())
        updateCurrentNetworkBanner(isWifi)

        val restoredProfile = savedInstanceState?.getString(KEY_SELECTED_PROFILE)
        selectedProfileKey = when {
            !separateEnabled -> Prefs.VPN_PROFILE_UNIFIED
            restoredProfile == Prefs.VPN_PROFILE_WIFI || restoredProfile == Prefs.VPN_PROFILE_MOBILE ->
                restoredProfile
            isWifi -> Prefs.VPN_PROFILE_WIFI
            else -> Prefs.VPN_PROFILE_MOBILE
        }

        isUpdatingProfileUi = true
        swSeparateVpnProfiles.isChecked = separateEnabled
        toggleNetworkProfile.visibility = if (separateEnabled) View.VISIBLE else View.GONE
        if (separateEnabled) {
            toggleNetworkProfile.check(
                if (selectedProfileKey == Prefs.VPN_PROFILE_WIFI) R.id.btnProfileWifi else R.id.btnProfileMobile
            )
        }
        isUpdatingProfileUi = false

        loadProfileIntoUi(selectedProfileKey)

        isUpdatingVpnSwitch = true
        swVpnEnabled.isChecked = Prefs.isGlobalVpnEnabled
        isUpdatingVpnSwitch = false

        swSeparateVpnProfiles.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingProfileUi) return@setOnCheckedChangeListener
            val ctx = requireContext()
            ctx.setSeparateVpnProfilesEnabled(isChecked)
            val nowWifi = NetworkUtils.isWifiConnected(ctx)
            updateCurrentNetworkBanner(nowWifi)
            toggleNetworkProfile.visibility = if (isChecked) View.VISIBLE else View.GONE
            val nextProfile = if (isChecked) {
                if (nowWifi) Prefs.VPN_PROFILE_WIFI else Prefs.VPN_PROFILE_MOBILE
            } else {
                Prefs.VPN_PROFILE_UNIFIED
            }
            isUpdatingProfileUi = true
            if (isChecked) {
                toggleNetworkProfile.check(
                    if (nextProfile == Prefs.VPN_PROFILE_WIFI) R.id.btnProfileWifi else R.id.btnProfileMobile
                )
            }
            isUpdatingProfileUi = false
            loadProfileIntoUi(nextProfile)
            viewModel.triggerRefreshMenuList()
        }

        toggleNetworkProfile.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isUpdatingProfileUi || !isChecked) return@addOnButtonCheckedListener
            val nextProfile = when (checkedId) {
                R.id.btnProfileWifi -> Prefs.VPN_PROFILE_WIFI
                R.id.btnProfileMobile -> Prefs.VPN_PROFILE_MOBILE
                else -> return@addOnButtonCheckedListener
            }
            if (nextProfile != selectedProfileKey) {
                loadProfileIntoUi(nextProfile)
            }
        }

        swVpnEnabled.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingVpnSwitch) return@setOnCheckedChangeListener
            if (isChecked) {
                val prepareIntent = VpnService.prepare(requireContext())
                if (prepareIntent != null) {
                    vpnPrepareLauncher.launch(prepareIntent)
                } else {
                    requireContext().setGlobalVpnEnabled(true)
                    viewModel.triggerRefreshMenuList()
                }
            } else {
                requireContext().setGlobalVpnEnabled(false)
                viewModel.triggerRefreshMenuList()
            }
        }

        btnDefaultAction.setOnClickListener {
            showActionPicker(it) { chosenAction ->
                defaultAction = chosenAction
                btnDefaultAction.text = ProxifierRule.getActionTitle(chosenAction)
                saveAndApplyRules()
            }
        }

        val touchCallback = object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
            private var dragMoved = false

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
                    dragMoved = true
                }
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                if (dragMoved) {
                    dragMoved = false
                    adapter.notifyDataSetChanged()
                    saveAndApplyRules()
                }
            }
        }
        itemTouchHelper = ItemTouchHelper(touchCallback)
        itemTouchHelper.attachToRecyclerView(rvRules)

        btnAddRule.setOnClickListener {
            showRuleEditor(null)
        }

        btnSave.setOnClickListener {
            dismiss()
        }

        return view
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            requireContext(),
            profileChangedReceiver,
            IntentFilter(ACTION_VPN_PROFILE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onResume() {
        super.onResume()
        context?.let { updateCurrentNetworkBanner(NetworkUtils.isWifiConnected(it)) }
        isUpdatingVpnSwitch = true
        swVpnEnabledView?.isChecked = Prefs.isGlobalVpnEnabled
        isUpdatingVpnSwitch = false
    }

    override fun onStop() {
        try {
            requireContext().unregisterReceiver(profileChangedReceiver)
        } catch (_: IllegalArgumentException) {}
        super.onStop()
    }

    override fun onDestroyView() {
        swVpnEnabledView = null
        tvCurrentNetworkView = null
        btnDefaultActionView = null
        super.onDestroyView()
    }

    private fun updateCurrentNetworkBanner(isWifi: Boolean) {
        tvCurrentNetworkView?.text = if (isWifi) {
            "Текущая сеть: Wi-Fi"
        } else {
            "Текущая сеть: Мобильная сеть"
        }
    }

    private fun loadProfileIntoUi(profileKey: String) {
        selectedProfileKey = profileKey

        defaultAction = Prefs.getAppRoutingDefaultActionForProfile(profileKey)
        btnDefaultActionView?.text = ProxifierRule.getActionTitle(defaultAction)

        rulesList.clear()
        rulesList.addAll(
            ProxifierRulesManager.getRulesForProfile(profileKey)
                .map { it.copy(targetPackages = it.targetPackages.toMutableList()) }
        )
        if (::adapter.isInitialized) {
            adapter.notifyDataSetChanged()
        }
    }

    private fun saveAndApplyRules() {
        val ctx = context ?: return
        Prefs.appRoutingEnabled = true
        Prefs.setAppRoutingDefaultActionForProfile(selectedProfileKey, defaultAction)
        ProxifierRulesManager.saveRulesForProfile(selectedProfileKey, rulesList)

        if (selectedProfileKey == Prefs.getActiveVpnProfileKey() &&
            Prefs.isGlobalVpnEnabled &&
            AppVpnService.isConnected
        ) {
            ctx.sendIntentToService(
                Intent(ctx, AppVpnService::class.java).apply {
                    action = AppVpnService.ACTION_RELOAD_PROFILE
                },
                isForeground = false
            )
        }
        viewModel.triggerRefreshMenuList()
    }

    private fun getInstalledAppLabel(pm: PackageManager, packageName: String): String? {
        if (packageName.startsWith("*")) return null
        return try {
            val info = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(packageName, 0)
            }
            pm.getApplicationLabel(info).toString()
        } catch (_: Exception) {
            null
        }
    }

    private fun isKnownUninstalledAppPackage(target: String): Boolean {
        return !target.startsWith("*") &&
            target.contains('.') &&
            (target.startsWith("com.") || target.startsWith("org.") || target.startsWith("app."))
    }

    private fun buildAppsButtonText(appLabels: List<String>): String = when {
        appLabels.isEmpty() -> "Выбрать приложения (0 выбрано)"
        appLabels.size <= 2 -> "Приложения: ${appLabels.joinToString(", ")}"
        else -> "Приложения: ${appLabels.take(2).joinToString(", ")} (+${appLabels.size - 2})"
    }

    private fun buildDefaultRuleName(
        appLabels: List<String>,
        // Domain filtering commented out:
        // customDomains: List<String>,
        action: String
    ): String {
        val targets = appLabels // + customDomains
        if (targets.isEmpty()) return "Правило"
        val summary = if (targets.size <= 2) {
            targets.joinToString(", ")
        } else {
            "${targets.take(2).joinToString(", ")} (+${targets.size - 2})"
        }
        val actionShort = ProxifierRule.getActionTitle(action).substringBefore(" (")
        return "$summary → $actionShort"
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
        val inflater = LayoutInflater.from(requireContext())
        val dialogView = inflater.inflate(R.layout.dialog_edit_rule, null)
        val tvTitle = dialogView.findViewById<TextView>(R.id.tvDialogTitle)
        val etRuleName = dialogView.findViewById<EditText>(R.id.etRuleName)
        val btnChooseApps = dialogView.findViewById<MaterialButton>(R.id.btnChooseApps)
        // Domain filtering commented out:
        // val etCustomDomains = dialogView.findViewById<EditText>(R.id.etCustomDomains)
        val cgAction = dialogView.findViewById<ChipGroup>(R.id.cgAction)

        val isEdit = existingRule != null
        tvTitle.text = if (isEdit) "Редактирование правила" else "Новое правило"
        etRuleName.setText(existingRule?.name ?: "")

        val pm = requireContext().packageManager
        val selectedAppPackages = mutableListOf<String>()
        val uninstalledPackages = mutableListOf<String>()
        // val initialDomains = mutableListOf<String>()

        existingRule?.targetPackages?.forEach { target ->
            when {
                getInstalledAppLabel(pm, target) != null -> selectedAppPackages.add(target)
                isKnownUninstalledAppPackage(target) -> uninstalledPackages.add(target)
                // else -> initialDomains.add(target)
            }
        }
        // etCustomDomains.setText(initialDomains.joinToString(", "))

        fun updateAppsButtonText() {
            val labels = selectedAppPackages.mapNotNull { getInstalledAppLabel(pm, it) }
            btnChooseApps.text = buildAppsButtonText(labels)
        }
        updateAppsButtonText()

        btnChooseApps.setOnClickListener {
            AppSelectDialog(selectedAppPackages.toList()) { newSelection ->
                selectedAppPackages.clear()
                selectedAppPackages.addAll(newSelection)
                updateAppsButtonText()
            }.show(childFragmentManager, AppSelectDialog.TAG)
        }

        val availableActions = ProxifierRulesManager.getAvailableActions()
        val initialAction = existingRule?.action ?: ProxifierRule.ACTION_BYEDPI
        var checkedChipId = View.NO_ID
        var firstChipId = View.NO_ID

        availableActions.forEach { (actionKey, actionTitle) ->
            val chip = inflater.inflate(R.layout.item_action_chip, cgAction, false) as Chip
            val chipId = View.generateViewId()
            chip.id = chipId
            chip.tag = actionKey
            chip.text = actionTitle
            cgAction.addView(chip)
            if (firstChipId == View.NO_ID) firstChipId = chipId
            if (actionKey == initialAction) checkedChipId = chipId
        }
        val targetChipId = if (checkedChipId != View.NO_ID) checkedChipId else firstChipId
        if (targetChipId != View.NO_ID) {
            cgAction.check(targetChipId)
        }

        AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setPositiveButton("Сохранить") { _, _ ->
                val chosenChip = cgAction.findViewById<Chip>(cgAction.checkedChipId)
                val chosenAction = chosenChip?.tag as? String ?: initialAction

                // Domain filtering commented out:
                // val customDomains = etCustomDomains.text.toString()
                //     .split(',', ' ', '\n')
                //     .map { it.trim().lowercase() }
                //     .filter { it.isNotBlank() }

                val finalTargets = linkedSetOf<String>().apply {
                    addAll(selectedAppPackages)
                    addAll(uninstalledPackages)
                    // addAll(customDomains)
                }.toMutableList()

                val appLabels = selectedAppPackages.mapNotNull { getInstalledAppLabel(pm, it) }
                val name = etRuleName.text.toString().trim().ifEmpty {
                    buildDefaultRuleName(appLabels, /* customDomains, */ chosenAction)
                }

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
                saveAndApplyRules()
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
                saveAndApplyRules()
            }

            // Up / Down Priority buttons
            holder.btnMoveUp.visibility = if (position > 0) View.VISIBLE else View.INVISIBLE
            holder.btnMoveDown.visibility = if (position < rulesList.size - 1) View.VISIBLE else View.INVISIBLE

            holder.btnMoveUp.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION && pos > 0) {
                    Collections.swap(rulesList, pos, pos - 1)
                    notifyItemMoved(pos, pos - 1)
                    notifyItemChanged(pos)
                    notifyItemChanged(pos - 1)
                    saveAndApplyRules()
                }
            }

            holder.btnMoveDown.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION && pos < rulesList.size - 1) {
                    Collections.swap(rulesList, pos, pos + 1)
                    notifyItemMoved(pos, pos + 1)
                    notifyItemChanged(pos)
                    notifyItemChanged(pos + 1)
                    saveAndApplyRules()
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
                getInstalledAppLabel(pm, item)
                // Domain filtering commented out:
                // ?: if (isKnownUninstalledAppPackage(item)) null else item
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
                    saveAndApplyRules()
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
                    if (pos < rulesList.size) {
                        notifyItemRangeChanged(pos, rulesList.size - pos)
                    }
                    if (pos == rulesList.size && pos > 0) {
                        notifyItemChanged(pos - 1)
                    }
                    saveAndApplyRules()
                }
            }
        }

        override fun getItemCount(): Int = rulesList.size
    }

    companion object {
        const val TAG = "ProxifierRulesBottomSheet"
        private const val KEY_SELECTED_PROFILE = "key_selected_profile"
    }
}

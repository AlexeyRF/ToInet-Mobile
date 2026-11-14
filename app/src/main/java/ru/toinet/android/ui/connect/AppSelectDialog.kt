package ru.toinet.android.ui.connect

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.toinet.android.R

class AppSelectDialog() : BottomSheetDialogFragment() {

    var onDoneListener: ((List<String>) -> Unit)? = null

    constructor(initialSelected: List<String>, onDone: (List<String>) -> Unit) : this() {
        arguments = Bundle().apply {
            putStringArrayList(ARG_INITIAL_SELECTED, ArrayList(initialSelected))
        }
        this.onDoneListener = onDone
    }

    data class AppItem(
        val name: String,
        val packageName: String
    )

    private val allApps = mutableListOf<AppItem>()
    private val filteredApps = mutableListOf<AppItem>()
    private val selectedPackages = mutableSetOf<String>()
    private val iconCache = LruCache<String, Drawable>(80)
    private lateinit var adapter: AppAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialList = savedInstanceState?.getStringArrayList(KEY_SELECTED)
            ?: arguments?.getStringArrayList(ARG_INITIAL_SELECTED)
        if (initialList != null) {
            selectedPackages.clear()
            selectedPackages.addAll(initialList)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(KEY_SELECTED, ArrayList(selectedPackages))
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.dialog_select_apps, container, false)
        val etSearch = view.findViewById<EditText>(R.id.etSearch)
        val progressBar = view.findViewById<ProgressBar>(R.id.progressBar)
        val rvApps = view.findViewById<RecyclerView>(R.id.rvApps)
        val btnCancel = view.findViewById<View>(R.id.btnCancel)
        val btnDone = view.findViewById<View>(R.id.btnDone)

        rvApps.layoutManager = LinearLayoutManager(requireContext())
        adapter = AppAdapter()
        rvApps.adapter = adapter

        btnCancel.setOnClickListener { dismiss() }
        btnDone.setOnClickListener {
            onDoneListener?.invoke(selectedPackages.toList())
            dismiss()
        }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.lowercase()?.trim() ?: ""
                filteredApps.clear()
                if (query.isEmpty()) {
                    filteredApps.addAll(allApps)
                } else {
                    filteredApps.addAll(allApps.filter {
                        it.name.lowercase().contains(query) || it.packageName.lowercase().contains(query)
                    })
                }
                adapter.notifyDataSetChanged()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        loadApps(progressBar, rvApps)
        return view
    }

    private fun loadApps(progressBar: ProgressBar, rvApps: RecyclerView) {
        progressBar.visibility = View.VISIBLE
        rvApps.visibility = View.GONE

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val context = context ?: return@launch
            val pm = context.packageManager
            val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            val apps = mutableListOf<AppItem>()

            for (appInfo in packages) {
                val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val isUpdatedSystem = (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                val isUserApp = !isSystem || isUpdatedSystem
                if (isUserApp || selectedPackages.contains(appInfo.packageName)) {
                    val name = appInfo.loadLabel(pm).toString()
                    apps.add(AppItem(name, appInfo.packageName))
                }
            }

            apps.sortWith(compareBy(
                { !selectedPackages.contains(it.packageName) },
                { it.name.lowercase() }
            ))

            withContext(Dispatchers.Main) {
                allApps.clear()
                allApps.addAll(apps)
                filteredApps.clear()
                filteredApps.addAll(apps)
                adapter.notifyDataSetChanged()
                progressBar.visibility = View.GONE
                rvApps.visibility = View.VISIBLE
            }
        }
    }

    inner class AppAdapter : RecyclerView.Adapter<AppAdapter.VH>() {
        inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val ivIcon: ImageView = itemView.findViewById(R.id.ivAppIcon)
            val tvName: TextView = itemView.findViewById(R.id.tvAppName)
            val tvPackage: TextView = itemView.findViewById(R.id.tvAppPackage)
            val cbSelected: CheckBox = itemView.findViewById(R.id.cbSelected)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_select_app, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val app = filteredApps[position]
            holder.tvName.text = app.name
            holder.tvPackage.text = app.packageName

            val cachedIcon = iconCache.get(app.packageName)
            if (cachedIcon != null) {
                holder.ivIcon.setImageDrawable(cachedIcon)
            } else {
                holder.ivIcon.setImageDrawable(null)
                holder.ivIcon.tag = app.packageName
                val pkg = app.packageName
                val pm = holder.itemView.context.packageManager
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val icon = pm.getApplicationIcon(pkg)
                        iconCache.put(pkg, icon)
                        withContext(Dispatchers.Main) {
                            if (holder.ivIcon.tag == pkg) {
                                holder.ivIcon.setImageDrawable(icon)
                            }
                        }
                    } catch (_: Exception) {}
                }
            }

            holder.cbSelected.isChecked = selectedPackages.contains(app.packageName)

            holder.itemView.setOnClickListener {
                if (selectedPackages.contains(app.packageName)) {
                    selectedPackages.remove(app.packageName)
                    holder.cbSelected.isChecked = false
                } else {
                    selectedPackages.add(app.packageName)
                    holder.cbSelected.isChecked = true
                }
            }
        }

        override fun getItemCount(): Int = filteredApps.size
    }

    companion object {
        const val TAG = "AppSelectDialog"
        private const val ARG_INITIAL_SELECTED = "arg_initial_selected"
        private const val KEY_SELECTED = "key_selected"
    }
}

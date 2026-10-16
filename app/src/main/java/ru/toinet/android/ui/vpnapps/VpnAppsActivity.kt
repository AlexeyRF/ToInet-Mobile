package ru.toinet.android.ui.vpnapps

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.toinet.android.R
import ru.toinet.android.util.Prefs

class VpnAppsActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var progressBar: View
    private lateinit var etSearch: EditText
    private lateinit var btnDisableRu: Button
    private lateinit var adapter: VpnAppsAdapter
    private val appList = mutableListOf<AppItem>()
    private val filteredList = mutableListOf<AppItem>()
    private var excludedApps = mutableSetOf<String>()

    data class AppItem(
        val name: String,
        val packageName: String,
        val icon: android.graphics.drawable.Drawable?
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vpn_apps)

        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Приложения для VPN"
        toolbar.setNavigationOnClickListener { finish() }

        recyclerView = findViewById(R.id.recyclerView)
        progressBar = findViewById(R.id.progressBar)
        etSearch = findViewById(R.id.etSearch)
        btnDisableRu = findViewById(R.id.btnDisableRu)

        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = VpnAppsAdapter()
        recyclerView.adapter = adapter

        excludedApps = Prefs.vpnExcludedApps.toMutableSet()

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                filterApps(s.toString())
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        btnDisableRu.setOnClickListener {
            val ruApps = appList.filter {
                it.packageName.contains("ru.", ignoreCase = true) || it.packageName.contains(".ru", ignoreCase = true)
            }
            ruApps.forEach { excludedApps.add(it.packageName) }
            savePrefs()
            adapter.notifyDataSetChanged()
        }

        loadApps()
    }

    private fun filterApps(query: String) {
        val lowerQuery = query.lowercase()
        filteredList.clear()
        if (lowerQuery.isEmpty()) {
            filteredList.addAll(appList)
        } else {
            filteredList.addAll(appList.filter {
                it.name.lowercase().contains(lowerQuery) || it.packageName.lowercase().contains(lowerQuery)
            })
        }
        adapter.notifyDataSetChanged()
    }

    private fun loadApps() {
        progressBar.visibility = View.VISIBLE
        recyclerView.visibility = View.GONE

        lifecycleScope.launch(Dispatchers.IO) {
            val pm = packageManager
            val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            val apps = mutableListOf<AppItem>()

            val priorityApps = setOf(
                "com.instagram.android",
                "com.google.android.youtube",
                "org.telegram.messenger",
                "org.telegram.messenger.web"
            )

            if (!Prefs.vpnLoopProtectionInitialized) {
                for (appInfo in packages) {
                    if (Prefs.isLockedVpnPackage(appInfo.packageName)) {
                        excludedApps.add(appInfo.packageName)
                    }
                }
                Prefs.vpnLoopProtectionInitialized = true
                savePrefs()
            }

            for (appInfo in packages) {
                val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val hasLaunchIntent = pm.getLaunchIntentForPackage(appInfo.packageName) != null
                val isPriority = priorityApps.contains(appInfo.packageName)
                val isLocked = Prefs.isLockedVpnPackage(appInfo.packageName)

                if (!isSystem || hasLaunchIntent || isPriority || isLocked) {
                    val name = appInfo.loadLabel(pm).toString()
                    val icon = appInfo.loadIcon(pm)
                    apps.add(AppItem(name, appInfo.packageName, icon))
                }
            }

            apps.sortWith(compareBy(
                { !priorityApps.contains(it.packageName) },
                { it.name.lowercase() }
            ))

            withContext(Dispatchers.Main) {
                appList.clear()
                appList.addAll(apps)
                filteredList.clear()
                filteredList.addAll(apps)
                adapter.notifyDataSetChanged()
                progressBar.visibility = View.GONE
                recyclerView.visibility = View.VISIBLE
            }
        }
    }

    inner class VpnAppsAdapter : RecyclerView.Adapter<VpnAppsAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val ivIcon: ImageView = view.findViewById(R.id.ivIcon)
            val tvName: TextView = view.findViewById(R.id.tvName)
            val tvPackage: TextView = view.findViewById(R.id.tvPackage)
            val swEnabled: Switch = view.findViewById(R.id.swEnabled)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_vpn_app, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val app = filteredList[position]
            val isLocked = Prefs.isLockedVpnPackage(app.packageName)
            val isExcluded = excludedApps.contains(app.packageName)
            holder.tvName.text = app.name
            holder.ivIcon.setImageDrawable(app.icon)

            if (isLocked && isExcluded) {
                holder.tvPackage.text = "${app.packageName} (защита от петли)"
            } else {
                holder.tvPackage.text = app.packageName
            }

            holder.swEnabled.isEnabled = true
            holder.swEnabled.setOnCheckedChangeListener(null)
            holder.swEnabled.isChecked = !isExcluded

            holder.itemView.setOnClickListener {
                holder.swEnabled.performClick()
            }

            holder.swEnabled.setOnCheckedChangeListener { _, isChecked ->
                val pkgName = app.packageName
                if (isChecked) {
                    if (isLocked) {
                        AlertDialog.Builder(this@VpnAppsActivity)
                            .setTitle("Защита от петли")
                            .setMessage("Возможна петля трафика. Отключить защиту?")
                            .setPositiveButton("Отключить") { _, _ ->
                                excludedApps.remove(pkgName)
                                savePrefs()
                                val pos = holder.bindingAdapterPosition
                                if (pos != RecyclerView.NO_POSITION) {
                                    notifyItemChanged(pos)
                                }
                            }
                            .setNegativeButton("Отмена") { _, _ ->
                                holder.swEnabled.isChecked = false
                            }
                            .setOnCancelListener {
                                holder.swEnabled.isChecked = false
                            }
                            .show()
                    } else {
                        val isRu = pkgName.contains("ru.", ignoreCase = true) || pkgName.contains(".ru", ignoreCase = true)
                        if (isRu) {
                            AlertDialog.Builder(this@VpnAppsActivity)
                                .setTitle("Предупреждение")
                                .setMessage("Включение VPN для российских приложений ($pkgName) может привести к компрометации прокси сервера. Вы уверены?")
                                .setPositiveButton("Да") { _, _ ->
                                    excludedApps.remove(pkgName)
                                    savePrefs()
                                }
                                .setNegativeButton("Отмена") { _, _ ->
                                    holder.swEnabled.isChecked = false
                                }
                                .setOnCancelListener {
                                    holder.swEnabled.isChecked = false
                                }
                                .show()
                        } else {
                            excludedApps.remove(pkgName)
                            savePrefs()
                        }
                    }
                } else {
                    excludedApps.add(pkgName)
                    savePrefs()
                    if (isLocked) {
                        val pos = holder.bindingAdapterPosition
                        if (pos != RecyclerView.NO_POSITION) {
                            notifyItemChanged(pos)
                        }
                    }
                }
            }
        }

        override fun getItemCount(): Int = filteredList.size
    }

    private fun savePrefs() {
        Prefs.vpnExcludedApps = excludedApps
    }
}

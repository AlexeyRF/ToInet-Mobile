package ru.toinet.android.byedpi.ui

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ru.toinet.android.R
import ru.toinet.android.byedpi.adapters.StrategyResultAdapter
import ru.toinet.android.byedpi.data.Mode
import ru.toinet.android.byedpi.data.AppStatus
import ru.toinet.android.byedpi.data.SiteResult
import ru.toinet.android.byedpi.data.StrategyResult
import ru.toinet.android.byedpi.services.appStatus
import ru.toinet.android.byedpi.services.ServiceManager
import ru.toinet.android.byedpi.utility.HistoryUtils
import ru.toinet.android.byedpi.utility.getPreferences
import ru.toinet.android.byedpi.utility.SiteCheckUtils
import ru.toinet.android.byedpi.utility.getIntStringNotNull
import ru.toinet.android.byedpi.utility.getLongStringNotNull
import androidx.core.content.edit
import ru.toinet.android.byedpi.utility.getStringNotNull
import ru.toinet.android.byedpi.utility.DomainListUtils
import ru.toinet.android.byedpi.utility.mode
import kotlinx.coroutines.*
import java.io.File

class TestActivity : androidx.appcompat.app.AppCompatActivity() {

    private lateinit var strategiesRecyclerView: RecyclerView
    private lateinit var progressTextView: TextView
    private lateinit var disclaimerTextView: TextView
    private lateinit var startStopButton: Button
    private lateinit var strategyAdapter: StrategyResultAdapter

    private lateinit var siteChecker: SiteCheckUtils
    private lateinit var cmdHistoryUtils: HistoryUtils
    private lateinit var sites: List<String>
    private lateinit var cmds: List<String>

    private var savedCmd: String = ""
    private var testJob: Job? = null
    private val strategies = mutableListOf<StrategyResult>()
    

    private var isTesting: Boolean
        get() = prefs.getBoolean("is_test_running", false)
        set(value) {
            prefs.edit(commit = true) { putBoolean("is_test_running", value) }
        }

    private val prefs by lazy { getPreferences() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_proxy_test)
        setSupportActionBar(findViewById(R.id.toolbar))

        val ip = prefs.getStringNotNull("byedpi_proxy_ip", "127.0.0.1")
        val port = prefs.getIntStringNotNull("byedpi_proxy_port", 1080)

        siteChecker = SiteCheckUtils(ip, port)
        cmdHistoryUtils = HistoryUtils(this)

        strategiesRecyclerView = findViewById(R.id.strategiesRecyclerView)
        startStopButton = findViewById(R.id.startStopButton)
        progressTextView = findViewById(R.id.progressTextView)
        disclaimerTextView = findViewById(R.id.disclaimerTextView)

        strategyAdapter = StrategyResultAdapter(this,
            onApply = { command ->
                addToHistory(command)
            }
        )

        strategiesRecyclerView.layoutManager = LinearLayoutManager(this)
        strategiesRecyclerView.adapter = strategyAdapter

        lifecycleScope.launch {
            val previousResults = loadResults()

            if (previousResults.isNotEmpty()) {
                progressTextView.text = getString(R.string.test_complete)
                disclaimerTextView.visibility = View.GONE

                strategies.clear()
                strategies.addAll(previousResults)

                strategyAdapter.updateStrategies(strategies)
            }

            if (isTesting) {
                progressTextView.text = getString(R.string.test_proxy_error)
                disclaimerTextView.text = getString(R.string.test_crash)
                disclaimerTextView.visibility = View.VISIBLE
                isTesting = false
            }
        }

        startStopButton.setOnClickListener {
            startStopButton.isClickable = false

            if (isTesting) {
                stopTesting()
            } else {
                startTesting()
            }

            startStopButton.postDelayed({ startStopButton.isClickable = true }, 1000)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isTesting) {
                    stopTesting()
                } else {
                    finish()
                }
            }
        })

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_test, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_copy_log -> {
                copyLog()
                true
            }
            R.id.action_settings -> {
                if (!isTesting) {
                    val intent = Intent(this, TestSettingsActivity::class.java)
                    startActivity(intent)
                } else {
                    Toast.makeText(this, R.string.settings_unavailable, Toast.LENGTH_SHORT).show()
                }
                true
            }
            android.R.id.home -> {
                onBackPressedDispatcher.onBackPressed()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private suspend fun waitForProxyStatus(statusNeeded: AppStatus): Boolean {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < 3000) {
            if (appStatus.first == statusNeeded) {
                delay(500)
                return true
            }
            delay(100)
        }
        return false
    }

    private suspend fun isProxyRunning(): Boolean = withContext(Dispatchers.IO) {
        appStatus.first == AppStatus.Running
    }

    private fun updateCmdArgs(cmd: String) {
        prefs.edit(commit = true) { putString("byedpi_cmd_args", cmd) }
    }

    private fun startTesting() {
        sites = loadSites()
        cmds = loadCmds()

        if (sites.isEmpty()) {
            Toast.makeText(this, R.string.test_settings_domain_empty, Toast.LENGTH_LONG).show()
            return
        }

        testJob = lifecycleScope.launch(Dispatchers.IO) {
            isTesting = true
            savedCmd = prefs.getString("byedpi_cmd_args", "").orEmpty()

            strategies.clear()
            strategies.addAll(cmds.map { StrategyResult(command = it) })

            withContext(Dispatchers.Main) {
                disclaimerTextView.visibility = View.GONE

                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                startStopButton.text = getString(R.string.test_stop)
                progressTextView.text = ""

                strategyAdapter.setTestingState(true)
                strategyAdapter.updateStrategies(strategies, sortByPercentage = false)
            }

            if (appStatus.first != AppStatus.Halted) {
                ServiceManager.stop(this@TestActivity)
                waitForProxyStatus(AppStatus.Halted)
                // Add a small delay to ensure the system fully destroys the service
                delay(1000)
            }

            val delaySec = prefs.getIntStringNotNull("byedpi_proxytest_delay", 1)
            val requestsCount = prefs.getIntStringNotNull("byedpi_proxytest_requests", 1)
            val requestTimeout = prefs.getLongStringNotNull("byedpi_proxytest_timeout", 5)
            val requestLimit = prefs.getIntStringNotNull("byedpi_proxytest_limit", 20)

            for (strategyIndex in strategies.indices) {
                if (!isActive) break

                val strategy = strategies[strategyIndex]
                val cmdIndex = strategyIndex + 1

                withContext(Dispatchers.Main) {
                    progressTextView.text = getString(R.string.test_process, cmdIndex, cmds.size)
                }

                updateCmdArgs(strategy.command)

                if (appStatus.first != AppStatus.Halted) stopTesting()
                else ServiceManager.start(this@TestActivity, Mode.Proxy)

                if (!waitForProxyStatus(AppStatus.Running)) {
                    stopTesting()
                }

                delay(delaySec * 500L)

                val totalRequests = sites.size * requestsCount
                strategy.totalRequests = totalRequests

                withContext(Dispatchers.Main) {
                    strategyAdapter.notifyItemChanged(strategyIndex)
                }

                siteChecker.checkSitesAsync(
                    sites = sites,
                    requestsCount = requestsCount,
                    requestTimeout = requestTimeout,
                    concurrentRequests = requestLimit,
                    fullLog = true,
                    onSiteChecked = { site, successCount, countRequests ->
                        lifecycleScope.launch(Dispatchers.Main) {
                            strategy.currentProgress += countRequests
                            strategy.successCount += successCount
                            strategy.siteResults.add(SiteResult(site, successCount, countRequests))

                            strategyAdapter.notifyItemChanged(strategyIndex, "progress")
                        }
                    }
                )

                strategy.isCompleted = true

                withContext(Dispatchers.Main) {
                    strategyAdapter.updateStrategies(strategies, sortByPercentage = true)
                    saveResults(strategies)
                }

                if (appStatus.first != AppStatus.Halted) ServiceManager.stop(this@TestActivity)
                else stopTesting()

                if (!waitForProxyStatus(AppStatus.Halted)) {
                    stopTesting()
                }

                delay(delaySec * 500L)
            }

            stopTesting()
        }
    }

    private fun stopTesting() {
        if (!isTesting) {
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            isTesting = false
            updateCmdArgs(savedCmd)

            testJob?.cancel()
            testJob = null

            if (appStatus.first != AppStatus.Halted) {
                ServiceManager.stop(this@TestActivity)
            }

            withContext(Dispatchers.Main) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                startStopButton.text = getString(R.string.test_start)
                progressTextView.text = getString(R.string.test_complete)

                suggestCombinations(strategies.filter { it.isCompleted })

                strategyAdapter.setTestingState(false)
                strategyAdapter.updateStrategies(strategies, sortByPercentage = true)

                saveResults(strategies)
            }
        }
    }

    private fun suggestCombinations(completeStrategies: List<StrategyResult>) {
        if (completeStrategies.isEmpty() || !::sites.isInitialized || sites.isEmpty()) return

        val hasPerfect = completeStrategies.any { it.successCount == it.totalRequests && it.totalRequests > 0 }
        if (hasPerfect) return

        val domainToWorkingCmds = mutableMapOf<String, List<String>>()
        var canCoverAll = true

        for (domain in sites) {
            val workingStrats = completeStrategies.filter { strat ->
                strat.siteResults.any { it.site == domain && it.successCount == it.totalCount && it.totalCount > 0 }
            }.map { it.command }

            if (workingStrats.isNotEmpty()) {
                domainToWorkingCmds[domain] = workingStrats
            } else {
                if (prefs.getBoolean("byedpi_proxytest_tor_fallback", false)) {
                    domainToWorkingCmds[domain] = listOf("TOR")
                }
            }
        }

        if (domainToWorkingCmds.isNotEmpty()) {
            val maxCombos = 3
            for (i in 0 until maxCombos) {
                val currentCombo = mutableMapOf<String, String>()
                for ((domain, cmds) in domainToWorkingCmds) {
                    val cmdIndex = if (i < cmds.size) i else 0
                    currentCombo[domain] = cmds[cmdIndex]
                }

                try {
                    val jsonObject = org.json.JSONObject()
                    currentCombo.forEach { (domain, cmd) ->
                        jsonObject.put(domain, cmd)
                    }
                    val combinedCmd = jsonObject.toString()

                    if (strategies.any { it.command == combinedCmd }) continue

                    val combinedResult = StrategyResult(command = combinedCmd).apply {
                        isCompleted = true
                        val reqs = prefs.getIntStringNotNull("byedpi_proxytest_requests", 1)
                        successCount = sites.size * reqs
                        totalRequests = successCount

                        sites.forEach { domain ->
                            siteResults.add(SiteResult(domain, reqs, reqs))
                        }
                    }
                    strategies.add(combinedResult)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun addToHistory(command: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            cmdHistoryUtils.addCommand(command)
            withContext(Dispatchers.Main) {
                if (command.contains("\"TOR\"") && !ru.toinet.android.util.Prefs.torEnabled) {
                    Toast.makeText(applicationContext, "Внимание: используется маршрутизация через TOR, но TOR отключен в настройках!", Toast.LENGTH_LONG).show()
                }
                val intent = Intent()
                intent.putExtra("strategy", command)
                setResult(RESULT_OK, intent)
                finish()
            }
        }
    }

        private fun saveResults(results: List<StrategyResult>) {
        try {
            val jsonArray = org.json.JSONArray()
            for (res in results) {
                val obj = org.json.JSONObject()
                obj.put("command", res.command)
                obj.put("successCount", res.successCount)
                obj.put("totalRequests", res.totalRequests)
                obj.put("currentProgress", res.currentProgress)
                obj.put("isCompleted", res.isCompleted)
                obj.put("isExpanded", res.isExpanded)
                val sites = org.json.JSONArray()
                for (site in res.siteResults) {
                    val siteObj = org.json.JSONObject()
                    siteObj.put("site", site.site)
                    siteObj.put("successCount", site.successCount)
                    siteObj.put("totalCount", site.totalCount)
                    sites.put(siteObj)
                }
                obj.put("siteResults", sites)
                jsonArray.put(obj)
            }
            val file = File(filesDir, "proxy_test_results.json")
            file.writeText(jsonArray.toString())
        } catch (e: Exception) {}
    }

        private fun loadResults(): List<StrategyResult> {
        val file = File(filesDir, "proxy_test_results.json")
        val list = mutableListOf<StrategyResult>()
        if (file.exists()) {
            try {
                val jsonText = file.readText()
                val jsonArray = org.json.JSONArray(jsonText)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val res = StrategyResult(
                        command = obj.getString("command"),
                        successCount = obj.getInt("successCount"),
                        totalRequests = obj.getInt("totalRequests"),
                        currentProgress = obj.getInt("currentProgress"),
                        isCompleted = obj.getBoolean("isCompleted"),
                        isExpanded = obj.getBoolean("isExpanded")
                    )
                    val sites = obj.getJSONArray("siteResults")
                    for (j in 0 until sites.length()) {
                        val siteObj = sites.getJSONObject(j)
                        res.siteResults.add(SiteResult(
                            site = siteObj.getString("site"),
                            successCount = siteObj.getInt("successCount"),
                            totalCount = siteObj.getInt("totalCount")
                        ))
                    }
                    list.add(res)
                }
            } catch (e: Exception) {}
        }
        return list
    }

    private fun loadSites(): List<String> {
        DomainListUtils.syncLists(this)
        return DomainListUtils.getActiveDomains(this)
    }

    private fun loadCmds(): List<String> {
        val userCommands = prefs.getBoolean("byedpi_proxytest_usercommands", false)
        val sniValue = prefs.getStringNotNull("byedpi_proxytest_sni", "google.com")

        return if (userCommands) {
            val content = prefs.getStringNotNull("byedpi_proxytest_commands", "")
            content.replace("{sni}", "\"${sniValue}\"").lines().map { it.trim() }.filter { it.isNotEmpty() }
        } else {
            val file = File(filesDir, "proxytest_strategies.list")
            val content = if (file.exists()) {
                file.readText()
            } else {
                assets.open("proxytest_strategies.list").bufferedReader().readText()
            }
            content.replace("{sni}", "\"${sniValue}\"").lines().map { it.trim() }.filter { it.isNotEmpty() }
        }
    }

    private fun copyLog() {
        val completeStrategies = strategies.filter { it.isCompleted }

        if (completeStrategies.isEmpty()) {
            Toast.makeText(this, R.string.toast_copied, Toast.LENGTH_SHORT).show()
            return
        }

        val sb = StringBuilder()

        completeStrategies.forEach { strategy ->
            sb.appendLine("${strategy.command}\n")

            strategy.siteResults.forEach { site ->
                sb.appendLine("${site.site} - ${site.successCount}/${site.totalCount}")
            }

            sb.appendLine("\n${strategy.successCount}/${strategy.totalRequests}")
            sb.appendLine("-------------")
        }

        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("proxy_test_log", sb.toString())
        clipboard.setPrimaryClip(clip)

        Toast.makeText(this, R.string.toast_copied, Toast.LENGTH_SHORT).show()
    }
}




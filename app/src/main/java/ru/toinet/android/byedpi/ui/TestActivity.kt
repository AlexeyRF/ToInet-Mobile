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
import ru.toinet.android.vpn.data.Mode
import ru.toinet.android.vpn.data.AppStatus
import ru.toinet.android.byedpi.data.SiteResult
import ru.toinet.android.byedpi.data.StrategyResult
import ru.toinet.android.vpn.services.appStatus
import ru.toinet.android.vpn.services.ServiceManager
import ru.toinet.android.byedpi.utility.HistoryUtils
import ru.toinet.android.vpn.utility.getPreferences
import ru.toinet.android.byedpi.utility.SiteCheckUtils
import ru.toinet.android.vpn.utility.getIntStringNotNull
import ru.toinet.android.vpn.utility.getLongStringNotNull
import androidx.core.content.edit
import ru.toinet.android.vpn.utility.getStringNotNull
import ru.toinet.android.byedpi.utility.DomainListUtils
import ru.toinet.android.vpn.utility.mode
import kotlinx.coroutines.*
import java.io.File

import ru.toinet.android.util.Prefs
import ru.toinet.android.util.NetworkUtils
import ru.toinet.android.util.sendIntentToService
import ru.toinet.android.vpn.data.ServiceStatus
import ru.toinet.android.byedpi.services.ByeDpiProxyService

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
    private val strategies = java.util.Collections.synchronizedList(mutableListOf<StrategyResult>())
    

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
        val port = prefs.getIntStringNotNull("byedpi_proxy_port", 1780)

        siteChecker = SiteCheckUtils(ip, port)
        cmdHistoryUtils = HistoryUtils(this)

        strategiesRecyclerView = findViewById(R.id.strategiesRecyclerView)
        startStopButton = findViewById(R.id.startStopButton)
        progressTextView = findViewById(R.id.progressTextView)
        disclaimerTextView = findViewById(R.id.disclaimerTextView)

        strategyAdapter = StrategyResultAdapter(this,
            onApplyWifi = { command ->
                applyStrategyForNetwork(command, isWifi = true)
            },
            onApplyMobile = { command ->
                applyStrategyForNetwork(command, isWifi = false)
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

    override fun onDestroy() {
        super.onDestroy()
        ByeDpiProxyService.testCmdArgsOverride = null
        if (isTesting) {
            isTesting = false
            testJob?.cancel()
            testJob = null
            if (appStatus.first != AppStatus.Halted) {
                ServiceManager.stop(this)
            }
        }
    }

    private suspend fun waitForProxyPort(port: Int, timeoutMs: Long = 5000): Boolean = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            try {
                java.net.Socket().use { socket ->
                    socket.connect(java.net.InetSocketAddress("127.0.0.1", port), 300)
                    return@withContext true
                }
            } catch (_: Exception) {
                delay(100)
            }
        }
        false
    }

    private suspend fun waitForPortRelease(port: Int, timeoutMs: Long = 3000): Boolean = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            try {
                java.net.Socket().use { socket ->
                    socket.connect(java.net.InetSocketAddress("127.0.0.1", port), 200)
                }
                delay(100)
            } catch (_: Exception) {
                return@withContext true
            }
        }
        false
    }

    private suspend fun waitForProxyStatus(statusNeeded: AppStatus, timeoutMs: Long = 7000): Boolean {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            if (appStatus.first == statusNeeded) {
                delay(300)
                return true
            }
            delay(100)
        }
        return appStatus.first == statusNeeded
    }

    private suspend fun isProxyRunning(): Boolean = withContext(Dispatchers.IO) {
        appStatus.first == AppStatus.Running
    }

    private fun updateCmdArgs(cmd: String) {
        prefs.edit(commit = true) {
            putBoolean("byedpi_enable_cmd_settings", true)
            putString("byedpi_cmd_args", cmd)
        }
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
                strategyAdapter.updateStrategies(strategies)
            }

            val ip = prefs.getStringNotNull("byedpi_proxy_ip", "127.0.0.1")
            val port = prefs.getIntStringNotNull("byedpi_proxy_port", 1780)

            if (appStatus.first != AppStatus.Halted) {
                ByeDpiProxyService.testCmdArgsOverride = null
                ServiceManager.stop(this@TestActivity)
                waitForProxyStatus(AppStatus.Halted, 5000)
                waitForPortRelease(port, 3000)
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

                if (appStatus.first != AppStatus.Halted) {
                    ServiceManager.stop(this@TestActivity)
                    waitForProxyStatus(AppStatus.Halted, 5000)
                    waitForPortRelease(port, 3000)
                }

                ByeDpiProxyService.testCmdArgsOverride = strategy.command
                updateCmdArgs(strategy.command)
                ServiceManager.start(this@TestActivity, Mode.Proxy)

                val statusRunning = waitForProxyStatus(AppStatus.Running, 5000)
                val portReady = if (statusRunning) waitForProxyPort(port, 5000) else false
                val started = statusRunning && portReady

                if (!started) {
                    val totalRequests = sites.size * requestsCount
                    strategy.totalRequests = totalRequests
                    strategy.currentProgress = totalRequests
                    strategy.successCount = 0
                    strategy.isCompleted = true
                    for (site in sites) {
                        strategy.siteResults.add(SiteResult(site, 0, requestsCount))
                    }
                    withContext(Dispatchers.Main) {
                        strategyAdapter.updateStrategies(strategies)
                        saveResults(strategies)
                    }
                    if (appStatus.first != AppStatus.Halted) {
                        ServiceManager.stop(this@TestActivity)
                        waitForProxyStatus(AppStatus.Halted, 3000)
                        waitForPortRelease(port, 2000)
                    }
                    continue
                }

                delay(delaySec * 500L)

                val totalRequests = sites.size * requestsCount
                strategy.totalRequests = totalRequests
                strategy.currentProgress = 0
                strategy.successCount = 0

                withContext(Dispatchers.Main) {
                    strategyAdapter.updateStrategy(strategy)
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

                            strategyAdapter.updateStrategy(strategy)
                        }
                    }
                )

                withContext(Dispatchers.Main) {
                    strategy.isCompleted = true
                    strategyAdapter.updateStrategies(strategies)
                    saveResults(strategies)
                }

                if (appStatus.first != AppStatus.Halted) {
                    ServiceManager.stop(this@TestActivity)
                    waitForProxyStatus(AppStatus.Halted, 5000)
                    waitForPortRelease(port, 3000)
                }

                delay(delaySec * 500L)
            }

            withContext(Dispatchers.Main) {
                progressTextView.text = getString(R.string.test_complete)
            }
            stopTesting()
        }
    }

    private fun stopTesting() {
        if (!isTesting) {
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            ByeDpiProxyService.testCmdArgsOverride = null
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
                // Fallback strategies disabled (commented out):
                // suggestCombinations(strategies.filter { it.isCompleted })

                strategyAdapter.setTestingState(false)
                strategyAdapter.updateStrategies(strategies)
                strategiesRecyclerView.scrollToPosition(0)

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
            }
            /*
            else {
                if (prefs.getBoolean("byedpi_proxytest_tor_fallback", false)) {
                    domainToWorkingCmds[domain] = listOf("TOR")
                }
            }
            */
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

    private fun applyStrategyForNetwork(command: String, isWifi: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            cmdHistoryUtils.addCommand(command)

            val targetNetworkName = if (isWifi) "Wi-Fi" else "Мобильной сети"
            if (isWifi) {
                Prefs.byedpiWifiArgs = command
            } else {
                Prefs.byedpiMobileArgs = command
            }
            Prefs.byedpiEnableCmdSettings = true

            val isCurrentlyWifi = NetworkUtils.isWifiConnected(this@TestActivity)
            val isCurrentNetworkTarget = (isWifi == isCurrentlyWifi)

            if (isCurrentNetworkTarget) {
                Prefs.byedpiArgs = command
                if (ByeDpiProxyService.status == ServiceStatus.Connected) {
                    val restartIntent = Intent(this@TestActivity, ByeDpiProxyService::class.java).apply {
                        action = ByeDpiProxyService.ACTION_RESTART
                        putExtra("is_foreground", !Prefs.isGlobalVpnEnabled && Prefs.isPrimaryNotificationService("byedpi"))
                    }
                    sendIntentToService(restartIntent, isForeground = false)
                }
            }

            withContext(Dispatchers.Main) {
                val activeNotice = if (isCurrentNetworkTarget && ByeDpiProxyService.status == ServiceStatus.Connected) {
                    " (перезапущен)"
                } else if (isCurrentNetworkTarget) {
                    " (текущая активная сеть)"
                } else ""
                Toast.makeText(this@TestActivity, "Стратегия сохранена для $targetNetworkName$activeNotice", Toast.LENGTH_SHORT).show()
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
                    val cmd = obj.getString("command")
                    // Filter out any leftover fallback/TOR combination strategies
                    if (cmd.contains("TOR", ignoreCase = true) ||
                        cmd.startsWith("{") ||
                        cmd.contains("fallback", ignoreCase = true)
                    ) {
                        continue
                    }
                    val res = StrategyResult(
                        command = cmd,
                        successCount = obj.getInt("successCount"),
                        totalRequests = obj.getInt("totalRequests"),
                        currentProgress = obj.getInt("currentProgress"),
                        isCompleted = obj.getBoolean("isCompleted"),
                        isExpanded = obj.optBoolean("isExpanded", false)
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

        val rawLines = if (userCommands) {
            val content = prefs.getStringNotNull("byedpi_proxytest_commands", "")
            content.replace("{sni}", "\"${sniValue}\"").lines()
        } else {
            try {
                val file = File(filesDir, "proxytest_strategies.list")
                if (file.exists()) file.delete()
            } catch (_: Exception) {}
            assets.open("proxytest_strategies.list").bufferedReader().use { it.readText() }
                .replace("{sni}", "\"${sniValue}\"").lines()
        }
        return rawLines.map { it.trim().removePrefix("\uFEFF") }.filter {
            it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("//") && !it.startsWith("--preset") && !it.contains("TOR", ignoreCase = true) && !it.contains("fallback", ignoreCase = true) && !it.startsWith("{")
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






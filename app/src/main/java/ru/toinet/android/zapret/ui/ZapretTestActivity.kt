package ru.toinet.android.zapret.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import ru.toinet.android.R
import ru.toinet.android.byedpi.adapters.StrategyResultAdapter
import ru.toinet.android.byedpi.data.SiteResult
import ru.toinet.android.byedpi.data.StrategyResult
import ru.toinet.android.byedpi.ui.TestSettingsActivity
import ru.toinet.android.byedpi.utility.DomainListUtils
import ru.toinet.android.byedpi.utility.SiteCheckUtils
import ru.toinet.android.util.Prefs
import ru.toinet.android.vpn.utility.getIntStringNotNull
import ru.toinet.android.vpn.utility.getLongStringNotNull
import ru.toinet.android.vpn.utility.getPreferences
import ru.toinet.android.zapret.ZapretService
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

class ZapretTestActivity : AppCompatActivity() {

    private lateinit var strategiesRecyclerView: RecyclerView
    private lateinit var progressTextView: TextView
    private lateinit var disclaimerTextView: TextView
    private lateinit var startStopButton: Button
    private lateinit var strategyAdapter: StrategyResultAdapter

    private lateinit var siteChecker: SiteCheckUtils
    private lateinit var sites: List<String>
    private lateinit var cmds: List<String>

    private var currentProcess: Process? = null
    private var testJob: Job? = null
    private val strategies = mutableListOf<StrategyResult>()
    private var isTesting = false

    private val prefs by lazy { getPreferences() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_proxy_test)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = "Тестирование Zapret"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val port = Prefs.zapretPort
        siteChecker = SiteCheckUtils("127.0.0.1", port)

        strategiesRecyclerView = findViewById(R.id.strategiesRecyclerView)
        startStopButton = findViewById(R.id.startStopButton)
        progressTextView = findViewById(R.id.progressTextView)
        disclaimerTextView = findViewById(R.id.disclaimerTextView)
        disclaimerTextView.text = "Тестирование различных стратегий Zapret (tpws) на доступность сайтов"

        strategyAdapter = StrategyResultAdapter(this,
            onApply = { command ->
                applyStrategy(command)
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
                    val intent = Intent(this, TestSettingsActivity::class.java).apply {
                        putExtra("open_fragment", "domain_lists")
                    }
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

    private fun startTesting() {
        sites = loadSites()
        cmds = loadCmds()

        if (sites.isEmpty()) {
            Toast.makeText(this, R.string.test_settings_domain_empty, Toast.LENGTH_LONG).show()
            return
        }

        val executablePath = ZapretService.getExecutable(this)
        if (executablePath == null) {
            Toast.makeText(this, "Исполняемый файл libtpws.so не найден", Toast.LENGTH_LONG).show()
            return
        }

        testJob = lifecycleScope.launch(Dispatchers.IO) {
            isTesting = true

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

            if (ZapretService.isRunning) {
                ZapretService.stop(this@ZapretTestActivity)
                delay(500)
            }

            val port = Prefs.zapretPort
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

                // Start tpws process for this strategy
                val cmdArgs = mutableListOf<String>()
                cmdArgs.add(executablePath)
                cmdArgs.add("--socks")
                cmdArgs.add("--bind-addr=127.0.0.1")
                cmdArgs.add("--port=$port")

                val parts = strategy.command.trim().split("\\s+".toRegex()).filter { it.isNotEmpty() }
                for (part in parts) {
                    if (!part.startsWith("--bind-addr") &&
                        !part.startsWith("--port") &&
                        part != "--socks") {
                        cmdArgs.add(part)
                    }
                }
                cmdArgs.add("--debug=1")

                try {
                    val pb = ProcessBuilder(cmdArgs)
                    pb.directory(filesDir)
                    pb.redirectErrorStream(true)
                    val proc = pb.start()
                    currentProcess = proc

                    // Wait for tpws to open port
                    var ready = false
                    val startTime = System.currentTimeMillis()
                    while (System.currentTimeMillis() - startTime < 1500 && !ready && isActive) {
                        try {
                            Socket().use { s ->
                                s.connect(InetSocketAddress("127.0.0.1", port), 200)
                                ready = true
                            }
                        } catch (_: Exception) {
                            delay(100)
                        }
                    }

                    if (!ready || !isActive) {
                        proc.destroyForcibly()
                        currentProcess = null
                        continue
                    }

                    delay(delaySec * 200L)

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
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    try {
                        currentProcess?.destroyForcibly()
                        currentProcess?.waitFor()
                    } catch (_: Exception) {}
                    currentProcess = null
                }

                delay(delaySec * 200L)
            }

            stopTesting()
        }
    }

    private fun stopTesting() {
        if (!isTesting) return

        lifecycleScope.launch(Dispatchers.IO) {
            isTesting = false

            testJob?.cancel()
            testJob = null

            try {
                currentProcess?.destroyForcibly()
                currentProcess?.waitFor()
            } catch (_: Exception) {}
            currentProcess = null

            withContext(Dispatchers.Main) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                startStopButton.text = getString(R.string.test_start)
                progressTextView.text = getString(R.string.test_complete)

                strategyAdapter.setTestingState(false)
                strategyAdapter.updateStrategies(strategies, sortByPercentage = true)

                saveResults(strategies)
            }
        }
    }

    private fun applyStrategy(command: String) {
        val intent = Intent().apply {
            putExtra("strategy", command)
        }
        setResult(RESULT_OK, intent)
        finish()
    }

    private fun saveResults(results: List<StrategyResult>) {
        try {
            val jsonArray = JSONArray()
            for (res in results) {
                val obj = JSONObject()
                obj.put("command", res.command)
                obj.put("successCount", res.successCount)
                obj.put("totalRequests", res.totalRequests)
                obj.put("currentProgress", res.currentProgress)
                obj.put("isCompleted", res.isCompleted)
                obj.put("isExpanded", res.isExpanded)
                val sitesArray = JSONArray()
                for (site in res.siteResults) {
                    val siteObj = JSONObject()
                    siteObj.put("site", site.site)
                    siteObj.put("successCount", site.successCount)
                    siteObj.put("totalCount", site.totalCount)
                    sitesArray.put(siteObj)
                }
                obj.put("siteResults", sitesArray)
                jsonArray.put(obj)
            }
            val file = File(filesDir, "zapret_test_results.json")
            file.writeText(jsonArray.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun loadResults(): List<StrategyResult> {
        val file = File(filesDir, "zapret_test_results.json")
        val list = mutableListOf<StrategyResult>()
        if (file.exists()) {
            try {
                val jsonText = file.readText()
                val jsonArray = JSONArray(jsonText)
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
                        res.siteResults.add(
                            SiteResult(
                                site = siteObj.getString("site"),
                                successCount = siteObj.getInt("successCount"),
                                totalCount = siteObj.getInt("totalCount")
                            )
                        )
                    }
                    list.add(res)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return list
    }

    private fun loadSites(): List<String> {
        DomainListUtils.syncLists(this)
        return DomainListUtils.getActiveDomains(this)
    }

    private fun loadCmds(): List<String> {
        val file = File(filesDir, "zapret_test_strategies.list")
        val content = if (file.exists()) {
            file.readText()
        } else {
            try {
                assets.open("zapret_strategies.list").bufferedReader().readText()
            } catch (_: Exception) {
                listOf(
                    "--split-pos=1",
                    "--split-pos=1 --disorder",
                    "--split-pos=2",
                    "--split-pos=2 --hostcase --domcase",
                    "--split-pos=1 --disorder --oob",
                    "--split-pos=2 --disorder",
                    "--split-pos=1 --hostcase",
                    "--split-pos=2,sniext+1",
                    "--split-pos=2 --oob",
                    "--split-pos=sni --hostcase",
                    "--split-pos=sni --disorder",
                    "--split-pos=1 --tlsrec=sni",
                    "--split-pos=1 --tlsrec=sni --hostcase",
                    "--split-pos=2 --tlsrec=sni --hostcase",
                    "--split-pos=midsld --disorder",
                    "--split-pos=1,midsld --disorder",
                    "--split-pos=1 --oob"
                ).joinToString("\n")
            }
        }
        return content.lines().map { it.trim() }.filter { it.isNotEmpty() }
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

        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("zapret_test_log", sb.toString())
        clipboard.setPrimaryClip(clip)

        Toast.makeText(this, R.string.toast_copied, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            currentProcess?.destroyForcibly()
        } catch (_: Exception) {}
        currentProcess = null
    }
}

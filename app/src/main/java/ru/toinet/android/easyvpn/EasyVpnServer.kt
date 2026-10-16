package ru.toinet.android.easyvpn

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import ru.toinet.android.util.Prefs
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.net.URLDecoder

data class VlessNode(
    val originalLink: String,
    val server: String,
    val port: Int,
    val uuid: String,
    val remarks: String,
    val method: String,
    val security: String,
    val host: String,
    val path: String,
    val flow: String,
    val sni: String,
    val alpn: String,
    val fp: String,
    val pbk: String,
    val sid: String,
    val type: String,
    val serviceName: String
) {
    companion object {
        fun parse(raw: String): VlessNode? {
            try {
                var s = raw.trim()
                if (!s.startsWith("vless://", ignoreCase = true)) return null
                s = s.substring(8)

                var remarks = ""
                if (s.contains("#")) {
                    val parts = s.split("#", limit = 2)
                    s = parts[0]
                    remarks = try {
                        URLDecoder.decode(parts[1], "UTF-8")
                    } catch (e: Exception) {
                        parts[1]
                    }
                }

                var queryStr = ""
                if (s.contains("?")) {
                    val parts = s.split("?", limit = 2)
                    s = parts[0]
                    queryStr = parts[1]
                }

                if (!s.contains("@")) return null
                val atParts = s.split("@", limit = 2)
                val uuid = atParts[0].trim()
                val hostPort = atParts[1].trim()

                val server: String
                val port: Int
                if (hostPort.startsWith("[")) {
                    val closeBracket = hostPort.indexOf("]")
                    if (closeBracket == -1) return null
                    server = hostPort.substring(1, closeBracket)
                    val rest = hostPort.substring(closeBracket + 1)
                    port = if (rest.startsWith(":")) rest.substring(1).toIntOrNull() ?: 443 else 443
                } else {
                    if (!hostPort.contains(":")) return null
                    val lastColon = hostPort.lastIndexOf(":")
                    server = hostPort.substring(0, lastColon).trim()
                    port = hostPort.substring(lastColon + 1).trim().toIntOrNull() ?: 443
                }

                if (uuid.isEmpty() || server.isEmpty() || port <= 0 || port > 65535) return null

                val params = mutableMapOf<String, String>()
                if (queryStr.isNotEmpty()) {
                    queryStr.split("&").forEach { param ->
                        if (param.contains("=")) {
                            val kv = param.split("=", limit = 2)
                            val k = try { URLDecoder.decode(kv[0], "UTF-8") } catch (e: Exception) { kv[0] }
                            val v = try { URLDecoder.decode(kv[1], "UTF-8") } catch (e: Exception) { kv[1] }
                            params[k] = v
                        }
                    }
                }

                return VlessNode(
                    originalLink = raw,
                    server = server,
                    port = port,
                    uuid = uuid,
                    remarks = remarks,
                    method = params["encryption"] ?: "none",
                    security = params["security"] ?: "none",
                    host = params["host"] ?: "",
                    path = params["path"] ?: "/",
                    flow = params["flow"] ?: "",
                    sni = params["sni"] ?: "",
                    alpn = params["alpn"] ?: "",
                    fp = params["fp"] ?: "chrome",
                    pbk = params["pbk"] ?: "",
                    sid = params["sid"] ?: "",
                    type = params["type"] ?: "tcp",
                    serviceName = params["serviceName"] ?: ""
                )
            } catch (e: Exception) {
                return null
            }
        }
    }
}

class EasyVpnServer(private val context: Context, private val scope: CoroutineScope) {
    private var process: Process? = null
    private val TAG = "EasyVpnServer"
    private var updateJob: Job? = null

    companion object {
        var isRunning: Boolean = false
            private set
    }

    fun start() {
        stop()

        val userSubsStr = Prefs.easyVpnSubs.trim()
        val defaultSubs = listOf(
            "https://gitverse.ru/api/repos/zieng2/wl/raw/branch/master/list_universal.txt",
            "https://gitverse.ru/api/repos/zieng2/wl/raw/branch/master/list_lite.txt",
            "https://raw.githubusercontent.com/solovyov-jenya2004/all_subs/refs/heads/main/final_sorted",
            "https://raw.githubusercontent.com/zieng2/wl/refs/heads/main/vless_universal.txt",
            "https://raw.githubusercontent.com/zieng2/wl/refs/heads/main/vless_lite.txt"
        )
        val activeSubs = if (userSubsStr.isNotEmpty()) {
            userSubsStr.split("\n", ",").map { it.trim() }.filter { it.isNotEmpty() }
        } else {
            defaultSubs
        }

        val subsHash = activeSubs.joinToString("|").hashCode().toString()

        // 1. Проверяем наличие прямых vless:// ссылок от пользователя
        val directUserNodes = activeSubs
            .filter { it.startsWith("vless://", ignoreCase = true) }
            .mapNotNull { VlessNode.parse(it) }

        // 2. Проверяем кэшированные рабочие ноды для этих подписок пользователя
        val cachedUserNodes = loadUserCachedNodes(subsHash)

        var startedWithUserNodes = false

        if (directUserNodes.isNotEmpty()) {
            Log.i(TAG, "Starting with ${directUserNodes.size} direct user vless nodes")
            val config = buildConfig(directUserNodes)
            startXrayProcess(config)
            startedWithUserNodes = true
        } else if (cachedUserNodes.isNotEmpty()) {
            Log.i(TAG, "Starting with ${cachedUserNodes.size} cached user nodes")
            val config = buildConfig(cachedUserNodes)
            startXrayProcess(config)
            startedWithUserNodes = true
        }

        // 3. Если нод пользователя пока нет (первый запуск / очищен кэш), активируем План «Ъ» (резервные ноды)
        // чтобы трафик пользователя и tun2socks поднялись мгновенно, не дожидаясь скачивания внешних подписок
        if (!startedWithUserNodes) {
            Log.i(TAG, "Activating Plan Ъ (reserve fallback nodes) while fetching user links...")
            val fallbackNodes = loadPlanBReserveNodes()
            if (fallbackNodes.isNotEmpty()) {
                val config = buildConfig(fallbackNodes)
                startXrayProcess(config)
            } else {
                Log.w(TAG, "Plan Ъ reserve nodes not available")
            }
        }

        // 4. В фоне: скачиваем свежие ссылки из подписок пользователя, проверяем пинг и переключаем на ноды пользователя
        updateJob = scope.launch(Dispatchers.IO) {
            try {
                if (startedWithUserNodes) {
                    delay(2000)
                }

                val fetchedLinks = fetchSubscriptionLinks(activeSubs)
                if (fetchedLinks.isEmpty()) {
                    Log.w(TAG, "No links fetched from user subscriptions. Keeping active nodes.")
                    return@launch
                }

                val parsedNodes = fetchedLinks.mapNotNull { VlessNode.parse(it) }
                Log.i(TAG, "Testing ${parsedNodes.size} nodes from user subscriptions...")

                val workingNodes = pingTestNodes(parsedNodes)
                Log.i(TAG, "Working nodes found from user links: ${workingNodes.size}")

                if (workingNodes.isNotEmpty() && isActive) {
                    saveUserCachedNodes(subsHash, workingNodes)
                    Log.i(TAG, "Switching Xray to ${workingNodes.size} verified user nodes (leaving Plan Ъ)")
                    val newConfig = buildConfig(workingNodes)
                    startXrayProcess(newConfig)
                } else if (!isRunning) {
                    // Если у пользователя 0 рабочих нод и ничего не запущено, гарантируем работу Плана «Ъ»
                    Log.w(TAG, "User links yielded 0 working nodes. Falling back to Plan Ъ.")
                    val fallbackNodes = loadPlanBReserveNodes()
                    if (fallbackNodes.isNotEmpty()) {
                        val config = buildConfig(fallbackNodes)
                        startXrayProcess(config)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error updating user subscription nodes", e)
            }
        }
    }

    private suspend fun fetchSubscriptionLinks(subs: List<String>): List<String> = withContext(Dispatchers.IO) {
        val links = mutableSetOf<String>()

        for (item in subs) {
            if (!coroutineContext.isActive) break
            val trimmed = item.trim()
            if (trimmed.isEmpty()) continue

            if (trimmed.startsWith("vless://", ignoreCase = true)) {
                links.add(trimmed)
                continue
            }

            if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
                try {
                    val url = URL(trimmed)
                    val conn = url.openConnection() as HttpURLConnection
                    conn.connectTimeout = 8000
                    conn.readTimeout = 8000
                    conn.setRequestProperty("User-Agent", "v2rayng/1.8.12 (Android; Mobile)")

                    val responseCode = conn.responseCode
                    if (responseCode in 200..299) {
                        val data = conn.inputStream.bufferedReader().readText()
                        val extracted = parseNodesFromContent(data)
                        links.addAll(extracted)
                        Log.i(TAG, "Fetched ${extracted.size} links from $trimmed")
                    } else {
                        Log.w(TAG, "HTTP $responseCode from $trimmed")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to fetch sub: $trimmed (${e.message})")
                }
            }
        }

        links.toList()
    }

    private fun parseNodesFromContent(content: String): List<String> {
        val result = mutableListOf<String>()
        var text = content.trim()

        if (!text.contains("vless://", ignoreCase = true)) {
            try {
                val cleaned = text.replace("\r", "").replace("\n", "").replace(" ", "")
                val padded = cleaned + "=".repeat((4 - cleaned.length % 4) % 4)
                val decoded = String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8)
                if (decoded.contains("vless://", ignoreCase = true)) {
                    text = decoded
                }
            } catch (e: Exception) {
                // ignore
            }
        }

        text.split("\n").forEach { line ->
            val l = line.trim()
            if (l.startsWith("vless://", ignoreCase = true)) {
                result.add(l)
            }
        }
        return result
    }

    private suspend fun pingTestNodes(nodes: List<VlessNode>): List<VlessNode> = withContext(Dispatchers.IO) {
        val working = mutableListOf<VlessNode>()
        val semaphore = Semaphore(50)
        val deferreds = nodes.take(200).map { node ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    if (!coroutineContext.isActive) return@withPermit null
                    try {
                        val sock = Socket()
                        sock.connect(InetSocketAddress(node.server, node.port), 1500)
                        sock.close()
                        node
                    } catch (e: Exception) {
                        null
                    }
                }
            }
        }

        deferreds.awaitAll().forEach {
            if (it != null) working.add(it)
        }
        working
    }

    private fun loadUserCachedNodes(expectedHash: String): List<VlessNode> {
        val file = File(context.filesDir, "easyvpn_user_nodes.json")
        if (!file.exists()) return emptyList()

        try {
            val json = JSONObject(file.readText())
            val savedHash = json.optString("subs_hash", "")
            if (savedHash != expectedHash) {
                Log.d(TAG, "Cached user nodes hash mismatch (saved: $savedHash, expected: $expectedHash)")
                return emptyList()
            }

            val arr = json.optJSONArray("nodes") ?: return emptyList()
            val list = mutableListOf<VlessNode>()
            for (i in 0 until arr.length()) {
                VlessNode.parse(arr.getString(i))?.let { list.add(it) }
            }
            return list
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read user cached nodes", e)
            return emptyList()
        }
    }

    private fun saveUserCachedNodes(subsHash: String, nodes: List<VlessNode>) {
        try {
            val json = JSONObject()
            json.put("subs_hash", subsHash)
            json.put("timestamp", System.currentTimeMillis() / 1000.0)
            val arr = JSONArray()
            nodes.forEach { arr.put(it.originalLink) }
            json.put("nodes", arr)
            val file = File(context.filesDir, "easyvpn_user_nodes.json")
            file.writeText(json.toString(2))
            Log.i(TAG, "Saved ${nodes.size} user nodes to cache")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save user nodes to cache", e)
        }
    }

    private fun loadPlanBReserveNodes(): List<VlessNode> {
        try {
            context.assets.open("easyvpn_default_nodes.json").use { stream ->
                val jsonStr = stream.bufferedReader().readText()
                val json = JSONObject(jsonStr)
                val nodesArray = json.optJSONArray("nodes")
                if (nodesArray != null && nodesArray.length() > 0) {
                    val list = mutableListOf<VlessNode>()
                    for (i in 0 until nodesArray.length()) {
                        val link = nodesArray.getString(i)
                        VlessNode.parse(link)?.let { list.add(it) }
                    }
                    if (list.isNotEmpty()) {
                        Log.i(TAG, "Plan Ъ: Loaded ${list.size} reserve nodes")
                        return list
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Plan Ъ: Failed to load reserve nodes", e)
        }
        return emptyList()
    }

    private fun buildConfig(nodes: List<VlessNode>): JSONObject {
        val outbounds = JSONArray()
        val proxyTags = JSONArray()

        nodes.take(100).forEachIndexed { i, node ->
            try {
                val tag = "proxy-$i"

                val rawType = node.type.lowercase()
                val netType = when (rawType) {
                    "raw", "", "tcp" -> "tcp"
                    "xhttp", "splithttp" -> "splithttp"
                    "httpupgrade" -> "httpupgrade"
                    "ws", "websocket" -> "ws"
                    "grpc" -> "grpc"
                    else -> "tcp"
                }

                var sec = node.security.lowercase()
                if (sec != "reality" && sec != "tls") {
                    sec = "none"
                }

                if (sec == "reality" && node.pbk.isEmpty()) {
                    sec = if (node.sni.isNotEmpty()) "tls" else "none"
                }

                val effectiveFlow = if (netType == "tcp" && (sec == "tls" || sec == "reality") && node.flow.isNotEmpty()) {
                    node.flow
                } else {
                    ""
                }

                val userObj = JSONObject().apply {
                    put("id", node.uuid)
                    put("encryption", if (node.method.isNotEmpty()) node.method else "none")
                    if (effectiveFlow.isNotEmpty()) {
                        put("flow", effectiveFlow)
                    }
                }

                val vnextObj = JSONObject().apply {
                    put("address", node.server)
                    put("port", node.port)
                    put("users", JSONArray().apply { put(userObj) })
                }

                val settingsObj = JSONObject().apply {
                    put("vnext", JSONArray().apply { put(vnextObj) })
                }

                val streamSettings = JSONObject().apply {
                    put("network", netType)
                    put("security", sec)

                    val effectiveSni = if (node.sni.isNotEmpty()) node.sni else node.server
                    val effectiveFp = if (node.fp.isNotEmpty()) node.fp else "chrome"

                    if (sec == "tls") {
                        val tlsObj = JSONObject().apply {
                            put("serverName", effectiveSni)
                            put("fingerprint", effectiveFp)
                            if (node.alpn.isNotEmpty()) {
                                val alpnList = node.alpn.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                                if (alpnList.isNotEmpty()) {
                                    put("alpn", JSONArray(alpnList))
                                }
                            }
                        }
                        put("tlsSettings", tlsObj)
                    } else if (sec == "reality") {
                        val realityObj = JSONObject().apply {
                            put("serverName", effectiveSni)
                            put("fingerprint", effectiveFp)
                            put("publicKey", node.pbk)
                            put("shortId", node.sid)
                            put("spiderX", "")
                        }
                        put("realitySettings", realityObj)
                    }

                    val effectiveHost = if (node.host.isNotEmpty()) node.host else effectiveSni

                    when (netType) {
                        "splithttp" -> {
                            val shObj = JSONObject().apply {
                                put("path", if (node.path.isNotEmpty()) node.path else "/")
                                if (effectiveHost.isNotEmpty()) {
                                    put("host", effectiveHost)
                                }
                            }
                            put("splithttpSettings", shObj)
                            put("xhttpSettings", shObj)
                        }
                        "ws" -> {
                            val wsObj = JSONObject().apply {
                                put("path", if (node.path.isNotEmpty()) node.path else "/")
                                val headers = JSONObject()
                                if (effectiveHost.isNotEmpty()) {
                                    headers.put("Host", effectiveHost)
                                }
                                put("headers", headers)
                            }
                            put("wsSettings", wsObj)
                        }
                        "grpc" -> {
                            val grpcObj = JSONObject().apply {
                                put("serviceName", node.serviceName)
                                put("multiMode", true)
                            }
                            put("grpcSettings", grpcObj)
                        }
                        "httpupgrade" -> {
                            val huObj = JSONObject().apply {
                                put("path", if (node.path.isNotEmpty()) node.path else "/")
                                if (effectiveHost.isNotEmpty()) {
                                    put("host", effectiveHost)
                                }
                            }
                            put("httpupgradeSettings", huObj)
                        }
                    }
                }

                val outbound = JSONObject().apply {
                    put("tag", tag)
                    put("protocol", "vless")
                    put("settings", settingsObj)
                    put("streamSettings", streamSettings)
                }

                outbounds.put(outbound)
                proxyTags.put(tag)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to build node $i", e)
            }
        }

        // Direct outbound
        outbounds.put(JSONObject().apply {
            put("tag", "direct")
            put("protocol", "freedom")
            put("settings", JSONObject())
        })

        return JSONObject().apply {
            put("log", JSONObject().apply {
                put("loglevel", "warning")
            })

            put("inbounds", JSONArray().apply {
                put(JSONObject().apply {
                    put("tag", "socks-in")
                    put("listen", "127.0.0.1")
                    put("port", 1791)
                    put("protocol", "socks")
                    put("settings", JSONObject().apply {
                        put("auth", "noauth")
                        put("udp", true)
                    })
                    put("sniffing", JSONObject().apply {
                        put("enabled", true)
                        put("destOverride", JSONArray().apply {
                            put("http")
                            put("tls")
                        })
                    })
                })
            })

            put("outbounds", outbounds)

            put("routing", JSONObject().apply {
                put("domainStrategy", "AsIs")
                put("balancers", JSONArray().apply {
                    put(JSONObject().apply {
                        put("tag", "balancer")
                        put("selector", JSONArray().apply { put("proxy-") })
                        put("strategy", JSONObject().apply {
                            put("type", "leastPing")
                        })
                    })
                })
                put("rules", JSONArray().apply {
                    put(JSONObject().apply {
                        put("type", "field")
                        put("inboundTag", JSONArray().apply { put("socks-in") })
                        put("balancerTag", "balancer")
                    })
                })
            })

            put("observatory", JSONObject().apply {
                put("subjectSelector", JSONArray().apply { put("proxy-") })
                put("probeUrl", "http://cp.cloudflare.com/generate_204")
                put("probeInterval", "3m")
                put("enableConcurrency", true)
            })
        }
    }

    private fun startXrayProcess(config: JSONObject): Boolean {
        val configFile = File(context.filesDir, "xray_config.json")
        configFile.writeText(config.toString(2))

        val libDir = context.applicationInfo.nativeLibraryDir
        val nativeXray = File(libDir, "libxray.so")

        if (!nativeXray.exists()) {
            Log.e(TAG, "libxray.so not found in $libDir")
            return false
        }

        try {
            process?.destroy()
            process = null
        } catch (e: Exception) {
            // ignore
        }

        val pb = ProcessBuilder(nativeXray.absolutePath, "run", "-c", configFile.absolutePath)
        pb.directory(context.filesDir)
        pb.environment()["xray.location.asset"] = context.filesDir.absolutePath
        pb.redirectErrorStream(true)

        return try {
            val proc = pb.start()
            process = proc
            isRunning = true
            Log.i(TAG, "xray started directly from nativeLibraryDir")

            scope.launch(Dispatchers.IO) {
                try {
                    proc.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            Log.d(TAG, "[xray] $line")
                        }
                    }
                } catch (e: Exception) {
                    // stream closed
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start xray process", e)
            false
        }
    }

    fun stop() {
        updateJob?.cancel()
        updateJob = null
        try {
            process?.destroy()
        } catch (e: Exception) {
            // ignore
        }
        process = null
        isRunning = false
        Log.i(TAG, "xray stopped")
    }
}

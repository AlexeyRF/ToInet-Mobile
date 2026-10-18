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
import java.net.Proxy
import java.net.Socket
import java.net.URL
import java.net.URLDecoder

object EasyVpnUpdater {
    private const val TAG = "EasyVpnUpdater"

    val DEFAULT_SUBS = listOf(
        "https://raw.githubusercontent.com/solovyov-jenya2004/all_subs/refs/heads/main/final_sorted",
        "https://raw.githubusercontent.com/zieng2/wl/refs/heads/main/vless_universal.txt",
        "https://raw.githubusercontent.com/zieng2/wl/refs/heads/main/vless_lite.txt"
    )

    fun getActiveSources(): List<String> {
        val userSubsStr = Prefs.easyVpnSubs.trim()
        if (userSubsStr.isEmpty()) {
            return DEFAULT_SUBS
        }
        val userLines = userSubsStr.split("\n", ",").map { it.trim() }.filter { it.isNotEmpty() }
        // Merge user sources and default sources, preserving user entries
        return (userLines + DEFAULT_SUBS).distinct()
    }

    suspend fun downloadUrlContent(urlStr: String): String? = withContext(Dispatchers.IO) {
        // 1. Попытка прямого скачивания (при наличии прямого доступа или через системный VPN)
        try {
            val url = URL(urlStr)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", "v2rayng/1.8.12 (Android; Mobile)")
            val code = conn.responseCode
            if (code in 200..299) {
                val data = conn.inputStream.bufferedReader().readText()
                if (data.isNotBlank()) return@withContext data
            }
        } catch (e: Exception) {
            Log.d(TAG, "Direct download of $urlStr failed: ${e.message}")
        }

        // 2. Если прямой доступ не сработал, но запущен EasyVPN, скачиваем через локальный SOCKS5 (127.0.0.1:1791)
        if (EasyVpnServer.isRunning) {
            try {
                val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", 1791))
                val url = URL(urlStr)
                val conn = url.openConnection(proxy) as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty("User-Agent", "v2rayng/1.8.12 (Android; Mobile)")
                val code = conn.responseCode
                if (code in 200..299) {
                    val data = conn.inputStream.bufferedReader().readText()
                    if (data.isNotBlank()) return@withContext data
                }
            } catch (e: Exception) {
                Log.d(TAG, "Proxy download of $urlStr via EasyVpn failed: ${e.message}")
            }
        }

        null
    }

    fun parseNodesFromContent(content: String): List<String> {
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
            } catch (_: Exception) {}
        }

        text.split("\n").forEach { line ->
            val l = line.trim()
            if (l.startsWith("vless://", ignoreCase = true)) {
                result.add(l)
            }
        }
        return result
    }

    suspend fun pingTestNodes(nodes: List<VlessNode>): List<VlessNode> = withContext(Dispatchers.IO) {
        val workingWithPing = mutableListOf<Pair<VlessNode, Long>>()
        val semaphore = Semaphore(50)
        val deferreds = nodes.take(300).map { node ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    if (!coroutineContext.isActive) return@withPermit null
                    try {
                        val start = System.currentTimeMillis()
                        val sock = Socket()
                        sock.connect(InetSocketAddress(node.server, node.port), 2000)
                        val rtt = System.currentTimeMillis() - start
                        sock.close()
                        Pair(node, rtt)
                    } catch (_: Exception) {
                        null
                    }
                }
            }
        }

        deferreds.awaitAll().forEach {
            if (it != null) workingWithPing.add(it)
        }

        // Сортировка по минимальному пингу (наиболее быстрые ноды первыми)
        workingWithPing.sortBy { it.second }
        workingWithPing.map { it.first }
    }

    suspend fun updateAndTestConfigs(context: Context): List<VlessNode> = withContext(Dispatchers.IO) {
        val sources = getActiveSources()
        Log.i(TAG, "Updating EasyVPN configs from ${sources.size} sources...")

        val allRawLinks = mutableListOf<String>()

        for (item in sources) {
            if (!isActive) break
            val trimmed = item.trim()
            if (trimmed.isEmpty()) continue

            if (trimmed.startsWith("vless://", ignoreCase = true)) {
                allRawLinks.add(trimmed)
            } else if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
                val data = downloadUrlContent(trimmed)
                if (data != null) {
                    val extracted = parseNodesFromContent(data)
                    allRawLinks.addAll(extracted)
                    Log.i(TAG, "Extracted ${extracted.size} links from $trimmed")
                }
            }
        }

        // Парсинг нод
        val parsedNodes = allRawLinks.mapNotNull { VlessNode.parse(it) }
        Log.i(TAG, "Parsed ${parsedNodes.size} total nodes")

        // Проверка на уникальность (дедупликация)
        val uniqueNodes = parsedNodes.distinctBy { node ->
            "${node.server.lowercase()}:${node.port}@${node.uuid}#${node.path}#${node.pbk}"
        }
        Log.i(TAG, "Unique nodes count: ${uniqueNodes.size}")

        if (uniqueNodes.isEmpty()) {
            return@withContext emptyList<VlessNode>()
        }

        // Тестирование нод
        val workingNodes = pingTestNodes(uniqueNodes)
        Log.i(TAG, "Working unique nodes after ping test: ${workingNodes.size}")

        if (workingNodes.isNotEmpty() && isActive) {
            val subsHash = sources.joinToString("|").hashCode().toString()
            saveUserCachedNodes(context, subsHash, workingNodes)
            Prefs.lastEasyVpnUpdate = System.currentTimeMillis()

            // Если EasyVPN работает прямо сейчас, обновляем конфигурацию на лету
            EasyVpnServer.activeInstance?.reloadWithNodes(workingNodes)
        }

        workingNodes
    }

    fun loadUserCachedNodes(context: Context, expectedHash: String): List<VlessNode> {
        val file = File(context.filesDir, "easyvpn_user_nodes.json")
        if (!file.exists()) return emptyList()

        try {
            val json = JSONObject(file.readText())
            val savedHash = json.optString("subs_hash", "")
            if (savedHash.isNotEmpty() && expectedHash.isNotEmpty() && savedHash != expectedHash) {
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

    fun saveUserCachedNodes(context: Context, subsHash: String, nodes: List<VlessNode>) {
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

    fun loadPlanBReserveNodes(context: Context): List<VlessNode> {
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

    fun buildConfig(nodes: List<VlessNode>, activeNodeIndex: Int = 0): JSONObject {
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
                    put("listen", Prefs.easyVpnProxyHost)
                    put("port", Prefs.easyVpnProxyPort)
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
                if (activeNodeIndex in 0 until nodes.size) {
                    put("rules", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "field")
                            put("inboundTag", JSONArray().apply { put("socks-in") })
                            put("outboundTag", "proxy-$activeNodeIndex")
                        })
                    })
                } else {
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
                }
            })

            put("observatory", JSONObject().apply {
                put("subjectSelector", JSONArray().apply { put("proxy-") })
                put("probeUrl", "http://cp.cloudflare.com/generate_204")
                put("probeInterval", "3m")
                put("enableConcurrency", true)
            })
        }
    }
}

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
        var connDirect: HttpURLConnection? = null
        try {
            val url = URL(urlStr)
            connDirect = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:132.0) Gecko/132.0 Firefox/132.0")
            }
            val code = connDirect.responseCode
            if (code in 200..299) {
                val data = connDirect.inputStream.bufferedReader().readText()
                if (data.isNotBlank()) return@withContext data
            }
        } catch (e: Exception) {
            Log.d(TAG, "Direct download of $urlStr failed: ${e.message}")
        } finally {
            try { connDirect?.disconnect() } catch (_: Exception) {}
        }

        // 2. Если прямой доступ не сработал, но запущен EasyVPN, скачиваем через локальный SOCKS5 (127.0.0.1:easyVpnProxyPort)
        if (EasyVpnServer.isRunning) {
            var connProxy: HttpURLConnection? = null
            try {
                val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", Prefs.easyVpnProxyPort))
                val url = URL(urlStr)
                connProxy = (url.openConnection(proxy) as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:132.0) Gecko/132.0 Firefox/132.0")
                }
                val code = connProxy.responseCode
                if (code in 200..299) {
                    val data = connProxy.inputStream.bufferedReader().readText()
                    if (data.isNotBlank()) return@withContext data
                }
            } catch (e: Exception) {
                Log.d(TAG, "Proxy download of $urlStr via EasyVpn failed: ${e.message}")
            } finally {
                try { connProxy?.disconnect() } catch (_: Exception) {}
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

    suspend fun pingTestNodes(nodes: List<VlessNode>, context: Context? = null): List<VlessNode> = withContext(Dispatchers.IO) {
        val workingWithPing = mutableListOf<Pair<VlessNode, Long>>()
        val semaphore = Semaphore(30)

        val deferreds = nodes.take(300).map { node ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    if (!coroutineContext.isActive) return@withPermit null
                    try {
                        val start = System.currentTimeMillis()
                        Socket().use { s ->
                            s.connect(InetSocketAddress(node.server, node.port), 3000)
                            val rtt = System.currentTimeMillis() - start
                            Pair(node, rtt)
                        }
                    } catch (_: Exception) {
                        null
                    }
                }
            }
        }

        deferreds.awaitAll().forEach {
            if (it != null) workingWithPing.add(it)
        }

        // Сортировка по минимальному пингу с приоритетом VLESS-Reality и отечественных SNI (не блокируемых ТСПУ)
        workingWithPing.sortBy { (node, rtt) ->
            var score = rtt
            val sni = node.sni.lowercase()
            if (node.security.equals("reality", ignoreCase = true)) score -= 1000L
            if (sni.contains("yandex") || sni.contains("vk.") || sni.contains("vk.com") || sni.contains("ok.ru") || sni.contains("max.ru") || sni.contains("sova")) {
                score -= 1500L
            }
            if (sni.contains("papar.online")) {
                score += 5000L
            }
            score
        }
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
        val workingNodes = pingTestNodes(uniqueNodes, context)
        Log.i(TAG, "Working unique nodes after ping test: ${workingNodes.size}")

        val finalNodes = if (workingNodes.isNotEmpty()) {
            workingNodes
        } else {
            Log.w(TAG, "Ping test returned 0 reachable nodes; using top unique parsed nodes as fallback")
            uniqueNodes.take(50)
        }

        if (finalNodes.isNotEmpty() && isActive) {
            val subsHash = sources.joinToString("|").hashCode().toString()
            saveUserCachedNodes(context, subsHash, finalNodes)
            Prefs.lastEasyVpnUpdate = System.currentTimeMillis()

            // Если EasyVPN работает прямо сейчас, обновляем конфигурацию на лету
            EasyVpnServer.activeInstance?.reloadWithNodes(finalNodes)
        }

        finalNodes
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

    fun buildDirectFallbackConfig(): JSONObject {
        val listenHost = Prefs.easyVpnProxyHost.ifBlank { "127.0.0.1" }
        val listenPort = if (Prefs.easyVpnProxyPort in 1..65535) Prefs.easyVpnProxyPort else 1790
        return JSONObject().apply {
            put("log", JSONObject().apply { put("loglevel", "warning") })
            put("dns", JSONObject().apply {
                put("servers", JSONArray().apply {
                    put("77.88.8.8")
                    put("77.88.8.1")
                    put("8.8.8.8")
                    put("8.8.4.4")
                })
            })
            put("inbounds", JSONArray().apply {
                put(JSONObject().apply {
                    put("tag", "socks-in")
                    put("listen", listenHost)
                    put("port", listenPort)
                    put("protocol", "socks")
                    put("settings", JSONObject().apply {
                        put("auth", "noauth")
                        put("udp", true)
                    })
                })
            })
            put("outbounds", JSONArray().apply {
                put(JSONObject().apply {
                    put("tag", "direct")
                    put("protocol", "freedom")
                    put("settings", JSONObject())
                })
            })
            put("routing", JSONObject().apply {
                put("domainStrategy", "AsIs")
                put("rules", JSONArray().apply {
                    put(JSONObject().apply {
                        put("type", "field")
                        put("inboundTag", JSONArray().apply { put("socks-in") })
                        put("outboundTag", "direct")
                    })
                })
            })
        }
    }

    fun buildConfig(nodes: List<VlessNode>, activeNodeIndex: Int = 0): JSONObject {
        return try {
            buildConfigInternal(nodes, activeNodeIndex)
        } catch (e: Exception) {
            Log.e(TAG, "Fatal error generating Xray config, falling back to direct config", e)
            buildDirectFallbackConfig()
        }
    }

    private fun buildConfigInternal(nodes: List<VlessNode>, activeNodeIndex: Int = 0): JSONObject {
        val outbounds = JSONArray()
        val proxyTags = JSONArray()

        val listenHost = Prefs.easyVpnProxyHost.ifBlank { "127.0.0.1" }
        val listenPort = if (Prefs.easyVpnProxyPort in 1..65535) Prefs.easyVpnProxyPort else 1790

        nodes.take(20).forEachIndexed { i, node ->
            try {
                if (node.server.isBlank() || node.port !in 1..65535 || node.uuid.isBlank()) {
                    Log.w(TAG, "Skipping invalid node $i: server='${node.server}', port=${node.port}, uuid='${node.uuid}'")
                    return@forEachIndexed
                }

                val tag = "proxy-$i"

                val rawType = node.type.lowercase().trim()
                val netType = when (rawType) {
                    "raw", "", "tcp" -> "tcp"
                    "xhttp", "splithttp" -> "splithttp"
                    "httpupgrade" -> "httpupgrade"
                    "ws", "websocket" -> "ws"
                    "grpc" -> "grpc"
                    else -> "tcp"
                }

                var sec = node.security.lowercase().trim()
                if (sec != "reality" && sec != "tls") {
                    sec = "none"
                }

                if (sec == "reality" && node.pbk.trim().isEmpty()) {
                    sec = if (node.sni.trim().isNotEmpty()) "tls" else "none"
                }

                val effectiveFlow = if (netType == "tcp" && (sec == "tls" || sec == "reality") && node.flow.contains("vision", ignoreCase = true)) {
                    "xtls-rprx-vision"
                } else {
                    ""
                }

                val userObj = JSONObject().apply {
                    put("id", node.uuid.trim())
                    // VLESS specification requires client encryption to be 'none'
                    put("encryption", "none")
                    if (effectiveFlow.isNotEmpty()) {
                        put("flow", effectiveFlow)
                    }
                }

                val vnextObj = JSONObject().apply {
                    put("address", node.server.trim())
                    put("port", node.port)
                    put("users", JSONArray().apply { put(userObj) })
                }

                val settingsObj = JSONObject().apply {
                    put("vnext", JSONArray().apply { put(vnextObj) })
                }

                val streamSettings = JSONObject().apply {
                    put("network", netType)
                    put("security", sec)

                    val effectiveSni = node.sni.trim().ifEmpty { node.server.trim() }
                    val cleanFp = node.fp.trim().lowercase()
                    val effectiveFp = when (cleanFp) {
                        "chrome", "firefox", "safari", "ios", "android", "edge", "360", "qq", "random", "randomized" -> cleanFp
                        else -> "chrome"
                    }

                    if (sec == "tls") {
                        val tlsObj = JSONObject().apply {
                            if (effectiveSni.isNotEmpty()) {
                                put("serverName", effectiveSni)
                            }
                            put("fingerprint", effectiveFp)
                            if (node.alpn.isNotEmpty() && !node.alpn.equals("null", ignoreCase = true)) {
                                val alpnList = node.alpn.split(",").map { it.trim() }.filter { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
                                if (alpnList.isNotEmpty()) {
                                    put("alpn", JSONArray(alpnList))
                                }
                            }
                        }
                        put("tlsSettings", tlsObj)
                    } else if (sec == "reality") {
                        val realityObj = JSONObject().apply {
                            if (effectiveSni.isNotEmpty()) {
                                put("serverName", effectiveSni)
                            }
                            put("fingerprint", effectiveFp)
                            put("publicKey", node.pbk.trim())

                            val rawSid = node.sid.trim().lowercase()
                            val validSid = if (rawSid.all { it in '0'..'9' || it in 'a'..'f' } && rawSid.length % 2 == 0) rawSid else ""
                            put("shortId", validSid)
                            put("spiderX", if (node.spx.isNotEmpty()) node.spx else "")

                            if (node.alpn.isNotEmpty() && !node.alpn.equals("null", ignoreCase = true)) {
                                val alpnList = node.alpn.split(",").map { it.trim() }.filter { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
                                if (alpnList.isNotEmpty()) {
                                    put("alpn", JSONArray(alpnList))
                                }
                            }
                        }
                        put("realitySettings", realityObj)
                    }

                    val effectiveHost = node.host.trim().ifEmpty { effectiveSni }

                    when (netType) {
                        "splithttp" -> {
                            val shObj = JSONObject().apply {
                                val p = node.path.trim()
                                val effectivePath = if (p.isNotEmpty() && !p.equals("null", ignoreCase = true)) {
                                    if (p.startsWith("/")) p else "/$p"
                                } else "/"
                                put("path", effectivePath)
                                if (effectiveHost.isNotEmpty()) {
                                    put("host", effectiveHost)
                                }
                                val effectiveMode = if (node.mode.trim().isNotEmpty() && !node.mode.equals("null", ignoreCase = true)) node.mode.trim() else "auto"
                                put("mode", effectiveMode)

                                val rawExtra = node.extra.trim()
                                if (rawExtra.isNotEmpty() && !rawExtra.equals("null", ignoreCase = true) && rawExtra.startsWith("{")) {
                                    try {
                                        val extraObj = JSONObject(rawExtra)
                                        val keys = extraObj.keys()
                                        while (keys.hasNext()) {
                                            val k = keys.next()
                                            if (!extraObj.isNull(k)) {
                                                val v = extraObj.get(k)
                                                if (k == "host" || k == "path" || k == "mode") {
                                                    if (optString(k, "").isEmpty()) {
                                                        put(k, v)
                                                    }
                                                } else if (k != "extra" && k != "downloadSettings") {
                                                    put(k, v)
                                                }
                                            }
                                        }
                                    } catch (_: Exception) {}
                                }
                            }
                            put("splithttpSettings", shObj)
                            put("xhttpSettings", shObj)
                        }
                        "ws" -> {
                            val wsObj = JSONObject().apply {
                                val p = node.path.trim()
                                val effectivePath = if (p.isNotEmpty() && !p.equals("null", ignoreCase = true)) {
                                    if (p.startsWith("/")) p else "/$p"
                                } else "/"
                                put("path", effectivePath)
                                val headers = JSONObject()
                                if (effectiveHost.isNotEmpty()) {
                                    val cleanHost = effectiveHost.replace("https://", "").replace("http://", "").split("/")[0].trim()
                                    headers.put("Host", cleanHost)
                                }
                                put("headers", headers)
                            }
                            put("wsSettings", wsObj)
                        }
                        "grpc" -> {
                            val grpcObj = JSONObject().apply {
                                val sName = if (node.serviceName.trim().isNotEmpty() && !node.serviceName.equals("null", ignoreCase = true)) node.serviceName.trim() else "grpc"
                                put("serviceName", sName)
                                put("multiMode", true)
                            }
                            put("grpcSettings", grpcObj)
                        }
                        "httpupgrade" -> {
                            val huObj = JSONObject().apply {
                                val p = node.path.trim()
                                val effectivePath = if (p.isNotEmpty() && !p.equals("null", ignoreCase = true)) {
                                    if (p.startsWith("/")) p else "/$p"
                                } else "/"
                                put("path", effectivePath)
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
                    if (ru.toinet.android.util.Prefs.easyVpnUpstreamEnabled) {
                        put("proxySettings", JSONObject().apply {
                            put("tag", "chain-upstream")
                        })
                    }
                }

                outbounds.put(outbound)
                proxyTags.put(tag)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to build node $i", e)
            }
        }

        if (ru.toinet.android.util.Prefs.easyVpnUpstreamEnabled) {
            val upstreamHost = ru.toinet.android.util.Prefs.easyVpnUpstreamHost.ifBlank { "127.0.0.1" }
            val upstreamPort = if (ru.toinet.android.util.Prefs.easyVpnUpstreamPort in 1..65535) ru.toinet.android.util.Prefs.easyVpnUpstreamPort else 9853
            outbounds.put(JSONObject().apply {
                put("tag", "chain-upstream")
                put("protocol", "socks")
                put("settings", JSONObject().apply {
                    put("servers", JSONArray().apply {
                        put(JSONObject().apply {
                            put("address", upstreamHost)
                            put("port", upstreamPort)
                        })
                    })
                })
            })
        }

        // Direct outbound
        outbounds.put(JSONObject().apply {
            put("tag", "direct")
            put("protocol", "freedom")
            put("settings", JSONObject())
        })

        // Blackhole outbound to silently drop blocked protocols like QUIC/UDP 443
        outbounds.put(JSONObject().apply {
            put("tag", "block")
            put("protocol", "blackhole")
            put("settings", JSONObject().apply {
                put("response", JSONObject().apply {
                    put("type", "none")
                })
            })
        })

        val targetTag = if (activeNodeIndex in 0 until nodes.size) "proxy-$activeNodeIndex" else null
        val hasTarget = targetTag != null && (0 until proxyTags.length()).any { proxyTags.getString(it) == targetTag }
        val effectiveTargetTag = if (hasTarget) targetTag else null

        return JSONObject().apply {
            put("log", JSONObject().apply {
                put("loglevel", "warning")
            })

            put("dns", JSONObject().apply {
                put("servers", JSONArray().apply {
                    put("77.88.8.8")
                    put("77.88.8.1")
                    put("8.8.8.8")
                    put("8.8.4.4")
                })
            })

            if (proxyTags.length() > 0) {
                put("observatory", JSONObject().apply {
                    put("subjectSelector", JSONArray().apply { put("proxy-") })
                    put("probeURL", "https://www.google.com/generate_204")
                    put("probeInterval", "20s")
                    put("enableConcurrency", true)
                })
            }

            put("inbounds", JSONArray().apply {
                put(JSONObject().apply {
                    put("tag", "socks-in")
                    put("listen", listenHost)
                    put("port", listenPort)
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
                val rulesArray = JSONArray()

                // Drop QUIC (UDP 443) traffic so browsers immediately fall back to TCP TLS without resets/hangs
                rulesArray.put(JSONObject().apply {
                    put("type", "field")
                    put("inboundTag", JSONArray().apply { put("socks-in") })
                    put("network", "udp")
                    put("port", "443")
                    put("outboundTag", "block")
                })

                if (proxyTags.length() > 0) {
                    put("balancers", JSONArray().apply {
                        put(JSONObject().apply {
                            put("tag", "balancer")
                            put("selector", JSONArray().apply { put("proxy-") })
                            put("strategy", JSONObject().apply {
                                put("type", "random")
                            })
                            put("fallbackTag", effectiveTargetTag ?: "proxy-0")
                        })
                    })

                    if (effectiveTargetTag != null) {
                        rulesArray.put(JSONObject().apply {
                            put("type", "field")
                            put("inboundTag", JSONArray().apply { put("socks-in") })
                            put("outboundTag", effectiveTargetTag)
                        })
                    } else {
                        rulesArray.put(JSONObject().apply {
                            put("type", "field")
                            put("inboundTag", JSONArray().apply { put("socks-in") })
                            put("balancerTag", "balancer")
                        })
                    }
                } else {
                    rulesArray.put(JSONObject().apply {
                        put("type", "field")
                        put("inboundTag", JSONArray().apply { put("socks-in") })
                        put("outboundTag", "direct")
                    })
                }

                put("rules", rulesArray)
            })
        }
    }
}

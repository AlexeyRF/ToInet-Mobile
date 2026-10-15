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
import java.net.URI
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
        fun parse(str: String): VlessNode? {
            try {
                // Fix missing // if any
                val fixedStr = if (!str.contains("://")) "vless://$str" else str
                val uri = URI(fixedStr)
                if (uri.scheme?.lowercase() != "vless") return null

                val queryParams = mutableMapOf<String, String>()
                uri.rawQuery?.split("&")?.forEach {
                    val parts = it.split("=")
                    if (parts.size == 2) {
                        try {
                            queryParams[URLDecoder.decode(parts[0], "UTF-8")] =
                                URLDecoder.decode(parts[1], "UTF-8")
                        } catch (e: Exception) {
                            queryParams[parts[0]] = parts[1]
                        }
                    }
                }

                return VlessNode(
                    originalLink = str,
                    server = uri.host ?: "",
                    port = if (uri.port != -1) uri.port else 443,
                    uuid = uri.userInfo ?: "",
                    remarks = try { URLDecoder.decode(uri.rawFragment ?: "", "UTF-8") } catch (e: Exception) { uri.rawFragment ?: "" },
                    method = queryParams["encryption"] ?: "none",
                    security = queryParams["security"] ?: "none",
                    host = queryParams["host"] ?: "",
                    path = queryParams["path"] ?: "/",
                    flow = queryParams["flow"] ?: "",
                    sni = queryParams["sni"] ?: "",
                    alpn = queryParams["alpn"] ?: "",
                    fp = queryParams["fp"] ?: "chrome",
                    pbk = queryParams["pbk"] ?: "",
                    sid = queryParams["sid"] ?: "",
                    type = queryParams["type"] ?: "tcp",
                    serviceName = queryParams["serviceName"] ?: ""
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
    private var job: Job? = null

    fun start() {
        job = scope.launch(Dispatchers.IO) {
            try {
                val defaultSubs = listOf(
                    "https://gitverse.ru/api/repos/zieng2/wl/raw/branch/master/list_lite.txt",
                    "https://gitverse.ru/api/repos/zieng2/wl/raw/branch/master/list_universal.txt",
                    "https://raw.githubusercontent.com/zieng2/wl/refs/heads/main/vless_lite.txt",
                    "https://raw.githubusercontent.com/solovyov-jenya2004/all_subs/refs/heads/main/final_sorted",
                    "https://raw.githubusercontent.com/zieng2/wl/refs/heads/main/vless_universal.txt"
                )
                val userSubs = Prefs.easyVpnSubs.split("\n", ",").map { it.trim() }.filter { it.isNotEmpty() }
                val subs = if (userSubs.isNotEmpty()) userSubs else defaultSubs

                val nodes = mutableListOf<String>()
                for (urlStr in subs) {
                    try {
                        if (urlStr.startsWith("vless://")) {
                            nodes.add(urlStr)
                            continue
                        }

                        val url = URL(urlStr)
                        val conn = url.openConnection() as HttpURLConnection
                        conn.connectTimeout = 10000
                        conn.readTimeout = 10000
                        val data = conn.inputStream.bufferedReader().readText()

                        var decoded = data
                        if (!data.contains("vless://")) {
                            try {
                                decoded = String(Base64.decode(data, Base64.DEFAULT))
                            } catch (e: Exception) {
                                // ignore
                            }
                        }

                        decoded.split("\n").forEach { line ->
                            val t = line.trim()
                            if (t.startsWith("vless://")) {
                                nodes.add(t)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to fetch sub: $urlStr", e)
                    }
                }

                if (nodes.isEmpty()) {
                    Log.e(TAG, "No vless nodes found")
                    return@launch
                }

                val parsedNodes = nodes.mapNotNull { VlessNode.parse(it) }

                val workingNodes = mutableListOf<VlessNode>()

                // Limit concurrency to 100 to avoid overwhelming network/DNS and match EVPN-x64 behavior
                val semaphore = Semaphore(100)
                val deferreds = parsedNodes.map { node ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            try {
                                val sock = Socket()
                                // 1500ms timeout like EVPN-x64
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
                    if (it != null) workingNodes.add(it)
                }

                if (workingNodes.isEmpty()) {
                    Log.e(TAG, "No working servers found")
                    return@launch
                }

                buildAndRunXray(workingNodes)
            } catch (e: Exception) {
                Log.e(TAG, "Error in EasyVpnServer", e)
            }
        }
    }

    private fun buildAndRunXray(nodes: List<VlessNode>) {
        val outbounds = JSONArray()
        val proxyTags = JSONArray()

        nodes.take(200).forEachIndexed { i, node ->
            try {
                val tag = "proxy-$i"

                val userObj = JSONObject().apply {
                    put("id", node.uuid)
                    put("encryption", if (node.method.isNotEmpty()) node.method else "none")
                    if (node.flow.isNotEmpty()) {
                        put("flow", node.flow)
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
                    put("network", if (node.type.isNotEmpty()) node.type else "tcp")
                    val sec = if (node.security == "reality" || node.security == "tls") node.security else "none"
                    put("security", sec)

                    if (sec == "tls") {
                        val tlsObj = JSONObject().apply {
                            put("serverName", if (node.sni.isNotEmpty()) node.sni else node.server)
                            if (node.fp.isNotEmpty()) {
                                put("fingerprint", node.fp)
                            }
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
                            put("serverName", if (node.sni.isNotEmpty()) node.sni else node.server)
                            put("fingerprint", if (node.fp.isNotEmpty()) node.fp else "chrome")
                            put("publicKey", node.pbk)
                            put("shortId", node.sid)
                            put("spiderX", "")
                        }
                        put("realitySettings", realityObj)
                    }

                    when (node.type) {
                        "ws" -> {
                            val wsObj = JSONObject().apply {
                                put("path", if (node.path.isNotEmpty()) node.path else "/")
                                val headers = JSONObject()
                                val hostVal = if (node.host.isNotEmpty()) node.host else (if (node.sni.isNotEmpty()) node.sni else node.server)
                                if (hostVal.isNotEmpty()) {
                                    headers.put("Host", hostVal)
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
                                val hostVal = if (node.host.isNotEmpty()) node.host else (if (node.sni.isNotEmpty()) node.sni else node.server)
                                if (hostVal.isNotEmpty()) {
                                    put("host", hostVal)
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

        val config = JSONObject().apply {
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

        val configFile = File(context.filesDir, "xray_config.json")
        configFile.writeText(config.toString(2))

        val libDir = context.applicationInfo.nativeLibraryDir
        val nativeXray = File(libDir, "libxray.so")

        if (!nativeXray.exists()) {
            Log.e(TAG, "xray binary not found in $libDir.")
            return
        }

        val xrayBinary = File(context.filesDir, "xray")
        try {
            nativeXray.copyTo(xrayBinary, overwrite = true)
            xrayBinary.setExecutable(true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy xray binary to filesDir", e)
            return
        }

        val pb = ProcessBuilder(xrayBinary.absolutePath, "run", "-c", configFile.absolutePath)
        pb.directory(context.filesDir)
        pb.redirectErrorStream(true)

        try {
            process = pb.start()
            Log.i(TAG, "xray started")

            scope.launch(Dispatchers.IO) {
                try {
                    process?.inputStream?.bufferedReader()?.useLines { lines ->
                        lines.forEach { line ->
                            Log.d(TAG, "[xray] $line")
                        }
                    }
                } catch (e: Exception) {
                    // stream closed
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start xray process", e)
        }
    }

    fun stop() {
        job?.cancel()
        process?.destroy()
        process = null
        Log.i(TAG, "xray stopped")
    }
}


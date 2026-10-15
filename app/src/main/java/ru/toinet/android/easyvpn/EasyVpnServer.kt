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
                // Hardcoded URLs as requested
                val subs = listOf(
                    "https://gitverse.ru/api/repos/zieng2/wl/raw/branch/master/list_lite.txt",
                    "https://gitverse.ru/api/repos/zieng2/wl/raw/branch/master/list_universal.txt",
                    "https://raw.githubusercontent.com/zieng2/wl/refs/heads/main/vless_lite.txt",
                    "https://raw.githubusercontent.com/solovyov-jenya2004/all_subs/refs/heads/main/final_sorted",
                    "https://raw.githubusercontent.com/zieng2/wl/refs/heads/main/vless_universal.txt"
                )

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

                buildAndRunSingBox(workingNodes)
            } catch (e: Exception) {
                Log.e(TAG, "Error in EasyVpnServer", e)
            }
        }
    }

    private fun buildAndRunSingBox(nodes: List<VlessNode>) {
        val outbounds = JSONArray()
        outbounds.put(JSONObject().apply {
            put("type", "direct")
            put("tag", "direct")
        })

        val proxyTags = JSONArray()

        nodes.take(200).forEachIndexed { i, node ->
            try {
                val tag = "proxy-$i"
                
                val outbound = JSONObject().apply {
                    put("type", "vless")
                    put("tag", tag)
                    put("server", node.server)
                    put("server_port", node.port)
                    put("uuid", node.uuid)
                    put("packet_encoding", "xudp")
                }
                
                if (node.flow.isNotEmpty()) {
                    outbound.put("flow", node.flow)
                }
                
                if (node.security == "tls" || node.security == "reality") {
                    val tlsConfig = JSONObject().apply {
                        put("enabled", true)
                        put("server_name", if (node.sni.isNotEmpty()) node.sni else node.server)
                        put("utls", JSONObject().apply {
                            put("enabled", true)
                            put("fingerprint", if (node.fp.isNotEmpty()) node.fp else "chrome")
                        })
                    }
                    if (node.alpn.isNotEmpty()) {
                        tlsConfig.put("alpn", JSONArray(node.alpn.split(",").map { it.trim() }))
                    }
                    if (node.security == "reality") {
                        tlsConfig.put("reality", JSONObject().apply {
                            put("enabled", true)
                            put("public_key", node.pbk)
                            put("short_id", node.sid)
                        })
                    }
                    outbound.put("tls", tlsConfig)
                }
                
                when (node.type) {
                    "ws" -> {
                        val transport = JSONObject().apply {
                            put("type", "ws")
                            put("path", node.path)
                            if (node.host.isNotEmpty()) {
                                put("headers", JSONObject().apply {
                                    put("Host", node.host)
                                })
                            }
                        }
                        outbound.put("transport", transport)
                    }
                    "grpc" -> {
                        val transport = JSONObject().apply {
                            put("type", "grpc")
                            put("service_name", node.serviceName)
                        }
                        outbound.put("transport", transport)
                    }
                    "httpupgrade" -> {
                        val transport = JSONObject().apply {
                            put("type", "httpupgrade")
                            put("path", node.path)
                            if (node.host.isNotEmpty()) {
                                put("headers", JSONObject().apply {
                                    put("Host", node.host)
                                })
                            }
                        }
                        outbound.put("transport", transport)
                    }
                }
                
                outbounds.put(outbound)
                proxyTags.put(tag)
            } catch (e: Exception) {
                // Ignore parsing errors for individual nodes
                Log.e(TAG, "Failed to build node $i", e)
            }
        }

        val autoOutbound = JSONObject().apply {
            put("type", "urltest")
            put("tag", "auto")
            put("outbounds", proxyTags)
            put("url", "http://cp.cloudflare.com/generate_204")
            put("interval", "3m")
            put("tolerance", 5000)
        }
        
        val newOutbounds = JSONArray()
        newOutbounds.put(autoOutbound)
        for (i in 0 until outbounds.length()) {
            newOutbounds.put(outbounds.get(i))
        }

        val config = JSONObject().apply {
            put("log", JSONObject().apply {
                put("level", "info")
            })
            // Removed local DNS block to allow pdnsd to route DNS over VLESS proxy
            put("inbounds", JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "socks")
                    put("tag", "socks-in")
                    put("listen", "127.0.0.1")
                    put("listen_port", 1791)
                })
            })
            put("outbounds", newOutbounds)
            put("route", JSONObject().apply {
                put("rules", JSONArray().apply {
                    put(JSONObject().apply {
                        put("inbound", "socks-in")
                        put("outbound", "auto")
                    })
                })
                put("auto_detect_interface", true)
                put("final", "auto")
            })
        }

        val configFile = File(context.filesDir, "easyvpn_config.json")
        configFile.writeText(config.toString(2))

        val libDir = context.applicationInfo.nativeLibraryDir
        val nativeSingBox = File(libDir, "libsing-box.so")
        
        if (!nativeSingBox.exists()) {
            Log.e(TAG, "sing-box binary not found in $libDir. Make sure you built the withEasyVpn flavor.")
            return
        }

        val singBoxBinary = File(context.filesDir, "sing-box")
        try {
            nativeSingBox.copyTo(singBoxBinary, overwrite = true)
            singBoxBinary.setExecutable(true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy sing-box binary to filesDir", e)
            return
        }

        val pb = ProcessBuilder(singBoxBinary.absolutePath, "run", "-c", configFile.absolutePath)
        pb.directory(context.filesDir)
        pb.redirectErrorStream(true)
        
        val env = pb.environment()
        env["ENABLE_DEPRECATED_LEGACY_DNS_SERVERS"] = "true"
        env["ENABLE_DEPRECATED_OUTBOUND_DNS_RULE_ITEM"] = "true"
        env["ENABLE_DEPRECATED_MISSING_DOMAIN_RESOLVER"] = "true"
        
        process = pb.start()
        Log.i(TAG, "sing-box started")
    }

    fun stop() {
        job?.cancel()
        process?.destroy()
        process = null
        Log.i(TAG, "sing-box stopped")
    }
}


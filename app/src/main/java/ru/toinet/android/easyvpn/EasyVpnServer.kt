package ru.toinet.android.easyvpn

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.*
import org.json.JSONObject
import ru.toinet.android.R
import ru.toinet.android.util.Prefs
import java.io.File
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
    val serviceName: String,
    val spx: String = "",
    val mode: String = "",
    val extra: String = ""
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
                    } catch (_: Exception) {
                        parts[1]
                    }
                }

                var queryParams = emptyMap<String, String>()
                if (s.contains("?")) {
                    val parts = s.split("?", limit = 2)
                    s = parts[0]
                    val q = parts[1]
                    queryParams = q.split("&").mapNotNull {
                        val kv = it.split("=", limit = 2)
                        if (kv.size == 2) {
                            val key = kv[0].lowercase().trim()
                            val value = try {
                                URLDecoder.decode(kv[1], "UTF-8")
                            } catch (_: Exception) {
                                kv[1]
                            }
                            key to value
                        } else null
                    }.toMap()
                }

                if (!s.contains("@")) return null
                val userParts = s.split("@", limit = 2)
                val uuid = userParts[0].trim()
                if (uuid.isEmpty()) return null

                var hostPort = userParts[1].trim()
                var embeddedPath = ""
                val slashIdx = hostPort.indexOf('/')
                if (slashIdx != -1) {
                    embeddedPath = hostPort.substring(slashIdx)
                    hostPort = hostPort.substring(0, slashIdx)
                }
                hostPort = hostPort.trim()

                val host: String
                val port: Int
                if (hostPort.startsWith("[")) {
                    val endBracket = hostPort.indexOf("]")
                    if (endBracket == -1) return null
                    host = hostPort.substring(1, endBracket)
                    val afterBracket = hostPort.substring(endBracket + 1)
                    if (!afterBracket.startsWith(":")) return null
                    port = afterBracket.substring(1).toIntOrNull() ?: return null
                } else {
                    val lastColon = hostPort.lastIndexOf(':')
                    if (lastColon == -1) return null
                    host = hostPort.substring(0, lastColon).trim()
                    port = hostPort.substring(lastColon + 1).trim().toIntOrNull() ?: return null
                }

                if (host.isEmpty() || port !in 1..65535) return null

                fun cleanParam(key: String, default: String = ""): String {
                    val v = queryParams[key]?.trim() ?: return default
                    return if (v.isEmpty() || v.equals("null", ignoreCase = true)) default else v
                }

                val rawPath = cleanParam("path", "")
                val path = if (rawPath.isNotEmpty()) rawPath else embeddedPath

                return VlessNode(
                    originalLink = raw.trim(),
                    server = host,
                    port = port,
                    uuid = uuid,
                    remarks = remarks,
                    method = cleanParam("encryption", "none"),
                    security = cleanParam("security", "none"),
                    host = cleanParam("host", ""),
                    path = path,
                    flow = cleanParam("flow", ""),
                    sni = cleanParam("sni", ""),
                    alpn = cleanParam("alpn", ""),
                    fp = cleanParam("fp", ""),
                    pbk = cleanParam("pbk", ""),
                    sid = cleanParam("sid", ""),
                    type = cleanParam("type", "tcp"),
                    serviceName = cleanParam("servicename", ""),
                    spx = cleanParam("spx", ""),
                    mode = cleanParam("mode", ""),
                    extra = cleanParam("extra", "")
                )
            } catch (e: Exception) {
                return null
            }
        }
    }
}

class EasyVpnServer(private val context: Context, private val scope: CoroutineScope) {
    @Volatile
    private var process: Process? = null
    @Volatile
    private var isStopped: Boolean = false
    private val TAG = "EasyVpnServer"
    private var updateJob: Job? = null

    private val nodeLock = Any()
    private var allNodes: MutableList<VlessNode> = mutableListOf()
    private var currentNodeIndex: Int = 0
    var onServerChanged: (() -> Unit)? = null
    var onStateChanged: ((running: Boolean, error: String?) -> Unit)? = null

    @Volatile
    var isServerRunning: Boolean = false
        private set

    companion object {
        @Volatile
        var isRunning: Boolean = false
            private set
        @Volatile
        var activeInstance: EasyVpnServer? = null
            private set
    }

    private data class ServerSwitchData(
        val rotatedNodes: List<VlessNode>,
        val currentNode: VlessNode,
        val index: Int,
        val totalCount: Int
    )

    fun getCurrentServerDescription(): String = synchronized(nodeLock) {
        if (allNodes.isEmpty()) return context.getString(R.string.vpn_notification_content)
        val idx = currentNodeIndex.coerceIn(0, allNodes.lastIndex)
        val node = allNodes[idx]
        val name = node.remarks.ifEmpty { node.server }
        return if (ru.toinet.android.util.Prefs.easyVpnServerNumberOnly) {
            "Сервер #${idx + 1}"
        } else {
            "Сервер ${idx + 1}/${allNodes.size}: $name"
        }
    }

    fun switchToNextServer() {
        val switchData = synchronized(nodeLock) {
            if (allNodes.isEmpty()) return@synchronized null
            currentNodeIndex = (currentNodeIndex + 1) % allNodes.size
            val node = allNodes[currentNodeIndex]
            val rotated = allNodes.drop(currentNodeIndex) + allNodes.take(currentNodeIndex)
            ServerSwitchData(rotated, node, currentNodeIndex, allNodes.size)
        }

        if (switchData == null) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "EasyVPN: нет доступных серверов", Toast.LENGTH_SHORT).show()
            }
            return
        }

        Log.i(TAG, "Switching to next server (${switchData.index + 1}/${switchData.totalCount}): ${switchData.currentNode.server}")

        val config = EasyVpnUpdater.buildConfig(switchData.rotatedNodes, activeNodeIndex = 0)
        startXrayProcess(config)

        Handler(Looper.getMainLooper()).post {
            val msg = if (ru.toinet.android.util.Prefs.easyVpnServerNumberOnly) {
                "EasyVPN: сервер #${switchData.index + 1}"
            } else {
                val name = switchData.currentNode.remarks.ifEmpty { switchData.currentNode.server }
                "EasyVPN: сервер ${switchData.index + 1}/${switchData.totalCount} ($name)"
            }
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
        onServerChanged?.invoke()
    }

    fun start(): Boolean {
        stop()
        synchronized(this) {
            isStopped = false
        }
        activeInstance = this

        if (ru.toinet.android.util.Prefs.easyVpnUpstreamEnabled) {
            ru.toinet.android.util.UpstreamProxyCoordinator.ensureUpstreamStarted(
                context,
                ru.toinet.android.util.Prefs.easyVpnUpstreamProvider
            )
        }

        val activeSubs = EasyVpnUpdater.getActiveSources()
        val subsHash = activeSubs.joinToString("|").hashCode().toString()

        // 1. Проверяем наличие прямых vless:// ссылок от пользователя
        val directUserNodes = activeSubs
            .filter { it.startsWith("vless://", ignoreCase = true) }
            .mapNotNull { VlessNode.parse(it) }

        // 2. Проверяем кэшированные рабочие ноды для этих подписок
        val cachedUserNodes = EasyVpnUpdater.loadUserCachedNodes(context, subsHash)

        var startedWithUserNodes = false
        var startedOk = false

        if (directUserNodes.isNotEmpty()) {
            Log.i(TAG, "Starting with ${directUserNodes.size} direct user vless nodes")
            val nodes = synchronized(nodeLock) {
                allNodes = directUserNodes.toMutableList()
                currentNodeIndex = 0
                allNodes.toList()
            }
            val config = EasyVpnUpdater.buildConfig(nodes, activeNodeIndex = 0)
            startedOk = startXrayProcess(config)
            startedWithUserNodes = true
        } else if (cachedUserNodes.isNotEmpty()) {
            Log.i(TAG, "Starting with ${cachedUserNodes.size} cached user nodes")
            val nodes = synchronized(nodeLock) {
                allNodes = cachedUserNodes.toMutableList()
                currentNodeIndex = 0
                allNodes.toList()
            }
            val config = EasyVpnUpdater.buildConfig(nodes, activeNodeIndex = 0)
            startedOk = startXrayProcess(config)
            startedWithUserNodes = true
        }

        // 3. Если нод пользователя пока нет (первый запуск / очищен кэш), активируем План «Ъ» (резервные ноды)
        // чтобы трафик пользователя и tun2socks поднялись мгновенно, не дожидаясь скачивания внешних подписок
        if (!startedWithUserNodes) {
            Log.i(TAG, "Activating Plan Ъ (reserve fallback nodes) while fetching user links...")
            val fallbackNodes = EasyVpnUpdater.loadPlanBReserveNodes(context)
            if (fallbackNodes.isNotEmpty()) {
                val nodes = synchronized(nodeLock) {
                    allNodes = fallbackNodes.toMutableList()
                    currentNodeIndex = 0
                    allNodes.toList()
                }
                val config = EasyVpnUpdater.buildConfig(nodes, activeNodeIndex = 0)
                startedOk = startXrayProcess(config)
            } else {
                Log.w(TAG, "Plan Ъ reserve nodes not available, starting with fallback direct config")
                val fallbackConfig = EasyVpnUpdater.buildDirectFallbackConfig()
                startedOk = startXrayProcess(fallbackConfig)
            }
        }

        // 4. В фоне: если кэш пуст или прошло более 2 часов с последнего обновления, скачиваем и тестируем
        val lastUpdate = Prefs.lastEasyVpnUpdate
        val updateIntervalMs = 2 * 60 * 60 * 1000L
        if (cachedUserNodes.isEmpty() || System.currentTimeMillis() - lastUpdate > updateIntervalMs) {
            updateJob = scope.launch(Dispatchers.IO) {
                if (startedWithUserNodes) {
                    delay(2000)
                }
                EasyVpnUpdater.updateAndTestConfigs(context)
            }
        }
        return startedOk
    }

    fun reloadWithNodes(nodes: List<VlessNode>) {
        if (!isRunning) return
        val (rotated, total) = synchronized(nodeLock) {
            allNodes = nodes.toMutableList()
            currentNodeIndex = currentNodeIndex.coerceIn(0, (allNodes.size - 1).coerceAtLeast(0))
            val rotatedNodes = if (allNodes.isNotEmpty()) allNodes.drop(currentNodeIndex) + allNodes.take(currentNodeIndex) else allNodes
            Pair(rotatedNodes, allNodes.size)
        }
        Log.i(TAG, "Reloading Xray with $total verified nodes")
        val targetIdx = 0
        val config = EasyVpnUpdater.buildConfig(rotated, activeNodeIndex = targetIdx)
        startXrayProcess(config)
        onServerChanged?.invoke()
    }

    @Synchronized
    private fun startXrayProcess(config: JSONObject): Boolean {
        if (isStopped) {
            Log.w(TAG, "Cannot start Xray process: server is stopped")
            return false
        }
        return try {
            val configFile = File(context.filesDir, "xray_config.json")
            configFile.writeText(config.toString(2))

            val libDir = context.applicationInfo.nativeLibraryDir
            val nativeXray = File(libDir, "libxray.so")

            if (!nativeXray.exists()) {
                Log.e(TAG, "libxray.so not found in $libDir")
                return false
            }
            try {
                nativeXray.setExecutable(true, false)
            } catch (_: Exception) {}

            val oldProc = process
            if (oldProc != null) {
                oldProc.destroyForcibly()
                process = null
                try {
                    Thread.sleep(100)
                } catch (_: Exception) {}
            }

            val pb = ProcessBuilder(nativeXray.absolutePath, "run", "-c", configFile.absolutePath)
            pb.directory(context.filesDir)
            pb.environment()["xray.location.asset"] = context.filesDir.absolutePath
            pb.redirectErrorStream(true)

            val proc = pb.start()
            process = proc
            Log.i(TAG, "xray started directly from nativeLibraryDir")

            scope.launch(Dispatchers.IO) {
                try {
                    proc.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            Log.d(TAG, "[xray] $line")
                            ru.toinet.android.util.NotificationLogger.log(context, "easyvpn", line)
                        }
                    }
                } catch (_: Exception) {}
                val exitCode = try { proc.waitFor() } catch (_: Exception) { -1 }
                Log.w(TAG, "xray process exited with code $exitCode")
                if (process == proc) {
                    val wasStopped = isStopped
                    isServerRunning = false
                    if (activeInstance == this@EasyVpnServer) {
                        isRunning = false
                    }
                    if (!wasStopped) {
                        onStateChanged?.invoke(false, "xray exited with code $exitCode")
                    }
                }
            }

            val listenPort = if (Prefs.easyVpnProxyPort in 1..65535) Prefs.easyVpnProxyPort else 1790
            val waitStart = System.currentTimeMillis()
            var bound = false
            var processAlive = true
            while (System.currentTimeMillis() - waitStart < 2500L) {
                processAlive = proc.isAlive
                if (!processAlive || isStopped) break
                try {
                    java.net.Socket().use { s ->
                        s.connect(java.net.InetSocketAddress("127.0.0.1", listenPort), 100)
                        bound = true
                    }
                } catch (_: Exception) {}
                if (bound) break
                try { Thread.sleep(50) } catch (_: InterruptedException) { break }
            }

            if (!processAlive || isStopped) {
                isServerRunning = false
                if (activeInstance == this) {
                    isRunning = false
                }
                false
            } else {
                if (!bound) {
                    Log.w(TAG, "xray process is alive after 2500ms, port $listenPort not open yet")
                }
                synchronized(this) {
                    if (isStopped) {
                        isServerRunning = false
                        if (activeInstance == this) isRunning = false
                        return false
                    }
                    isServerRunning = true
                    isRunning = true
                }
                onStateChanged?.invoke(true, null)
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start xray process", e)
            isServerRunning = false
            if (activeInstance == this) {
                isRunning = false
            }
            false
        }
    }

    @Synchronized
    fun stop() {
        isStopped = true
        isServerRunning = false
        updateJob?.cancel()
        updateJob = null
        if (activeInstance == this) {
            isRunning = false
            activeInstance = null
        }
        synchronized(nodeLock) {
            allNodes.clear()
            currentNodeIndex = 0
        }
        try {
            val oldProc = process
            if (oldProc != null) {
                oldProc.destroyForcibly()
                try { oldProc.waitFor(500, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        process = null
        Log.i(TAG, "xray stopped")
    }
}

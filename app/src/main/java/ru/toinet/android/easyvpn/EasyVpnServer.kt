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
    val spx: String = ""
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
                        if (kv.size == 2) kv[0].lowercase() to URLDecoder.decode(kv[1], "UTF-8") else null
                    }.toMap()
                }

                if (!s.contains("@")) return null
                val userParts = s.split("@", limit = 2)
                val uuid = userParts[0]
                val hostPort = userParts[1]

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
                    val hp = hostPort.split(":")
                    if (hp.size != 2) return null
                    host = hp[0]
                    port = hp[1].toIntOrNull() ?: return null
                }

                return VlessNode(
                    originalLink = raw.trim(),
                    server = host,
                    port = port,
                    uuid = uuid,
                    remarks = remarks,
                    method = queryParams["encryption"] ?: "none",
                    security = queryParams["security"] ?: "none",
                    host = queryParams["host"] ?: "",
                    path = queryParams["path"] ?: "",
                    flow = queryParams["flow"] ?: "",
                    sni = queryParams["sni"] ?: "",
                    alpn = queryParams["alpn"] ?: "",
                    fp = queryParams["fp"] ?: "",
                    pbk = queryParams["pbk"] ?: "",
                    sid = queryParams["sid"] ?: "",
                    type = queryParams["type"] ?: "tcp",
                    serviceName = queryParams["servicename"] ?: "",
                    spx = queryParams["spx"] ?: ""
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

    private var allNodes: MutableList<VlessNode> = mutableListOf()
    private var currentNodeIndex: Int = 0
    var onServerChanged: (() -> Unit)? = null

    companion object {
        var isRunning: Boolean = false
            private set
        var activeInstance: EasyVpnServer? = null
            private set
    }

    fun getCurrentServerDescription(): String {
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
        if (allNodes.isEmpty()) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "EasyVPN: нет доступных серверов", Toast.LENGTH_SHORT).show()
            }
            return
        }
        currentNodeIndex = (currentNodeIndex + 1) % allNodes.size
        val currentNode = allNodes[currentNodeIndex]
        Log.i(TAG, "Switching to next server ($currentNodeIndex/${allNodes.size}): ${currentNode.server}")

        val rotatedNodes = allNodes.drop(currentNodeIndex) + allNodes.take(currentNodeIndex)
        val config = EasyVpnUpdater.buildConfig(rotatedNodes, activeNodeIndex = 0)
        startXrayProcess(config)

        Handler(Looper.getMainLooper()).post {
            val msg = if (ru.toinet.android.util.Prefs.easyVpnServerNumberOnly) {
                "EasyVPN: сервер #${currentNodeIndex + 1}"
            } else {
                val name = currentNode.remarks.ifEmpty { currentNode.server }
                "EasyVPN: сервер ${currentNodeIndex + 1}/${allNodes.size} ($name)"
            }
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
        onServerChanged?.invoke()
    }

    fun start() {
        stop()
        activeInstance = this

        val activeSubs = EasyVpnUpdater.getActiveSources()
        val subsHash = activeSubs.joinToString("|").hashCode().toString()

        // 1. Проверяем наличие прямых vless:// ссылок от пользователя
        val directUserNodes = activeSubs
            .filter { it.startsWith("vless://", ignoreCase = true) }
            .mapNotNull { VlessNode.parse(it) }

        // 2. Проверяем кэшированные рабочие ноды для этих подписок
        val cachedUserNodes = EasyVpnUpdater.loadUserCachedNodes(context, subsHash)

        var startedWithUserNodes = false

        if (directUserNodes.isNotEmpty()) {
            Log.i(TAG, "Starting with ${directUserNodes.size} direct user vless nodes")
            allNodes = directUserNodes.toMutableList()
            currentNodeIndex = 0
            val config = EasyVpnUpdater.buildConfig(allNodes, activeNodeIndex = 0)
            startXrayProcess(config)
            startedWithUserNodes = true
        } else if (cachedUserNodes.isNotEmpty()) {
            Log.i(TAG, "Starting with ${cachedUserNodes.size} cached user nodes")
            allNodes = cachedUserNodes.toMutableList()
            currentNodeIndex = 0
            val config = EasyVpnUpdater.buildConfig(allNodes, activeNodeIndex = 0)
            startXrayProcess(config)
            startedWithUserNodes = true
        }

        // 3. Если нод пользователя пока нет (первый запуск / очищен кэш), активируем План «Ъ» (резервные ноды)
        // чтобы трафик пользователя и tun2socks поднялись мгновенно, не дожидаясь скачивания внешних подписок
        if (!startedWithUserNodes) {
            Log.i(TAG, "Activating Plan Ъ (reserve fallback nodes) while fetching user links...")
            val fallbackNodes = EasyVpnUpdater.loadPlanBReserveNodes(context)
            if (fallbackNodes.isNotEmpty()) {
                allNodes = fallbackNodes.toMutableList()
                currentNodeIndex = 0
                val config = EasyVpnUpdater.buildConfig(allNodes, activeNodeIndex = 0)
                startXrayProcess(config)
            } else {
                Log.w(TAG, "Plan Ъ reserve nodes not available")
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
    }

    fun reloadWithNodes(nodes: List<VlessNode>) {
        if (!isRunning) return
        Log.i(TAG, "Reloading Xray with ${nodes.size} verified nodes")
        allNodes = nodes.toMutableList()
        currentNodeIndex = currentNodeIndex.coerceIn(0, (allNodes.size - 1).coerceAtLeast(0))
        val rotatedNodes = if (allNodes.isNotEmpty()) allNodes.drop(currentNodeIndex) + allNodes.take(currentNodeIndex) else allNodes
        val config = EasyVpnUpdater.buildConfig(rotatedNodes, activeNodeIndex = 0)
        startXrayProcess(config)
        onServerChanged?.invoke()
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
        } catch (_: Exception) {}

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
                } catch (_: Exception) {}
                val exitCode = try { proc.waitFor() } catch (_: Exception) { -1 }
                Log.w(TAG, "xray process exited with code $exitCode")
                if (process == proc) {
                    isRunning = false
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
        if (activeInstance == this) {
            activeInstance = null
        }
        allNodes.clear()
        currentNodeIndex = 0
        try {
            process?.destroy()
        } catch (_: Exception) {}
        process = null
        isRunning = false
        Log.i(TAG, "xray stopped")
    }
}

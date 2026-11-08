package ru.toinet.android.tgws

import kotlinx.coroutines.*
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

class TgwsPool(
    private val poolSize: Int = 4,
    private val bufferSize: Int = 256 * 1024,
    private val frontingSni: String = "",
    private val disableSecure: Boolean = false,
    private val scope: CoroutineScope
) {
    data class WsKey(val dc: Int, val isMedia: Boolean, val isTestDc: Boolean)
    data class PooledWs(val ws: RawWebSocket, val createdAt: Long)
    data class PooledWorkerWs(val ws: RawWebSocket, val createdAt: Long, val domain: String)

    private val wsIdle = ConcurrentHashMap<WsKey, ConcurrentLinkedQueue<PooledWs>>()
    private val wsRefilling = ConcurrentHashMap<WsKey, Job>()

    private val cfIdle = ConcurrentHashMap<Int, ConcurrentLinkedQueue<PooledWorkerWs>>()
    private val cfRefilling = ConcurrentHashMap<Int, Job>()

    private var rotationJob: Job? = null
    private var tryFrontingFirst = false

    companion object {
        const val WS_MAX_AGE_MS = 120_000L
        const val CF_WORKER_MAX_AGE_MS = 100_000L
    }

    init {
        startRotation()
    }

    private fun startRotation() {
        rotationJob?.cancel()
        rotationJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(5000L)
                val now = System.currentTimeMillis()

                // Rotate WS Pool
                for ((key, queue) in wsIdle) {
                    val iterator = queue.iterator()
                    while (iterator.hasNext()) {
                        val item = iterator.next()
                        if (now - item.createdAt >= WS_MAX_AGE_MS || item.ws.isClosed) {
                            iterator.remove()
                            item.ws.close()
                        }
                    }
                }

                // Rotate CF Worker Pool
                for ((dc, queue) in cfIdle) {
                    val iterator = queue.iterator()
                    while (iterator.hasNext()) {
                        val item = iterator.next()
                        if (now - item.createdAt >= CF_WORKER_MAX_AGE_MS || item.ws.isClosed) {
                            iterator.remove()
                            item.ws.close()
                        }
                    }
                }
            }
        }
    }

    suspend fun getWs(
        dc: Int,
        isMedia: Boolean,
        isTestDc: Boolean,
        targetIp: String?,
        domains: List<String>
    ): RawWebSocket? {
        if (targetIp == null || poolSize <= 0) return null
        val key = WsKey(dc, isMedia, isTestDc)
        val queue = wsIdle.computeIfAbsent(key) { ConcurrentLinkedQueue() }

        val now = System.currentTimeMillis()
        while (true) {
            val item = queue.poll() ?: break
            if (now - item.createdAt < WS_MAX_AGE_MS && !item.ws.isClosed) {
                scheduleWsRefill(key, targetIp, domains)
                return item.ws
            }
            item.ws.close()
        }

        scheduleWsRefill(key, targetIp, domains)
        return null
    }

    private fun scheduleWsRefill(key: WsKey, targetIp: String, domains: List<String>) {
        if (poolSize <= 0) return
        val existing = wsRefilling[key]
        if (existing != null && existing.isActive) return

        val job = scope.launch(Dispatchers.IO) {
            try {
                val queue = wsIdle.computeIfAbsent(key) { ConcurrentLinkedQueue() }
                val needed = poolSize - queue.size
                if (needed <= 0) return@launch

                val path = if (key.isTestDc) "/apiws_test" else "/apiws"
                for (i in 0 until needed) {
                    if (!isActive) break
                    val ws = connectOneWs(targetIp, domains, path)
                    if (ws != null) {
                        queue.offer(PooledWs(ws, System.currentTimeMillis()))
                    }
                }
            } finally {
                wsRefilling.remove(key)
            }
        }
        wsRefilling[key] = job
    }

    private fun connectOneWs(targetIp: String, domains: List<String>, path: String): RawWebSocket? {
        for (domain in domains) {
            val modes = if (tryFrontingFirst && frontingSni.isNotBlank()) listOf(true, false) else listOf(false, true)
            for (fronted in modes) {
                val sni = if (fronted && frontingSni.isNotBlank()) frontingSni else domain
                try {
                    val ws = RawWebSocket.connect(
                        host = targetIp,
                        domain = domain,
                        timeoutMs = if (fronted) 7000 else 8000,
                        path = path,
                        sni = sni,
                        secure = true,
                        bufferSize = bufferSize
                    )
                    tryFrontingFirst = fronted
                    return ws
                } catch (_: Exception) {}
            }
        }
        return null
    }

    suspend fun getCfWorker(
        dc: Int,
        fallbackDst: String,
        workerDomains: List<String>
    ): Pair<RawWebSocket, String>? {
        if (workerDomains.isEmpty() || poolSize <= 0) return null
        val queue = cfIdle.computeIfAbsent(dc) { ConcurrentLinkedQueue() }

        val now = System.currentTimeMillis()
        while (true) {
            val item = queue.poll() ?: break
            if (now - item.createdAt < CF_WORKER_MAX_AGE_MS && !item.ws.isClosed) {
                scheduleCfWorkerRefill(dc, fallbackDst, workerDomains)
                return Pair(item.ws, item.domain)
            }
            item.ws.close()
        }

        scheduleCfWorkerRefill(dc, fallbackDst, workerDomains)
        return null
    }

    private fun scheduleCfWorkerRefill(dc: Int, fallbackDst: String, workerDomains: List<String>) {
        if (workerDomains.isEmpty() || poolSize <= 0) return
        val existing = cfRefilling[dc]
        if (existing != null && existing.isActive) return

        val job = scope.launch(Dispatchers.IO) {
            try {
                val queue = cfIdle.computeIfAbsent(dc) { ConcurrentLinkedQueue() }
                if (queue.size >= 1) return@launch

                val query = "dst=" + URLEncoder.encode(fallbackDst, "UTF-8") + "&dc=$dc"
                val path = "/apiws?$query"

                for (workerDomain in workerDomains.shuffled()) {
                    if (!isActive) break
                    try {
                        val ws = RawWebSocket.connect(
                            host = workerDomain,
                            domain = workerDomain,
                            timeoutMs = 8000,
                            path = path,
                            secure = !disableSecure,
                            bufferSize = bufferSize
                        )
                        queue.offer(PooledWorkerWs(ws, System.currentTimeMillis(), workerDomain))
                        break
                    } catch (_: Exception) {}
                }
            } finally {
                cfRefilling.remove(dc)
            }
        }
        cfRefilling[dc] = job
    }

    fun warmup(dcMappings: Map<Int, String>, isTestDc: Boolean = false) {
        for ((dc, targetIp) in dcMappings) {
            for (isMedia in listOf(false, true)) {
                val domains = TgwsConfig.wsDomains(dc, isMedia)
                val key = WsKey(dc, isMedia, isTestDc)
                scheduleWsRefill(key, targetIp, domains)
            }
        }
    }

    fun close() {
        rotationJob?.cancel()
        for (job in wsRefilling.values) job.cancel()
        for (job in cfRefilling.values) job.cancel()
        wsRefilling.clear()
        cfRefilling.clear()

        for (queue in wsIdle.values) {
            while (true) {
                val item = queue.poll() ?: break
                item.ws.close()
            }
        }
        wsIdle.clear()

        for (queue in cfIdle.values) {
            while (true) {
                val item = queue.poll() ?: break
                item.ws.close()
            }
        }
        cfIdle.clear()
    }
}

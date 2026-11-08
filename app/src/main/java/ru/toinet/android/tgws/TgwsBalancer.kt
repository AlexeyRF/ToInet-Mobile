package ru.toinet.android.tgws

import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class TgwsBalancer {
    private var domains: List<String> = emptyList()
    private val dcToDomain = ConcurrentHashMap<Int, String>()
    private var refreshJob: Job? = null
    private val isRefreshing = AtomicBoolean(false)

    init {
        updateDomainsList(TgwsConfig.DEFAULT_CF_PROXY_DOMAINS)
    }

    @Synchronized
    fun updateDomainsList(domainsList: List<String>) {
        val normalized = domainsList
            .map { it.trim().lowercase() }
            .filter { TgwsConfig.isValidDomain(it) }
            .distinct()

        if (normalized.isEmpty() || domains == normalized) return

        domains = normalized
        for (dc in listOf(1, 2, 3, 4, 5, 203)) {
            dcToDomain[dc] = domains.random()
        }
    }

    fun updateDomainForDc(dc: Int, domain: String): Boolean {
        val current = dcToDomain[dc]
        if (current == domain) return false
        dcToDomain[dc] = domain
        return true
    }

    fun getDomainsForDc(dc: Int): List<String> {
        val result = mutableListOf<String>()
        val current = dcToDomain[dc]
        if (current != null) {
            result.add(current)
        }
        val shuffled = domains.shuffled()
        for (d in shuffled) {
            if (d != current) {
                result.add(d)
            }
        }
        return result
    }

    fun startAutoRefresh(scope: CoroutineScope) {
        if (refreshJob != null) return
        refreshJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    refreshDomainsFromGithub()
                } catch (_: Exception) {}
                delay(3600_000L) // Refresh every hour
            }
        }
    }

    fun stopAutoRefresh() {
        refreshJob?.cancel()
        refreshJob = null
    }

    private fun refreshDomainsFromGithub() {
        if (!isRefreshing.compareAndSet(false, true)) return
        try {
            val randomParam = (1..7).map { (('a'..'z') + ('A'..'Z')).random() }.joinToString("")
            val url = URL("${TgwsConfig.CFPROXY_DOMAINS_URL}?$randomParam")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 10000
                setRequestProperty("User-Agent", "tg-ws-proxy-android")
            }
            conn.connect()
            if (conn.responseCode == 200) {
                val lines = conn.inputStream.bufferedReader().use { it.readLines() }
                val fetched = lines
                    .map { it.trim() }
                    .filter { it.isNotBlank() && !it.startsWith("#") }
                    .map { TgwsConfig.decodeCfDomain(it) }
                    .filter { TgwsConfig.isValidDomain(it) }
                    .distinct()

                if (fetched.size >= 3) {
                    updateDomainsList(fetched)
                }
            }
            conn.disconnect()
        } catch (_: Exception) {
        } finally {
            isRefreshing.set(false)
        }
    }
}

package ru.toinet.android.tgws

import android.util.Log
import ru.toinet.android.util.Prefs

object UniversalTgProxy {
    private var core: TgwsCore? = null
    private const val TAG = "UniversalTgProxy"

    private val IP_TO_DC = mapOf(
        1 to "149.154.175.50",
        2 to "149.154.167.51",
        3 to "149.154.175.100",
        4 to "149.154.167.91",
        5 to "149.154.171.5"
    )

    fun start() {
        if (core != null) return
        try {
            val listenHost = if (Prefs.openProxyOnAllInterfaces) "0.0.0.0" else "127.0.0.1"
            core = TgwsCore(
                host = listenHost,
                port = 1082,
                dcMappings = IP_TO_DC,
                secret = Prefs.tgwsSecret,
                fakeTlsDomain = Prefs.tgwsFakeTls,
                useByeDpi = true,
                disableWebSockets = false,
                cfWorkerDomains = Prefs.tgwsCfWorkerDomains,
                cfProxyDomains = Prefs.tgwsCfProxyDomains,
                fallbackCfProxy = Prefs.tgwsFallbackCfProxy,
                cfProxyH2Media = Prefs.tgwsCfProxyH2Media,
                frontingSni = Prefs.tgwsFrontingSni,
                disableSecure = Prefs.tgwsDisableSecure,
                forceTestDc = Prefs.tgwsForceTestDc,
                poolSize = Prefs.tgwsPoolSize,
                onLog = { msg -> Log.d(TAG, msg) }
            )
            core?.start()
            Log.i(TAG, "Started on port 1082 as MTProto-to-SOCKS bridge")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting UniversalTgProxy", e)
        }
    }

    fun stop() {
        try {
            core?.stop()
        } catch (_: Exception) {}
        core = null
        Log.i(TAG, "Stopped")
    }
}

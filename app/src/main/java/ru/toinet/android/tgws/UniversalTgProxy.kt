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
        5 to "91.108.56.116"
    )

    fun start() {
        /* MTProto proxy is disabled because it does not work.
        if (core != null) return
        try {
            core = TgwsCore(
                host = "0.0.0.0",
                port = 1082,
                dcMappings = IP_TO_DC,
                secret = Prefs.tgwsSecret,
                fakeTlsDomain = "",
                useByeDpi = true, // this enables the vpnProvider check in createSocket!
                disableWebSockets = true,
                onLog = { msg -> Log.d(TAG, msg) }
            )
            core?.start()
            Log.i(TAG, "Started on port 1082 as MTProto-to-SOCKS bridge")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting UniversalTgProxy", e)
        }
        */
    }

    fun stop() {
        /* MTProto proxy is disabled because it does not work.
        try {
            core?.stop()
        } catch (e: Exception) {}
        core = null
        Log.i(TAG, "Stopped")
        */
    }
}

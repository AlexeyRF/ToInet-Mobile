package ru.toinet.android.tgws

import android.util.Log

object GatikNative {
    private const val TAG = "GatikNative"
    var isLoaded = false
        private set

    init {
        try {
            System.loadLibrary("tgws")
            isLoaded = true
            Log.i(TAG, "Native Gatik C++ loaded successfully")
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to load native libtgws: ${e.message}")
        }
    }

    external fun nativeStartGatik(
        host: String,
        port: Int,
        torHost: String,
        torPort: Int,
        tgwsHost: String,
        tgwsPort: Int
    ): Boolean

    external fun nativeStopGatik()

    external fun nativeIsRunning(): Boolean
}

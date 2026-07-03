package ru.toinet.android.operaproxy

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast
import ru.toinet.android.util.Prefs
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlin.concurrent.thread

class OperaProxyService : Service() {
    private var process: Process? = null

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "OperaProxyService"
        const val ACTION_START = "ru.toinet.android.operaproxy.START"
        const val ACTION_STOP = "ru.toinet.android.operaproxy.STOP"

        fun start(context: Context) {
            val intent = Intent(context, OperaProxyService::class.java).apply {
                action = ACTION_START
            }
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, OperaProxyService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startInternal()
            ACTION_STOP -> {
                stopInternal()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun extractBinary(): String? {
        val supportedAbis = Build.SUPPORTED_ABIS
        val isArm64 = supportedAbis.contains("arm64-v8a")
        val isArm = supportedAbis.contains("armeabi-v7a") || supportedAbis.contains("armeabi")

        val assetName = when {
            isArm64 -> "opera-proxy/opera-proxy.arm64"
            isArm -> "opera-proxy/opera-proxy.arm"
            else -> {
                val currentArch = supportedAbis.firstOrNull() ?: "unknown"
                val msg = "Ошибка: opera-proxy скомпилирован только для arm. Ваша архитектура: $currentArch."
                Log.e(TAG, msg)
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
                }
                return null
            }
        }

        val destFile = File(filesDir, "opera-proxy")
        try {
            assets.open(assetName).use { inputStream ->
                FileOutputStream(destFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            destFile.setExecutable(true, false)
            return destFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract opera-proxy", e)
            return null
        }
    }

    private fun startInternal() {
        stopInternal()

        val executable = extractBinary() ?: run {
            stopSelf()
            return
        }

        val cmdArgs = mutableListOf<String>()
        cmdArgs.add(executable)
        
        val bindAddress = Prefs.operaProxyBindAddress.takeIf { it.isNotBlank() } ?: "127.0.0.1:1888"
        cmdArgs.add("-bind-address")
        cmdArgs.add(bindAddress)
        
        val upstream = Prefs.operaProxyUpstream.takeIf { it.isNotBlank() }
        if (upstream != null) {
            cmdArgs.add("-upstream")
            cmdArgs.add(upstream)
        }

        if (Prefs.operaProxyVerbose) {
            cmdArgs.add("-v")
        }

        try {
            val pb = ProcessBuilder(cmdArgs)
            if (Prefs.operaProxyUseByeDpi) {
                pb.environment()["ALL_PROXY"] = "socks5://127.0.0.1:1080"
                pb.environment()["all_proxy"] = "socks5://127.0.0.1:1080"
                Log.i(TAG, "OperaProxy using ByeDpi proxy on 127.0.0.1:1080")
            }
            pb.directory(filesDir)
            pb.redirectErrorStream(true)
            val proc = pb.start()
            process = proc
            Log.i(TAG, "opera-proxy started with args: $cmdArgs")

            thread {
                try {
                    proc.inputStream.bufferedReader().use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            Log.d(TAG, "opera-proxy: $line")
                            ru.toinet.android.util.NotificationLogger.log(this@OperaProxyService, "operaproxy", line ?: "")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error reading opera-proxy output", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start opera-proxy", e)
        }
    }

    private fun stopInternal() {
        process?.destroy()
        process = null
        Log.i(TAG, "opera-proxy stopped")
    }

    override fun onDestroy() {
        stopInternal()
        super.onDestroy()
    }
}

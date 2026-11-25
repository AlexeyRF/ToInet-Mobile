package ru.toinet.android.operaproxy

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import ru.toinet.android.util.NotificationLogger
import ru.toinet.android.util.Prefs
import java.io.File
import java.io.FileOutputStream

class OperaProxyServer(
    private val context: Context,
    private val scope: CoroutineScope
) {
    @Volatile
    private var process: Process? = null
    @Volatile
    private var isStopped: Boolean = false
    @Volatile
    var isServerRunning: Boolean = false
        private set
    var onStateChanged: ((running: Boolean, error: String?) -> Unit)? = null

    companion object {
        private const val TAG = "OperaProxyServer"
        @Volatile
        var isRunning: Boolean = false
            private set

        private val _logs = MutableSharedFlow<String>(extraBufferCapacity = 100)
        val logs = _logs.asSharedFlow()
        val logHistory: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf<String>())

        fun getExecutable(context: Context): String? {
            val libDir = context.applicationInfo.nativeLibraryDir
            val nativeBin = File(libDir, "liboperaproxy.so")
            if (nativeBin.exists()) {
                try {
                    nativeBin.setExecutable(true, false)
                } catch (_: Exception) {}
                return nativeBin.absolutePath
            }

            // Fallback: extract from assets if not present in nativeLibraryDir
            val supportedAbis = Build.SUPPORTED_ABIS
            val isArm64 = supportedAbis.contains("arm64-v8a")
            val isArm = supportedAbis.contains("armeabi-v7a") || supportedAbis.contains("armeabi")

            val assetName = when {
                isArm64 -> "opera-proxy/opera-proxy.arm64"
                isArm -> "opera-proxy/opera-proxy.arm"
                else -> return null
            }

            val destFile = File(context.filesDir, "opera-proxy")
            try {
                val assetLength = context.assets.open(assetName).use { it.available().toLong() }
                if (!destFile.exists() || destFile.length() != assetLength) {
                    context.assets.open(assetName).use { inputStream ->
                        FileOutputStream(destFile).use { outputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                }
                destFile.setExecutable(true, false)
                return destFile.absolutePath
            } catch (e: Exception) {
                Log.e(TAG, "Failed to extract opera-proxy", e)
                return null
            }
        }
    }

    fun start(): Boolean {
        stop()
        isStopped = false

        NotificationLogger.log(context, "operaproxy", "Запуск Opera Proxy...")

        val executable = getExecutable(context) ?: run {
            val err = "Ошибка: исполняемый файл opera-proxy не найден"
            Log.e(TAG, err)
            NotificationLogger.log(context, "operaproxy", err)
            return false
        }

        val cmdArgs = mutableListOf<String>()
        cmdArgs.add(executable)
        val bindAddress = Prefs.operaProxyBindAddress.takeIf { it.isNotBlank() } ?: "127.0.0.1:1785"
        cmdArgs.add("-bind-address")
        cmdArgs.add(bindAddress)
        cmdArgs.add("-socks-mode")
        cmdArgs.add("-verbosity")
        cmdArgs.add(if (Prefs.operaProxyVerbose) "10" else "20")

        val preset = Prefs.operaProxyPreset.trim().ifEmpty { "random" }
        if (preset.equals("random", ignoreCase = true) ||
            preset.equals("first", ignoreCase = true) ||
            preset.equals("fastest", ignoreCase = true)) {
            cmdArgs.add("-server-selection")
            cmdArgs.add(preset.lowercase())
        } else {
            cmdArgs.add("-country")
            cmdArgs.add(preset)
            cmdArgs.add("-server-selection")
            cmdArgs.add("random")
        }

        val upstream = Prefs.operaProxyUpstream.takeIf { it.isNotBlank() }
        if (upstream != null) {
            cmdArgs.add("-proxy")
            cmdArgs.add(upstream)
        }

        return try {
            val pb = ProcessBuilder(cmdArgs)
            pb.directory(context.filesDir)
            pb.redirectErrorStream(true)
            val proc = pb.start()
            process = proc
            Log.i(TAG, "opera-proxy started with args: $cmdArgs")

            scope.launch(Dispatchers.IO) {
                try {
                    proc.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            Log.d(TAG, "opera-proxy: $line")
                            synchronized(logHistory) {
                                if (logHistory.size >= 200) {
                                    logHistory.removeAt(0)
                                }
                                logHistory.add(line)
                            }
                            _logs.tryEmit(line)
                            NotificationLogger.log(context, "operaproxy", line)
                        }
                    }
                } catch (_: Exception) {
                    // stream closed
                }
                val exitCode = try { proc.waitFor() } catch (_: Exception) { -1 }
                Log.w(TAG, "opera-proxy exited with code $exitCode")
                if (process == proc) {
                    val wasStopped = isStopped
                    isServerRunning = false
                    isRunning = false
                    if (!wasStopped) {
                        onStateChanged?.invoke(false, "opera-proxy exited with code $exitCode")
                    }
                }
            }

            val port = bindAddress.split(":").lastOrNull()?.toIntOrNull() ?: 1785
            val waitStart = System.currentTimeMillis()
            var bound = false
            var processAlive = true
            while (System.currentTimeMillis() - waitStart < 2500L) {
                processAlive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    proc.isAlive
                } else {
                    try { proc.exitValue(); false } catch (_: IllegalThreadStateException) { true }
                }
                if (!processAlive || isStopped) break
                try {
                    java.net.Socket().use { s ->
                        s.connect(java.net.InetSocketAddress("127.0.0.1", port), 100)
                        bound = true
                    }
                } catch (_: Exception) {}
                if (bound) break
                try { Thread.sleep(50) } catch (_: InterruptedException) { break }
            }

            if (!processAlive || isStopped) {
                isServerRunning = false
                isRunning = false
                NotificationLogger.log(context, "operaproxy", "Opera Proxy завершился при запуске")
                false
            } else {
                if (!bound) {
                    Log.w(TAG, "opera-proxy alive after 2500ms, port $port not bound yet")
                }
                synchronized(this) {
                    if (isStopped) {
                        isServerRunning = false
                        isRunning = false
                        return false
                    }
                    isServerRunning = true
                    isRunning = true
                }
                NotificationLogger.log(context, "operaproxy", "Opera Proxy запущен ($bindAddress)")
                onStateChanged?.invoke(true, null)
                true
            }
        } catch (e: Exception) {
            val errMsg = "Ошибка запуска процесса opera-proxy: ${e.message}"
            Log.e(TAG, errMsg, e)
            NotificationLogger.log(context, "operaproxy", errMsg)
            isServerRunning = false
            isRunning = false
            false
        }
    }

    fun stop() {
        isStopped = true
        val wasRunning = isServerRunning || isRunning
        isServerRunning = false
        isRunning = false
        try {
            val oldProc = process
            if (oldProc != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    oldProc.destroyForcibly()
                    try { oldProc.waitFor(500, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: Exception) {}
                } else {
                    oldProc.destroy()
                    try { Thread.sleep(100) } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {
            // ignore
        }
        process = null
        if (wasRunning) {
            NotificationLogger.log(context, "operaproxy", "Opera Proxy остановлен")
        }
        Log.i(TAG, "opera-proxy stopped")
    }
}

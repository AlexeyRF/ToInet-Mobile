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
    private var process: Process? = null

    companion object {
        private const val TAG = "OperaProxyServer"
        var isRunning: Boolean = false
            private set

        private val _logs = MutableSharedFlow<String>(extraBufferCapacity = 100)
        val logs = _logs.asSharedFlow()
        val logHistory: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf<String>())

        fun getExecutable(context: Context): String? {
            val libDir = context.applicationInfo.nativeLibraryDir
            val nativeBin = File(libDir, "liboperaproxy.so")
            if (nativeBin.exists()) {
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
                context.assets.open(assetName).use { inputStream ->
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
    }

    fun start() {
        stop()

        NotificationLogger.log(context, "operaproxy", "Запуск Opera Proxy...")

        val executable = getExecutable(context) ?: run {
            val err = "Ошибка: исполняемый файл opera-proxy не найден"
            Log.e(TAG, err)
            NotificationLogger.log(context, "operaproxy", err)
            return
        }

        val cmdArgs = mutableListOf<String>()
        cmdArgs.add(executable)
        val bindAddress = Prefs.operaProxyBindAddress.takeIf { it.isNotBlank() } ?: "127.0.0.1:1888"
        cmdArgs.add("-bind-address")
        cmdArgs.add(bindAddress)
        cmdArgs.add("-socks-mode")
        cmdArgs.add("-verbosity")
        cmdArgs.add(if (Prefs.operaProxyVerbose) "10" else "20")

        val upstream = Prefs.operaProxyUpstream.takeIf { it.isNotBlank() }
        if (upstream != null) {
            cmdArgs.add("-proxy")
            cmdArgs.add(upstream)
        }

        try {
            val pb = ProcessBuilder(cmdArgs)
            pb.directory(context.filesDir)
            pb.redirectErrorStream(true)
            val proc = pb.start()
            process = proc
            isRunning = true
            Log.i(TAG, "opera-proxy started with args: $cmdArgs")
            NotificationLogger.log(context, "operaproxy", "Opera Proxy запущен ($bindAddress)")

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
                } catch (e: Exception) {
                    // stream closed
                }
            }
        } catch (e: Exception) {
            val errMsg = "Ошибка запуска процесса opera-proxy: ${e.message}"
            Log.e(TAG, errMsg, e)
            NotificationLogger.log(context, "operaproxy", errMsg)
        }
    }

    fun stop() {
        try {
            process?.destroy()
        } catch (e: Exception) {
            // ignore
        }
        process = null
        if (isRunning) {
            isRunning = false
            NotificationLogger.log(context, "operaproxy", "Opera Proxy остановлен")
        }
        Log.i(TAG, "opera-proxy stopped")
    }
}

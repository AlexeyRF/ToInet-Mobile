package ru.toinet.android.zapret

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import ru.toinet.android.util.NotificationLogger
import ru.toinet.android.util.Prefs

class ZapretServer(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private var process: Process? = null

    companion object {
        private const val TAG = "ZapretServer"
        var isRunning = false
            private set
    }

    fun start() {
        stop()

        val executablePath = ZapretService.getExecutable(context)
        if (executablePath == null) {
            Log.e(TAG, "libtpws.so not found")
            return
        }

        val bindHost = Prefs.zapretHost
        val port = Prefs.zapretPort
        val cmdArgs = mutableListOf<String>()
        cmdArgs.add(executablePath)
        cmdArgs.add("--socks")
        cmdArgs.add("--bind-addr=$bindHost")
        cmdArgs.add("--port=$port")

        val rawArgs = Prefs.zapretArgs.trim()
        if (rawArgs.isNotEmpty()) {
            val parts = rawArgs.split("\\s+".toRegex()).filter { it.isNotEmpty() }
            for (part in parts) {
                if (!part.startsWith("--bind-addr") &&
                    !part.startsWith("--port") &&
                    part != "--socks") {
                    cmdArgs.add(part)
                }
            }
        }
        cmdArgs.add("--debug=1")

        try {
            val pb = ProcessBuilder(cmdArgs)
            pb.directory(context.filesDir)
            pb.redirectErrorStream(true)
            val proc = pb.start()
            process = proc
            isRunning = true
            Log.i(TAG, "Zapret tpws started with args: $cmdArgs")

            scope.launch(Dispatchers.IO) {
                try {
                    proc.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            Log.d(TAG, "[zapret] $line")
                            NotificationLogger.log(context, "zapret", line)
                        }
                    }
                } catch (e: Exception) {
                    // stream closed
                } finally {
                    isRunning = false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Zapret process", e)
            isRunning = false
        }
    }

    fun stop() {
        try {
            process?.destroyForcibly()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop Zapret", e)
        }
        process = null
        isRunning = false
        Log.i(TAG, "Zapret tpws stopped")
    }
}

package ru.toinet.android.turnproxy

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import ru.toinet.android.util.Prefs
import java.io.File

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast

class TurnProxyService : Service() {

    private var process: Process? = null

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "TurnProxyService"
        const val ACTION_START = "ru.toinet.android.turnproxy.START"
        const val ACTION_STOP = "ru.toinet.android.turnproxy.STOP"
        const val ACTION_CAPTCHA_REQUIRED = "ru.toinet.android.turnproxy.CAPTCHA_REQUIRED"
        const val ACTION_CAPTCHA_FINISHED = "ru.toinet.android.turnproxy.CAPTCHA_FINISHED"
        const val EXTRA_URL = "url"

        private val CAPTCHA_URL_REGEX = java.util.regex.Pattern.compile("""(?:manually open this URL|Open this URL in your browser):\s*(https?://\S+)""")

        fun start(context: Context) {
            val intent = Intent(context, TurnProxyService::class.java).apply {
                action = ACTION_START
            }
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, TurnProxyService::class.java).apply {
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

    private fun startInternal() {
        stopInternal()

        val supportedAbis = Build.SUPPORTED_ABIS
        if (!supportedAbis.contains("arm64-v8a") && !supportedAbis.contains("armeabi-v7a")) {
            val currentArch = supportedAbis.firstOrNull() ?: "unknown"
            val msg = "Ошибка: Ядро TurnProxy скомпилировано только для arm64-v8a и armeabi-v7a. Ваша архитектура: $currentArch."
            Log.e(TAG, msg)
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
            }
            stopSelf()
            return
        }

        val libDir = File(applicationInfo.nativeLibraryDir)
        val executable = libDir.listFiles { f ->
            f.name.startsWith("libfreeturn") && f.name.endsWith(".so")
        }?.maxByOrNull { it.name }?.absolutePath

        if (executable == null) {
            val msg = "Файл ядра TurnProxy (libfreeturn.so) не найден для вашей системы."
            Log.e(TAG, msg)
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
            }
            stopSelf()
            return
        }

        val cmdArgs = mutableListOf<String>()
        cmdArgs.add(executable)

        if (Prefs.turnProxyRawMode) {
            val parts = Prefs.turnProxyRawCommand.trim().split("\\s+".toRegex()).filter { it.isNotEmpty() }
            cmdArgs.addAll(parts)
        } else {
            cmdArgs.add("-peer")
            cmdArgs.add(Prefs.turnProxyServerAddr)
            cmdArgs.add("-provider")
            cmdArgs.add(Prefs.turnProxyProvider)
            if (Prefs.turnProxyProvider == "vk") {
                cmdArgs.add("-link")
                cmdArgs.add(Prefs.turnProxyVkLink)
            }
            cmdArgs.add("-listen")
            val bindHost = if (Prefs.openProxyOnAllInterfaces) "0.0.0.0" else "127.0.0.1"
            cmdArgs.add("$bindHost:${Prefs.turnProxyLocalPort}")

            val threads = Prefs.turnProxyThreads
            if (threads > 0) {
                cmdArgs.add("-n")
                cmdArgs.add(threads.toString())
            }

            val streamsPerCred = Prefs.turnProxyStreamsPerCred
            if (streamsPerCred > 0 && streamsPerCred != 10) {
                cmdArgs.add("-streams-per-cred")
                cmdArgs.add(streamsPerCred.toString())
            }

            if (Prefs.turnProxyTcpForward) {
                cmdArgs.add("-mode")
                cmdArgs.add("tcp")
                if (Prefs.turnProxyBond) {
                    cmdArgs.add("-bond")
                }
            }
            
            if (Prefs.turnProxyUseUdp) {
                cmdArgs.add("-transport")
                cmdArgs.add("udp")
            }

            val obfKey = Prefs.turnProxyObfKey
            if (obfKey.isNotEmpty()) {
                cmdArgs.add("-obf-profile")
                cmdArgs.add(Prefs.turnProxyObfProfile)
                cmdArgs.add("-obf-key")
                cmdArgs.add(obfKey)
            }

            if (Prefs.turnProxyManualCaptcha) {
                cmdArgs.add("-manual-captcha")
            }

            val browser = Prefs.turnProxyBrowser
            if (Prefs.turnProxyProvider == "vk" && browser == "chrome") {
                cmdArgs.add("-browser")
                cmdArgs.add(browser)
            }

            val customDns = Prefs.turnProxyDnsServers.trim()
            if (customDns.isNotEmpty()) {
                cmdArgs.add("-dns-servers")
                cmdArgs.add(customDns)
            }

            val dnsMode = Prefs.turnProxyDnsMode
            if (dnsMode == "plain" || dnsMode == "doh") {
                cmdArgs.add("-dns-mode")
                cmdArgs.add(dnsMode)
            }

            val magicTurn = Prefs.turnProxyMagicTurn.trim()
            if (magicTurn.isNotEmpty()) {
                cmdArgs.add("-turn")
                cmdArgs.add(magicTurn)
            }

            val clientId = Prefs.turnProxyClientId.trim()
            if (clientId.isNotEmpty()) {
                cmdArgs.add("-client-id")
                cmdArgs.add(clientId)
            }
        }

        try {
            val pb = ProcessBuilder(cmdArgs)
            if (Prefs.turnProxyUseByeDpi) {
                pb.environment()["ALL_PROXY"] = "socks5://127.0.0.1:1080"
                pb.environment()["all_proxy"] = "socks5://127.0.0.1:1080"
                Log.i(TAG, "TurnProxy using ByeDpi proxy on 127.0.0.1:1080")
            }
            pb.directory(filesDir)
            pb.redirectErrorStream(true)
            val proc = pb.start()
            process = proc
            Log.i(TAG, "TurnProxy started with args: $cmdArgs")

            Thread {
                try {
                    proc.inputStream.bufferedReader().use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            val l = line!!
                            Log.d(TAG, "TurnProxy: $l")
                            ru.toinet.android.util.NotificationLogger.log(this@TurnProxyService, "turnproxy", l)

                            val matcher = CAPTCHA_URL_REGEX.matcher(l)
                            if (matcher.find()) {
                                val url = matcher.group(1)
                                val intent = Intent(ACTION_CAPTCHA_REQUIRED)
                                intent.putExtra(EXTRA_URL, url)
                                intent.setPackage(packageName)
                                sendBroadcast(intent)
                            }

                            if (l.contains("[VK Auth] Failed") ||
                                l.contains("[VK Auth] Success") ||
                                (l.contains("[Captcha]") && l.contains("failed"))) {
                                val intent = Intent(ACTION_CAPTCHA_FINISHED)
                                intent.setPackage(packageName)
                                sendBroadcast(intent)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error reading TurnProxy output", e)
                }
            }.start()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start TurnProxy", e)
        }
    }

    private fun stopInternal() {
        process?.destroy()
        process = null
        Log.i(TAG, "TurnProxy stopped")
    }

    override fun onDestroy() {
        stopInternal()
        super.onDestroy()
    }
}

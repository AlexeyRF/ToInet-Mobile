package ru.toinet.android.fakevpn

import android.util.Log
import kotlinx.coroutines.*
import java.net.InetSocketAddress
import java.net.ServerSocket

class DirectSocks5Server(
    private val listenAddr: InetSocketAddress
) {
    companion object {
        private const val TAG = "FakeVpnServer"
    }

    private var serverSocket: ServerSocket? = null
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (job != null) return
        job = scope.launch(Dispatchers.IO) {
            try {
                serverSocket = ServerSocket(listenAddr.port, 128, listenAddr.address)
                Log.i(TAG, "FakeVPN SOCKS5 server listening on ${listenAddr.hostString}:${listenAddr.port}")

                while (isActive) {
                    val client = serverSocket!!.accept()
                    Log.d(TAG, "Incoming connection from ${client.remoteSocketAddress}")
                    
                    launch {
                        DirectSocks5Connection(client).handle()
                    }
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    Log.e(TAG, "FakeVpnServer error", e)
                }
            } finally {
                stop()
            }
        }
    }

    fun stop() {
        try {
            serverSocket?.close()
        } catch (e: Exception) {}
        job?.cancel()
        job = null
        Log.i(TAG, "FakeVPN SOCKS5 server stopped.")
    }
}

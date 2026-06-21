package ru.toinet.android.rehabilitator

import android.util.Log
import kotlinx.coroutines.*
import java.net.InetSocketAddress
import java.net.ServerSocket

class Socks5Server(
    private val listenAddr: InetSocketAddress,
    private val byedpiAddr: InetSocketAddress,
    private val upstreamAddr: InetSocketAddress,
    private val username: String? = null,
    private val password: String? = null
) {
    companion object {
        private const val TAG = "RehabilitatorServer"
    }

    private var serverSocket: ServerSocket? = null
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (job != null) return
        job = scope.launch(Dispatchers.IO) {
            try {
                serverSocket = ServerSocket(listenAddr.port, 128, listenAddr.address)
                Log.i(TAG, "SOCKS5 server listening on ${listenAddr.hostString}:${listenAddr.port}")

                while (isActive) {
                    val client = serverSocket!!.accept()
                    Log.d(TAG, "Incoming connection from ${client.remoteSocketAddress}")
                    
                    launch {
                        Socks5Connection(client, byedpiAddr, username, password).handle()
                    }
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    Log.e(TAG, "Socks5Server error", e)
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
        Log.i(TAG, "SOCKS5 server stopped.")
    }
}

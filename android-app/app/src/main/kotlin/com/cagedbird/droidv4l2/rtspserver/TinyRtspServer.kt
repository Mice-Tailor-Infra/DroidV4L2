package com.cagedbird.droidv4l2.rtspserver

import android.util.Log
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * TinyRtspServer: A lightweight TCP server that listens for RTSP requests.
 */
class TinyRtspServer(
    private val port: Int,
    private val onSessionCreated: (RtspSession) -> Unit
) {
    private val TAG = "TinyRtspServer"
    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()
    private var running = false

    fun start() {
        if (running) return
        running = true
        executor.execute {
            try {
                serverSocket = ServerSocket(port)
                Log.i(TAG, "RTSP Server listening on port $port")
                while (running) {
                    val client = serverSocket?.accept() ?: break
                    Log.i(TAG, "New RTSP Client: ${client.inetAddress.hostAddress}")
                    val session = RtspSession(client)
                    onSessionCreated(session)
                    executor.execute(session)
                }
            } catch (e: Exception) {
                if (running) Log.e(TAG, "Server error: ${e.message}")
            }
        }
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {}
        serverSocket = null
    }
}

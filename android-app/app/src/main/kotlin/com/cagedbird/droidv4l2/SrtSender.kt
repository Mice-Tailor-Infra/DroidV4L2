package com.cagedbird.droidv4l2

import android.media.MediaCodec
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.pedro.common.ConnectChecker
import com.pedro.srt.srt.SrtClient
import java.nio.ByteBuffer

class SrtSender(
    private val host: String, 
    private val port: Int,
    private val onConnected: () -> Unit
) : ConnectChecker {
    private val TAG = "SrtSender"
    private val srtClient = SrtClient(this)
    private val handler = Handler(Looper.getMainLooper())
    private var isStopped = false
    private var isConnecting = false
    
    private var cachedSps: ByteArray? = null
    private var cachedPps: ByteArray? = null

    fun start() {
        isStopped = false
        connectInternal()
    }

    private fun connectInternal() {
        if (isStopped || srtClient.isStreaming || isConnecting) return
        
        isConnecting = true
        val url = "srt://$host:$port/live"
        Log.d(TAG, "[NETWORK] Heartbeat: Trying to connect to $url")
        srtClient.connect(url)
    }

    fun send(data: ByteArray, timestampUs: Long, flags: Int) {
        if ((flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
            cacheSpsPps(data)
            if (srtClient.isStreaming) sendConfigPackets()
        } else if (srtClient.isStreaming) {
            val buffer = ByteBuffer.wrap(data)
            val info = MediaCodec.BufferInfo().apply {
                size = data.size
                presentationTimeUs = timestampUs
                offset = 0
                this.flags = flags
            }
            srtClient.sendVideo(buffer, info)
        }
    }

    private fun cacheSpsPps(data: ByteArray) {
        var ppsIndex = -1
        for (i in 4 until data.size - 4) {
            if (data[i] == 0.toByte() && data[i+1] == 0.toByte() && 
                data[i+2] == 0.toByte() && data[i+3] == 1.toByte()) {
                ppsIndex = i
                break
            }
        }
        if (ppsIndex != -1) {
            cachedSps = data.copyOfRange(0, ppsIndex)
            cachedPps = data.copyOfRange(ppsIndex, data.size)
        }
    }

    private fun sendConfigPackets() {
        val sps = cachedSps ?: return
        val pps = cachedPps ?: return
        srtClient.setVideoInfo(ByteBuffer.wrap(sps), ByteBuffer.wrap(pps), null)
    }

    fun stop() {
        isStopped = true
        isConnecting = false
        handler.removeCallbacksAndMessages(null)
        srtClient.disconnect()
    }

    override fun onConnectionStarted(url: String) {}
    override fun onConnectionSuccess() {
        Log.i(TAG, "[NETWORK] SRT CONNECTED")
        isConnecting = false
        sendConfigPackets()
        onConnected()
    }
    
    override fun onConnectionFailed(reason: String) {
        Log.e(TAG, "[NETWORK] Connection failed: $reason")
        isConnecting = false
        if (!isStopped) {
            handler.postDelayed({ connectInternal() }, 2000)
        }
    }
    
    override fun onNewBitrate(bitrate: Long) {}
    override fun onDisconnect() {
        Log.w(TAG, "[NETWORK] SRT DISCONNECTED")
        isConnecting = false
        if (!isStopped) {
            handler.postDelayed({ connectInternal() }, 2000)
        }
    }
    override fun onAuthError() {}
    override fun onAuthSuccess() {}
}
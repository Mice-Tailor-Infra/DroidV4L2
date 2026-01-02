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
    private val retryHandler = Handler(Looper.getMainLooper())
    private var isStopped = false
    
    private var cachedSps: ByteArray? = null
    private var cachedPps: ByteArray? = null

    fun start() {
        isStopped = false
        connectInternal()
    }

    private fun connectInternal() {
        if (isStopped || srtClient.isStreaming) return
        val url = "srt://$host:$port/live"
        Log.d(TAG, "Connecting to $url")
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
        
        val dummyInfo = MediaCodec.BufferInfo().apply {
            offset = 0
            presentationTimeUs = System.nanoTime() / 1000
            flags = MediaCodec.BUFFER_FLAG_CODEC_CONFIG
        }
        
        dummyInfo.size = sps.size
        srtClient.sendVideo(ByteBuffer.wrap(sps), dummyInfo)
        dummyInfo.size = pps.size
        srtClient.sendVideo(ByteBuffer.wrap(pps), dummyInfo)
    }

    fun stop() {
        isStopped = true
        retryHandler.removeCallbacksAndMessages(null)
        srtClient.disconnect()
    }

    override fun onConnectionStarted(url: String) {}
    override fun onConnectionSuccess() {
        Log.i(TAG, "SRT SUCCESS")
        sendConfigPackets()
        onConnected()
    }
    
    override fun onConnectionFailed(reason: String) {
        Log.e(TAG, "SRT FAILED: $reason. Retrying in 2s...")
        if (!isStopped) retryHandler.postDelayed({ connectInternal() }, 2000)
    }
    
    override fun onNewBitrate(bitrate: Long) {}
    override fun onDisconnect() {
        Log.w(TAG, "SRT DISCONNECTED. Reconnecting...")
        if (!isStopped) retryHandler.postDelayed({ connectInternal() }, 1000)
    }
    override fun onAuthError() {}
    override fun onAuthSuccess() {}
}

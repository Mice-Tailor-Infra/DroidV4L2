package com.cagedbird.droidv4l2

import android.media.MediaCodec
import android.util.Log
import com.pedro.common.ConnectChecker
import com.pedro.srt.srt.SrtClient
import java.nio.ByteBuffer

class SrtSender(
    private val host: String, 
    private val port: Int,
    private val onConnected: () -> Unit // 回调通知连接成功
) : ConnectChecker {
    private val TAG = "SrtSender"
    private val srtClient = SrtClient(this)
    
    private var cachedSps: ByteArray? = null
    private var cachedPps: ByteArray? = null

    fun start() {
        if (!srtClient.isStreaming) {
            val url = "srt://$host:$port/live"
            Log.d(TAG, "Connecting to $url")
            srtClient.connect(url)
        }
    }

    fun send(data: ByteArray, timestampUs: Long, flags: Int) {
        val buffer = ByteBuffer.wrap(data)
        val info = MediaCodec.BufferInfo().apply {
            size = data.size
            presentationTimeUs = timestampUs
            offset = 0
            this.flags = flags
        }

        if ((flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
            cacheSpsPps(data)
            if (srtClient.isStreaming) sendConfigPackets()
        } else {
            if (srtClient.isStreaming) {
                srtClient.sendVideo(buffer, info)
            }
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
        srtClient.disconnect()
    }

    override fun onConnectionStarted(url: String) {}
    override fun onConnectionSuccess() {
        Log.i(TAG, "SRT_CONNECTED")
        sendConfigPackets()
        onConnected() // 触发 UI 层的刷新逻辑
    }
    override fun onConnectionFailed(reason: String) {}
    override fun onNewBitrate(bitrate: Long) {}
    override fun onDisconnect() {}
    override fun onAuthError() {}
    override fun onAuthSuccess() {}
}
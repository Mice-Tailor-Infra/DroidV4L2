package com.cagedbird.droidv4l2

import android.media.MediaCodec
import android.util.Log
import com.pedro.common.ConnectChecker
import com.pedro.srt.srt.SrtClient
import java.nio.ByteBuffer

class SrtSender(private val host: String, private val port: Int) : ConnectChecker {
    private val TAG = "SrtSender"
    private val srtClient = SrtClient(this)
    
    // 缓存 SPS/PPS，防止在连接建立前丢失
    private var cachedSps: ByteBuffer? = null
    private var cachedPps: ByteBuffer? = null

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
            Log.i(TAG, "Caching SPS/PPS")
            cacheSpsPps(data)
            if (srtClient.isStreaming) {
                sendConfig()
            }
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
            cachedSps = ByteBuffer.allocateDirect(ppsIndex).apply { put(data, 0, ppsIndex); rewind() }
            cachedPps = ByteBuffer.allocateDirect(data.size - ppsIndex).apply { put(data, ppsIndex, data.size - ppsIndex); rewind() }
        }
    }

    private fun sendConfig() {
        val sps = cachedSps ?: return
        val pps = cachedPps ?: return
        Log.i(TAG, "Sending cached SPS/PPS to SRT")
        srtClient.setVideoInfo(sps, pps, null)
    }

    fun stop() {
        srtClient.disconnect()
    }

    override fun onConnectionStarted(url: String) {}
    override fun onConnectionSuccess() {
        Log.i(TAG, "SRT SUCCESS - Sending headers")
        // 连接成功，立刻补发配置信息
        sendConfig()
    }
    override fun onConnectionFailed(reason: String) { Log.e(TAG, "SRT FAILED: $reason") }
    override fun onNewBitrate(bitrate: Long) {}
    override fun onDisconnect() {}
    override fun onAuthError() {}
    override fun onAuthSuccess() {}
}
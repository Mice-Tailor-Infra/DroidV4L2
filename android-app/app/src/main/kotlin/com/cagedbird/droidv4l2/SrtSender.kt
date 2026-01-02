package com.cagedbird.droidv4l2

import android.media.MediaCodec
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.pedro.common.ConnectChecker
import com.pedro.common.VideoCodec
import com.pedro.srt.srt.SrtClient
import java.nio.ByteBuffer

class SrtSender(
    private val host: String, 
    private val port: Int,
    private val isHevc: Boolean,
    private val onConnected: () -> Unit
) : ConnectChecker, VideoSender {
    private val TAG = "SrtSender"
    private val srtClient = SrtClient(this)
    private val handler = Handler(Looper.getMainLooper())
    private var isStopped = false
    
    private var cachedVps: ByteArray? = null
    private var cachedSps: ByteArray? = null
    private var cachedPps: ByteArray? = null

    init {
        // 在初始化时设置编码格式
        srtClient.setVideoCodec(if (isHevc) VideoCodec.H265 else VideoCodec.H264)
    }

    override fun start() {
        isStopped = false
        connectInternal()
    }

    private fun connectInternal() {
        if (isStopped || srtClient.isStreaming) return
        val url = "srt://$host:$port/live"
        Log.d(TAG, "[NETWORK] Heartbeat: Connecting to $url (HEVC=$isHevc)")
        srtClient.connect(url)
    }

    override fun send(data: ByteArray, timestampUs: Long, flags: Int) {
        if ((flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
            parseConfigData(data)
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

    override fun getInfo(): String = "srt://$host:$port"

    private fun parseConfigData(data: ByteArray) {
        val indices = mutableListOf<Int>()
        // 查找所有 Start Code (00 00 00 01)
        for (i in 0 until data.size - 3) {
            if (data[i] == 0.toByte() && data[i+1] == 0.toByte() && 
                data[i+2] == 0.toByte() && data[i+3] == 1.toByte()) {
                indices.add(i)
            }
        }
        
        indices.add(data.size) // 哨兵

        for (k in 0 until indices.size - 1) {
            val start = indices[k]
            val end = indices[k+1]
            val nal = data.copyOfRange(start, end)
            
            if (isHevc) {
                // H.265 NAL Type: (byte[4] >> 1) & 0x3F
                // StartCode是4字节，所以 NAL Header 是 data[start+4]
                if (nal.size > 4) {
                    val type = (nal[4].toInt() shr 1) and 0x3F
                    when (type) {
                        32 -> cachedVps = nal // VPS
                        33 -> cachedSps = nal // SPS
                        34 -> cachedPps = nal // PPS
                    }
                }
            } else {
                // H.264 NAL Type: byte[4] & 0x1F
                if (nal.size > 4) {
                    val type = nal[4].toInt() and 0x1F
                    when (type) {
                        7 -> cachedSps = nal // SPS
                        8 -> cachedPps = nal // PPS
                    }
                }
            }
        }
        
        if (isHevc) {
             Log.i(TAG, "Config parsed (HEVC): VPS=${cachedVps != null}, SPS=${cachedSps != null}, PPS=${cachedPps != null}")
        } else {
             Log.i(TAG, "Config parsed (AVC): SPS=${cachedSps != null}, PPS=${cachedPps != null}")
        }
    }

    private fun sendConfigPackets() {
        if (isHevc) {
            if (cachedSps == null || cachedPps == null || cachedVps == null) return
            // setVideoInfo for HEVC: SPS, PPS, VPS
            srtClient.setVideoInfo(ByteBuffer.wrap(cachedSps), ByteBuffer.wrap(cachedPps), ByteBuffer.wrap(cachedVps))
        } else {
            if (cachedSps == null || cachedPps == null) return
            srtClient.setVideoInfo(ByteBuffer.wrap(cachedSps), ByteBuffer.wrap(cachedPps), null)
        }
    }

    override fun stop() {
        Log.d(TAG, "[CLEANUP] stop() called, disabling retries")
        isStopped = true
        handler.removeCallbacksAndMessages(null)
        srtClient.disconnect()
    }

    override fun onConnectionStarted(url: String) {}
    override fun onConnectionSuccess() {
        if (isStopped) return
        Log.i(TAG, "[NETWORK] SRT CONNECTED SUCCESS")
        sendConfigPackets()
        onConnected()
    }
    
    override fun onConnectionFailed(reason: String) {
        if (isStopped) return
        Log.w(TAG, "[NETWORK] Connection failed: $reason. Retrying...")
        handler.postDelayed({ connectInternal() }, 2000)
    }
    
    override fun onNewBitrate(bitrate: Long) {}
    override fun onDisconnect() {
        if (isStopped) return
        Log.w(TAG, "[NETWORK] SRT DISCONNECTED. Re-polling...")
        handler.postDelayed({ connectInternal() }, 1000)
    }
    override fun onAuthError() {}
    override fun onAuthSuccess() {}
}
package com.cagedbird.droidv4l2

import android.media.MediaCodec
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.pedro.common.ConnectChecker
import com.pedro.rtspserver.server.RtspServer
import com.pedro.common.VideoCodec
import java.nio.ByteBuffer
import java.net.Inet4Address
import java.net.NetworkInterface

class RtspServerSender(
    private val port: Int,
    private val isHevc: Boolean,
    private val onClientConnected: () -> Unit
) : ConnectChecker, VideoSender {
    private val TAG = "RtspServerSender"
    private val rtspServer = RtspServer(this, port)
    private val handler = Handler(Looper.getMainLooper())
    
    // Cache SPS/PPS/VPS to set format header
    private var cachedVps: ByteArray? = null
    private var cachedSps: ByteArray? = null
    private var cachedPps: ByteArray? = null
    
    private val localIp: String by lazy { getLocalIpAddress() ?: "0.0.0.0" }

    init {
        // RtspServer sets video codec dynamically based on stream info
    }

    override fun start() {
        Log.i(TAG, "Starting RTSP Server on port $port")
        rtspServer.startServer()
    }

    override fun stop() {
        Log.i(TAG, "Stopping RTSP Server")
        rtspServer.stopServer()
    }

    override fun send(data: ByteArray, timestampUs: Long, flags: Int) {
        if ((flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
            parseConfigData(data)
            // RtspServer needs SPS/PPS to set up the SDP (Session Description Protocol)
            if (isHevc) {
               if (cachedSps != null && cachedPps != null && cachedVps != null) {
                   rtspServer.setVideoInfo(ByteBuffer.wrap(cachedSps), ByteBuffer.wrap(cachedPps), ByteBuffer.wrap(cachedVps))
               }
            } else {
               if (cachedSps != null && cachedPps != null) {
                   rtspServer.setVideoInfo(ByteBuffer.wrap(cachedSps), ByteBuffer.wrap(cachedPps), null)
               }
            }
        } else {
             val buffer = ByteBuffer.wrap(data)
             val info = MediaCodec.BufferInfo().apply {
                size = data.size
                presentationTimeUs = timestampUs
                offset = 0
                this.flags = flags
            }
            rtspServer.sendVideo(buffer, info)
        }
    }

    override fun getInfo(): String {
        return "rtsp://$localIp:$port/live"
    }
    
    private fun getLocalIpAddress(): String? {
        try {
            val en = NetworkInterface.getNetworkInterfaces()
            while (en.hasMoreElements()) {
                val intf = en.nextElement()
                val enumIpAddr = intf.inetAddresses
                while (enumIpAddr.hasMoreElements()) {
                    val inetAddress = enumIpAddr.nextElement()
                    if (!inetAddress.isLoopbackAddress && inetAddress is Inet4Address) {
                        return inetAddress.hostAddress
                    }
                }
            }
        } catch (ex: Exception) {
            Log.e(TAG, "IP lookup failed", ex)
        }
        return null
    }

    private fun parseConfigData(data: ByteArray) {
        val indices = mutableListOf<Int>()
        for (i in 0 until data.size - 3) {
            if (data[i] == 0.toByte() && data[i+1] == 0.toByte() && 
                data[i+2] == 0.toByte() && data[i+3] == 1.toByte()) {
                indices.add(i)
            }
        }
        indices.add(data.size)

        for (k in 0 until indices.size - 1) {
            val start = indices[k]
            val end = indices[k+1]
            val nal = data.copyOfRange(start, end)
            
            if (isHevc) {
                if (nal.size > 4) {
                    val type = (nal[4].toInt() shr 1) and 0x3F
                    when (type) {
                        32 -> cachedVps = nal
                        33 -> cachedSps = nal
                        34 -> cachedPps = nal
                    }
                }
            } else {
                if (nal.size > 4) {
                    val type = nal[4].toInt() and 0x1F
                    when (type) {
                        7 -> cachedSps = nal
                        8 -> cachedPps = nal
                    }
                }
            }
        }
    }

    // ConnectChecker implementation for RtspServer (Client connections)
    override fun onConnectionStarted(url: String) {
        Log.d(TAG, "Client connecting: $url")
    }
    
    override fun onConnectionSuccess() {
        Log.i(TAG, "Client Connected!")
        handler.post { onClientConnected() }
    }
    
    override fun onConnectionFailed(reason: String) {
        Log.e(TAG, "RTSP Error: $reason")
    }
    
    override fun onNewBitrate(bitrate: Long) {}
    override fun onDisconnect() {
        Log.i(TAG, "Client Disconnected")
    }
    override fun onAuthError() {}
    override fun onAuthSuccess() {}
}

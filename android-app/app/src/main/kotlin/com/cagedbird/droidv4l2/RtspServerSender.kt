package com.cagedbird.droidv4l2

import android.media.MediaCodec
import android.util.Log
import com.cagedbird.tinyrtsp.RtpPacketizer
import com.cagedbird.tinyrtsp.RtspSession
import com.cagedbird.tinyrtsp.TinyRtspServer
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.CopyOnWriteArrayList

class RtspServerSender(
    private val port: Int,
    private val isHevc: Boolean,
    private val onClientConnected: () -> Unit
) : VideoSender {
    private val TAG = "RtspServerSender"
    
    private val sessions = CopyOnWriteArrayList<RtspSession>()
    private val packetizer = RtpPacketizer(isHevc)
    
    private val rtspServer = TinyRtspServer(port) { session ->
        session.isHevc = isHevc
        session.vps = cachedVps
        session.sps = cachedSps
        session.pps = cachedPps
        sessions.add(session)
        onClientConnected()
    }
    
    private var cachedVps: ByteArray? = null
    private var cachedSps: ByteArray? = null
    private var cachedPps: ByteArray? = null
    
    private val localIp: String by lazy { getLocalIpAddress() ?: "0.0.0.0" }

    override fun start() {
        Log.i(TAG, "Starting TinyRTSP Server on port $port")
        rtspServer.start()
    }

    override fun stop() {
        Log.i(TAG, "Stopping TinyRTSP Server")
        rtspServer.stop()
        sessions.forEach { it.stop() }
        sessions.clear()
    }

    override fun send(data: ByteArray, timestampUs: Long, flags: Int) {
        if ((flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
            parseConfigData(data)
            // Update active sessions with new config
            sessions.forEach {
                it.vps = cachedVps
                it.sps = cachedSps
                it.pps = cachedPps
            }
        } else {
            // Strip Start Code (00 00 00 01)
            // MediaCodec usually returns Annex B format
            var offset = 0
            if (data.size > 4 && data[0] == 0.toByte() && data[1] == 0.toByte() && 
                data[2] == 0.toByte() && data[3] == 1.toByte()) {
                offset = 4
            } else if (data.size > 3 && data[0] == 0.toByte() && data[1] == 0.toByte() && 
                data[2] == 1.toByte()) {
                offset = 3
            }

            val nalu = if (offset > 0) {
                data.copyOfRange(offset, data.size)
            } else {
                data
            }

            // KEY FRAME INJECTION FOR HEVC
            if (isHevc && (flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) {
                // Send VPS/SPS/PPS before the IDR frame
                cachedVps?.let { packetizer.packetize(it, timestampUs) { p, _ -> broadcast(p) } }
                cachedSps?.let { packetizer.packetize(it, timestampUs) { p, _ -> broadcast(p) } }
                cachedPps?.let { packetizer.packetize(it, timestampUs) { p, _ -> broadcast(p) } }
            }
            
            // We use the packetizer to turn NALU into RTP packets
            packetizer.packetize(nalu, timestampUs) { packet, _ ->
                broadcast(packet)
            }
        }
    }

    private fun broadcast(packet: ByteArray) {
        val iterator = sessions.iterator()
        while (iterator.hasNext()) {
            val session = iterator.next()
            try {
                session.sendRtpPacket(packet)
            } catch (e: Exception) {
                session.stop()
                sessions.remove(session)
            }
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
            // Skip start code
            val nalStart = start + 4
            if (nalStart >= end) continue
            val nal = data.copyOfRange(nalStart, end)
            
            if (isHevc) {
                val type = (nal[0].toInt() shr 1) and 0x3F
                when (type) {
                    32 -> cachedVps = nal
                    33 -> cachedSps = nal
                    34 -> cachedPps = nal
                }
            } else {
                val type = nal[0].toInt() and 0x1F
                when (type) {
                    7 -> cachedSps = nal
                    8 -> cachedPps = nal
                }
            }
        }
    }
}
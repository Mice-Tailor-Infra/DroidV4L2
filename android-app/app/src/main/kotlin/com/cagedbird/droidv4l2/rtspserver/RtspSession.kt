package com.cagedbird.droidv4l2.rtspserver

import android.util.Base64
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.util.regex.Pattern

/**
 * RtspSession: Handles the RTSP state machine for a single client.
 */
class RtspSession(private val socket: Socket) : Runnable {
    private val TAG = "RtspSession"
    @Volatile private var running = true
    private var cSeq = "1"
    
    // Media Info
    var isHevc = false
    var vps: ByteArray? = null
    var sps: ByteArray? = null
    var pps: ByteArray? = null
    
    // RTP Info
    private var clientRtpPort = 0
    private var clientRtcpPort = 0
    private var clientAddress: InetAddress? = null
    @Volatile private var udpSocket: DatagramSocket? = null
    @Volatile private var playing = false

    override fun run() {
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = socket.getOutputStream()
            clientAddress = socket.inetAddress

            while (running) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) continue
                
                Log.d(TAG, "REQ: $line")
                val firstLine = line.split(" ")
                if (firstLine.size < 2) continue
                
                val method = firstLine[0]
                val headers = mutableMapOf<String, String>()
                
                // Read headers
                var hLine = reader.readLine()
                while (hLine != null && hLine.isNotEmpty()) {
                    val part = hLine.split(": ", limit = 2)
                    if (part.size == 2) headers[part[0]] = part[1]
                    hLine = reader.readLine()
                }
                
                cSeq = headers["CSeq"] ?: "1"
                
                when (method) {
                    "OPTIONS" -> handleOptions(writer)
                    "DESCRIBE" -> handleDescribe(writer)
                    "SETUP" -> handleSetup(headers, writer)
                    "PLAY" -> handlePlay(writer)
                    "TEARDOWN" -> handleTeardown(writer)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Session error: ${e.message}")
        } finally {
            stop()
        }
    }

    private fun handleOptions(out: OutputStream) {
        val response = "RTSP/1.0 200 OK\r\n" +
                "CSeq: $cSeq\r\n" +
                "Public: OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN\r\n\r\n"
        out.write(response.toByteArray())
    }

    private fun handleDescribe(out: OutputStream) {
        val sdp = generateSdp()
        Log.i(TAG, "Generated SDP:\n$sdp")
        val response = "RTSP/1.0 200 OK\r\n" +
                "CSeq: $cSeq\r\n" +
                "Content-Type: application/sdp\r\n" +
                "Content-Length: ${sdp.length}\r\n\r\n" + sdp
        out.write(response.toByteArray())
    }

    private fun handleSetup(headers: Map<String, String>, out: OutputStream) {
        val transport = headers["Transport"] ?: ""
        Log.i(TAG, "SETUP Transport: $transport") // Debug: Check raw transport header
        val matcher = Pattern.compile("client_port=(\\d+)-(\\d+)").matcher(transport)
        if (matcher.find()) {
            clientRtpPort = matcher.group(1).toInt()
            clientRtcpPort = matcher.group(2).toInt()
            Log.i(TAG, "Parsed Client Ports: RTP=$clientRtpPort, RTCP=$clientRtcpPort")
        } else {
            Log.e(TAG, "Failed to parse client_port from Transport header!")
        }
        
        udpSocket = DatagramSocket()
        val serverPort = udpSocket!!.localPort

        val response = "RTSP/1.0 200 OK\r\n" +
                "CSeq: $cSeq\r\n" +
                "Transport: RTP/AVP/UDP;unicast;client_port=$clientRtpPort-$clientRtcpPort;server_port=$serverPort-${serverPort+1};ssrc=12345678\r\n" +
                "Session: 12345678\r\n\r\n"
        out.write(response.toByteArray())
    }

    private fun handlePlay(out: OutputStream) {
        val response = "RTSP/1.0 200 OK\r\n" +
                "CSeq: $cSeq\r\n" +
                "Session: 12345678\r\n" +
                "Range: npt=0.000-\r\n\r\n"
        out.write(response.toByteArray())
        playing = true
        Log.i(TAG, "Session Playing. Target: $clientAddress:$clientRtpPort")
    }

    private fun handleTeardown(out: OutputStream) {
        val response = "RTSP/1.0 200 OK\r\n" +
                "CSeq: $cSeq\r\n\r\n"
        out.write(response.toByteArray())
        running = false
    }

    private fun generateSdp(): String {
        val ip = socket.localAddress.hostAddress
        val codecName = if (isHevc) "H265" else "H264"
        
        var fmtp = "a=fmtp:96 packetization-mode=1"
        if (isHevc) {
            val vpsStr = Base64.encodeToString(vps ?: ByteArray(0), Base64.NO_WRAP)
            val spsStr = Base64.encodeToString(sps ?: ByteArray(0), Base64.NO_WRAP)
            val ppsStr = Base64.encodeToString(pps ?: ByteArray(0), Base64.NO_WRAP)
            fmtp = "a=fmtp:96 sprop-vps=$vpsStr; sprop-sps=$spsStr; sprop-pps=$ppsStr"
        } else {
            val spsStr = Base64.encodeToString(sps ?: ByteArray(0), Base64.NO_WRAP)
            val ppsStr = Base64.encodeToString(pps ?: ByteArray(0), Base64.NO_WRAP)
            fmtp = "a=fmtp:96 packetization-mode=1;sprop-parameter-sets=$spsStr,$ppsStr"
        }

        return "v=0\r\n" +
                "o=- 0 0 IN IP4 $ip\r\n" +
                "s=DroidV4L2 Stream\r\n" +
                "c=IN IP4 $ip\r\n" +
                "t=0 0\r\n" +
                "m=video 0 RTP/AVP 96\r\n" +
                "a=rtpmap:96 $codecName/90000\r\n" +
                "$fmtp\r\n" +
                "a=control:trackID=0\r\n"
    }

    fun sendRtpPacket(packet: ByteArray) {
        if (!playing || clientAddress == null || clientRtpPort == 0) {
            return
        }
        try {
            val socket = udpSocket
            if (socket != null) {
                val datagram = DatagramPacket(packet, packet.size, clientAddress, clientRtpPort)
                socket.send(datagram)
            }
        } catch (e: Exception) {
             Log.e(TAG, "UDP Send Error", e)
        }
    }

    fun stop() {
        running = false
        playing = false
        try { socket.close() } catch (e: Exception) {}
        try { udpSocket?.close() } catch (e: Exception) {}
    }
}

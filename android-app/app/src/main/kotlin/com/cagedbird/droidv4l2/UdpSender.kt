package com.cagedbird.droidv4l2

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors

class UdpSender(private val host: String, private val port: Int) {
    private var socket: DatagramSocket? = null
    private val address: InetAddress by lazy { InetAddress.getByName(host) }
    private val sendExecutor = Executors.newSingleThreadExecutor()

    init {
        socket = DatagramSocket()
    }

    fun send(data: ByteArray) {
        sendExecutor.execute {
            try {
                // For H.264, if the packet is larger than MTU, it might get fragmented.
                // For simplicity now, we just send. Later we can implement proper RTP fragmentation.
                val packet = DatagramPacket(data, data.size, address, port)
                socket?.send(packet)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun stop() {
        socket?.close()
        sendExecutor.shutdown()
    }
}

package com.cagedbird.droidv4l2.rtspserver

import java.nio.ByteBuffer
import kotlin.experimental.and
import kotlin.experimental.or

/**
 * RtpPacketizer: Encapsulates H.264/H.265 NAL units into RTP packets.
 * Supports Single NAL Unit packets and Fragmentation Units (FU).
 */
class RtpPacketizer(private val isHevc: Boolean) {
    private var sequenceNumber = 0
    private var ssrc = (Math.random() * 0xFFFFFFFFL).toLong()
    private val MTU = 1300 // Leave some room for headers

    private val rtpBuffer = ByteBuffer.allocate(1500)

    /**
     * Packetizes a NAL unit and calls [onPacket] for each resulting RTP packet.
     */
    fun packetize(nalu: ByteArray, timestampUs: Long, onPacket: (ByteArray, Int) -> Unit) {
        val timestamp = (timestampUs * 90 / 1000).toInt() // 90kHz clock for video

        if (nalu.size <= MTU) {
            // Single NAL unit packet
            sendSinglePacket(nalu, timestamp, onPacket)
        } else {
            // Fragmentation Units (FU)
            if (isHevc) {
                sendHevcFragmentationUnits(nalu, timestamp, onPacket)
            } else {
                sendAvcFragmentationUnits(nalu, timestamp, onPacket)
            }
        }
    }

    private fun sendSinglePacket(nalu: ByteArray, timestamp: Int, onPacket: (ByteArray, Int) -> Unit) {
        prepareRtpHeader(timestamp, true)
        rtpBuffer.put(nalu)
        finalizePacket(onPacket)
    }

    private fun sendAvcFragmentationUnits(nalu: ByteArray, timestamp: Int, onPacket: (ByteArray, Int) -> Unit) {
        val nri = nalu[0] and 0x60.toByte()
        val type = nalu[0] and 0x1F.toByte()
        var pos = 1
        
        while (pos < nalu.size) {
            val remaining = nalu.size - pos
            val chunkSize = if (remaining > MTU - 2) MTU - 2 else remaining
            val isLast = pos + chunkSize == nalu.size

            prepareRtpHeader(timestamp, isLast)
            
            // FU-A indicator
            rtpBuffer.put((nri or 28.toByte()))
            // FU-A header
            var fuHeader = type
            if (pos == 1) fuHeader = fuHeader or 0x80.toByte() // Start
            if (isLast) fuHeader = fuHeader or 0x40.toByte() // End
            rtpBuffer.put(fuHeader)
            
            rtpBuffer.put(nalu, pos, chunkSize)
            finalizePacket(onPacket)
            pos += chunkSize
        }
    }

    private fun sendHevcFragmentationUnits(nalu: ByteArray, timestamp: Int, onPacket: (ByteArray, Int) -> Unit) {
        // Original NAL Header (2 bytes)
        val originalHeader1 = nalu[0]
        val originalHeader2 = nalu[1]
        
        // Extract original Type (bits 1-6 of byte 1)
        val originalType = (originalHeader1.toInt() shr 1) and 0x3F
        
        // Construct FU Payload Header (2 bytes)
        // Byte 1: F(1) | Type=49(6) | LayerId_High(1)
        // We take F and LayerId_High from original, set Type to 49
        val payloadHeader1 = (originalHeader1.toInt() and 0x81) or (49 shl 1)
        
        // Byte 2: LayerId_Low(5) | TID(3)
        // We take entire byte 2 from original.
        // CRITICAL FIX: Ensure TID is not 0. TID is bits 0-2.
        // If TID is 0, we force it to 1.
        var payloadHeader2 = originalHeader2.toInt()
        if ((payloadHeader2 and 0x07) == 0) {
            payloadHeader2 = payloadHeader2 or 0x01
        }

        var pos = 2
        
        while (pos < nalu.size) {
            val remaining = nalu.size - pos
            val chunkSize = if (remaining > MTU - 3) MTU - 3 else remaining
            val isLast = pos + chunkSize == nalu.size

            prepareRtpHeader(timestamp, isLast)
            
            // Write 2-byte Payload Header
            rtpBuffer.put(payloadHeader1.toByte())
            rtpBuffer.put(payloadHeader2.toByte())
            
            // Write 1-byte FU Header
            // S(1) | E(1) | Type(6)
            var fuHeader = originalType
            if (pos == 2) fuHeader = fuHeader or 0x80 // S bit
            if (isLast) fuHeader = fuHeader or 0x40 // E bit
            rtpBuffer.put(fuHeader.toByte())
            
            rtpBuffer.put(nalu, pos, chunkSize)
            finalizePacket(onPacket)
            pos += chunkSize
        }
    }

    private fun prepareRtpHeader(timestamp: Int, marker: Boolean) {
        rtpBuffer.clear()
        // V=2, P=0, X=0, CC=0 -> 0x80
        rtpBuffer.put(0x80.toByte())
        // M, PT=96 (Dynamic)
        val mpt = if (marker) 0x80 or 96 else 96
        rtpBuffer.put(mpt.toByte())
        // Sequence Number
        rtpBuffer.putShort((sequenceNumber++ and 0xFFFF).toShort())
        // Timestamp
        rtpBuffer.putInt(timestamp)
        // SSRC
        rtpBuffer.putInt(ssrc.toInt())
    }

    private fun finalizePacket(onPacket: (ByteArray, Int) -> Unit) {
        val size = rtpBuffer.position()
        val packet = ByteArray(size)
        rtpBuffer.flip()
        rtpBuffer.get(packet)
        onPacket(packet, size)
    }
}

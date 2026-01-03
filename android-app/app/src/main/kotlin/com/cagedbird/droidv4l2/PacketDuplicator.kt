package com.cagedbird.droidv4l2

import android.util.Log

/** Composite VideoSender that duplicates packets to multiple destinations. 复合视频发送器，将数据包复制到多个目的地。 */
class PacketDuplicator(private val senders: List<VideoSender>) : VideoSender {
    private val TAG = "PacketDuplicator"

    override fun start() {
        Log.i(
                TAG,
                "Starting broadcast to ${senders.size} destinations | 此刻开启向 ${senders.size} 个目的地的广播"
        )
        senders.forEach { it.start() }
    }

    override fun stop() {
        Log.i(TAG, "Stopping broadcast | 停止广播")
        senders.forEach { it.stop() }
    }

    override fun send(data: ByteArray, timestampUs: Long, flags: Int) {
        // Iterate through all senders and dispatch the packet
        // 遍历所有发送器并分发数据包
        senders.forEach { sender ->
            try {
                sender.send(data, timestampUs, flags)
            } catch (e: Exception) {
                // Prevent one failure from stopping others
                // 防止一个失败影响其他发送器
                Log.e(TAG, "Failed to send to ${sender.getInfo()}: ${e.message}")
            }
        }
    }

    override fun getInfo(): String {
        // Combine info strings from all senders
        // 组合所有发送器的信息字符串
        return senders.joinToString(" + ") { it.getInfo() }
    }
}

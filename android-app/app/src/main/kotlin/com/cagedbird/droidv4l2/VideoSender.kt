package com.cagedbird.droidv4l2

interface VideoSender {
    /**
     * Start the sender (connect to server or start listening)
     */
    fun start()

    /**
     * Stop the sender and cleanup resources
     */
    fun stop()

    /**
     * Send encoded video data
     * @param data The NAL units
     * @param timestampUs Presentation timestamp
     * @param flags MediaCodec flags (e.g. BUFFER_FLAG_KEY_FRAME, BUFFER_FLAG_CODEC_CONFIG)
     */
    fun send(data: ByteArray, timestampUs: Long, flags: Int)

    /**
     * Get descriptive info about the connection (e.g., URL or Port)
     */
    fun getInfo(): String
}

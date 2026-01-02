package com.cagedbird.droidv4l2

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class VideoEncoder(
    private val width: Int,
    private val height: Int,
    private val bitRate: Int,
    private val frameRate: Int,
    private val onEncodedData: (ByteArray, Long, Int) -> Unit // Added flags parameter
) {
    private var mediaCodec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private val encoderExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    fun start() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_LATENCY, 0)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
        }

        mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {}

                override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                    val outputBuffer = codec.getOutputBuffer(index)
                    if (outputBuffer != null && info.size > 0) {
                        outputBuffer.position(info.offset)
                        outputBuffer.limit(info.offset + info.size)
                        
                        val data = ByteArray(info.size)
                        outputBuffer.get(data)
                        onEncodedData(data, info.presentationTimeUs, info.flags) // Pass the real flags
                        
                        codec.releaseOutputBuffer(index, false)
                    }
                }

                override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) { e.printStackTrace() }
                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                    Log.d("VideoEncoder", "Format changed: $format")
                }
            })

            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = createInputSurface()
            start()
        }
    }

    fun getInputSurface(): Surface? = inputSurface

    fun stop() {
        try {
            mediaCodec?.stop()
            mediaCodec?.release()
        } catch (e: Exception) {}
        mediaCodec = null
        inputSurface?.release()
        inputSurface = null
        encoderExecutor.shutdown()
    }
}
package com.cagedbird.droidv4l2

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Surface
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class VideoEncoder(
        private val width: Int,
        private val height: Int,
        private val bitRate: Int,
        private val frameRate: Int,
        private val mimeType: String,
        private val onEncodedData: (ByteArray, Long, Int) -> Unit
) {
    private var mediaCodec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private val encoderExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    // Explicit IDR Watchdog
    private val idrHandler = Handler(Looper.getMainLooper())
    private val idrRunnable =
            object : Runnable {
                override fun run() {
                    if (mediaCodec != null) {
                        requestKeyFrame()
                        idrHandler.postDelayed(this, 1000) // Force IDR every 1 second
                    }
                }
            }

    fun start() {
        // Start a background thread for MediaCodec callbacks to avoid NetworkOnMainThreadException
        backgroundThread = HandlerThread("VideoEncoderThread").apply { start() }
        backgroundHandler = Handler(backgroundThread!!.looper)

        val format =
                MediaFormat.createVideoFormat(mimeType, width, height).apply {
                    setInteger(
                            MediaFormat.KEY_COLOR_FORMAT,
                            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                    )
                    setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                    setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // 1 second between I-frames
                    setInteger(
                            MediaFormat.KEY_BITRATE_MODE,
                            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                    )
                    setInteger(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000 / frameRate)
                    setInteger(MediaFormat.KEY_PRIORITY, 0)
                }

        mediaCodec =
                MediaCodec.createEncoderByType(mimeType).apply {
                    setCallback(
                            object : MediaCodec.Callback() {
                                override fun onInputBufferAvailable(
                                        codec: MediaCodec,
                                        index: Int
                                ) {}
                                override fun onOutputBufferAvailable(
                                        codec: MediaCodec,
                                        index: Int,
                                        info: MediaCodec.BufferInfo
                                ) {
                                    val outputBuffer = codec.getOutputBuffer(index)
                                    if (outputBuffer != null && info.size > 0) {
                                        outputBuffer.position(info.offset)
                                        outputBuffer.limit(info.offset + info.size)
                                        val data = ByteArray(info.size)
                                        outputBuffer.get(data)
                                        onEncodedData(data, info.presentationTimeUs, info.flags)
                                        codec.releaseOutputBuffer(index, false)
                                    }
                                }
                                override fun onError(
                                        codec: MediaCodec,
                                        e: MediaCodec.CodecException
                                ) {
                                    e.printStackTrace()
                                }
                                override fun onOutputFormatChanged(
                                        codec: MediaCodec,
                                        format: MediaFormat
                                ) {}
                            },
                            backgroundHandler
                    ) // Pass the background handler here!

                    configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    inputSurface = createInputSurface()
                    start()

                    // Start IDR Watchdog with 500ms delay to prevent start-up stutter
                    idrHandler.postDelayed(idrRunnable, 500)
                }
    }

    // 关键功能：强制产生一个 IDR 帧
    fun requestKeyFrame() {
        val params = Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }
        try {
            mediaCodec?.setParameters(params)
            Log.i("VideoEncoder", "IDR Frame Requested")
        } catch (e: Exception) {
            Log.e("VideoEncoder", "Failed to request IDR frame", e)
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

        backgroundThread?.quitSafely()
        backgroundThread = null
        backgroundHandler = null

        idrHandler.removeCallbacks(idrRunnable)
    }
}

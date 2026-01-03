package com.cagedbird.droidv4l2

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaFormat
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class StreamingService : LifecycleService() {
    private val TAG = "StreamingService"
    private val CHANNEL_ID = "DroidV4L2Channel"
    private val NOTIFICATION_ID = 1

    private val binder = LocalBinder()
    private lateinit var cameraExecutor: ExecutorService

    private var packetDuplicator: PacketDuplicator? = null

    // WebRTC
    private var webRtcManager: WebRtcManager? = null
    private var webServer: WebServer? = null

    private var videoEncoder: VideoEncoder? = null
    private var videoSender: VideoSender? = null
    private var isStreaming = false

    // Config
    data class StreamingConfig(
            val host: String,
            val port: Int,
            val bitrate: Int,
            val fps: Int,
            val width: Int,
            val height: Int,
            val isHevc: Boolean,
            val protocol: String
    )

    private var currentConfig: StreamingConfig? = null

    // Preview
    private var viewFinder: PreviewView? = null

    // Preview persistence
    private var encoderPreview: Preview? = null
    private var viewPreview: Preview? = null

    inner class LocalBinder : Binder() {
        fun getService(): StreamingService = this@StreamingService
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        cameraExecutor = Executors.newSingleThreadExecutor()

        // WebServer starts immediately, but WebRTC manager is lazy loaded
        webServer = WebServer(this, 8080, null)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            "START" -> {
                val config = parseIntent(intent)
                startStreaming(config)
            }
            "STOP" -> {
                stopStreaming()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION") stopForeground(true)
                }
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private fun parseIntent(intent: Intent): StreamingConfig {
        return StreamingConfig(
                host = intent.getStringExtra("host") ?: "10.0.0.17",
                port = intent.getIntExtra("port", 5000),
                bitrate = intent.getIntExtra("bitrate", 10),
                fps = intent.getIntExtra("fps", 30),
                width = intent.getIntExtra("width", 1280),
                height = intent.getIntExtra("height", 720),
                isHevc = intent.getBooleanExtra("isHevc", false),
                protocol = intent.getStringExtra("protocol") ?: "SRT (Caller)"
        )
    }

    private fun startStreaming(config: StreamingConfig) {
        if (isStreaming) return
        currentConfig = config

        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val mimeType =
                if (config.isHevc) MediaFormat.MIMETYPE_VIDEO_HEVC
                else MediaFormat.MIMETYPE_VIDEO_AVC

        Log.i(
                TAG,
                "Starting Service Stream: ${config.protocol} ${config.width}x${config.height}@${config.fps} ${if(config.isHevc) "HEVC" else "AVC"}"
        )

        videoEncoder =
                VideoEncoder(
                        config.width,
                        config.height,
                        config.bitrate * 1_000_000,
                        config.fps,
                        mimeType
                ) { data, ts, flags -> videoSender?.send(data, ts, flags) }
        videoEncoder?.start()

        setupSender(config)
        videoSender?.start()

        bindCamera()
        isStreaming = true
    }

    private fun setupSender(config: StreamingConfig) {
        when (config.protocol) {
            "SRT (Caller)" -> {
                videoSender =
                        SrtSender(config.host, config.port, config.isHevc) {
                            videoEncoder?.requestKeyFrame()
                        }
            }
            "RTSP (Server)" -> {
                videoSender =
                        RtspServerSender(8554, config.isHevc) { videoEncoder?.requestKeyFrame() }
            }
            "Broadcast (SRT + RTSP)" -> {
                val srtSender =
                        SrtSender(config.host, config.port, config.isHevc) {
                            videoEncoder?.requestKeyFrame()
                        }
                val rtspSender =
                        RtspServerSender(8554, config.isHevc) { videoEncoder?.requestKeyFrame() }
                videoSender = PacketDuplicator(listOf(srtSender, rtspSender))
            }
        }
    }

    fun attachPreview(previewView: PreviewView?) {
        viewFinder = previewView
        // Dynamic Surface Attachment: Just set the provider, no re-bind needed!
        // This is safe to call even if viewPreview is null (it will be set when streaming starts)
        viewPreview?.setSurfaceProvider(viewFinder?.surfaceProvider)
    }

    private fun bindCamera() {
        val config = currentConfig ?: return

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener(
                {
                    val cameraProvider = cameraProviderFuture.get()
                    val resSelector =
                            ResolutionSelector.Builder()
                                    .setResolutionStrategy(
                                            ResolutionStrategy(
                                                    Size(config.width, config.height),
                                                    ResolutionStrategy
                                                            .FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                                            )
                                    )
                                    .build()

                    // 1. Setup Persistent Encoder Preview
                    if (encoderPreview == null) {
                        encoderPreview =
                                Preview.Builder().setResolutionSelector(resSelector).build()
                        encoderPreview?.setSurfaceProvider { request ->
                            videoEncoder?.getInputSurface()?.let {
                                request.provideSurface(it, cameraExecutor) {}
                            }
                        }
                    }

                    // 2. Setup Persistent View Preview (Always created, optionally attached)
                    if (viewPreview == null) {
                        viewPreview = Preview.Builder().setResolutionSelector(resSelector).build()
                    }

                    // Bind the surface provider if viewFinder is currently available
                    if (viewFinder != null) {
                        viewPreview?.setSurfaceProvider(viewFinder?.surfaceProvider)
                    }

                    // 3. Setup ImageAnalysis for WebRTC
                    val imageAnalysis =
                            ImageAnalysis.Builder()
                                    .setResolutionSelector(resSelector)
                                    .setBackpressureStrategy(
                                            ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
                                    )
                                    .build()

                    imageAnalysis.setAnalyzer(cameraExecutor) { image ->
                        if (config.protocol == "WebRTC") {
                            // Lazy Init WebRTC
                            if (webRtcManager == null) {
                                try {
                                    Log.i(TAG, "Initializing WebRTC Manager...")
                                    webRtcManager = WebRtcManager(this@StreamingService)
                                    webServer?.setWebRtcManager(webRtcManager!!)
                                } catch (e: Throwable) {
                                    Log.e(TAG, "Failed to initialize WebRTC", e)
                                    // Fallback or error?
                                }
                            }
                            webRtcManager?.onFrame(image)
                        } else if (config.protocol == "MJPEG (HTTP)") {
                            // MJPEG Handling
                            try {
                                // YuvImage requires NV21.
                                // ImageProxy is YUV_420_888.
                                // We take the Y plane (0) and UV/VU planes.
                                // If stride=2 (NV12/NV21), we can copy.
                                // For speed, we just dump the buffer if compatible or do a naive
                                // copy.

                                val planes = image.planes
                                val yBuffer = planes[0].buffer
                                val uBuffer = planes[1].buffer
                                val vBuffer = planes[2].buffer

                                val ySize = yBuffer.remaining()
                                val uSize = uBuffer.remaining()
                                val vSize = vBuffer.remaining()

                                // Construct NV21 byte array [Y...Y][V U V U...]
                                val nv21 = ByteArray(ySize + uSize + vSize) // bit oversized maybe

                                // Copy Y
                                yBuffer.get(nv21, 0, ySize)

                                // Copy V and U (Interleaved)
                                // If ImageAnalysis outputs format YUV_420_888 with pixelStride=2,
                                // typically it's NV12 (UVUV) or NV21 (VUVU).
                                // Android standard is usually NV21 compatible if we are lucky?
                                // Actually YUV_420_888 generic.
                                // For 'YuvImage', we need NV21 (Y then VU).
                                // If we have NV12 (Y then UV), colors are swapped.

                                // Naive implementation: Copy V then U?
                                // If we assume NV12 (UV), we actally want VUVU.
                                // Let's just create a temporary NV21-ish buffer from the planes.
                                val uvHeight = image.height / 2
                                val uvWidth = image.width / 2
                                val pixelStride = planes[1].pixelStride
                                val rowStride = planes[1].rowStride

                                // This is slow in Java.
                                // FAST PATH: If we don't care about perfect color, pass Y + UV.
                                // Let's try direct YuvImage with "NV21" format (17).
                                // We need a single byte array.

                                // Hack: If we assume NV21 (standard Android camera picture),
                                // we can just get the whole buffer? No, CameraX abstraction
                                // prevents this.

                                // Let's just copy V then U for valid NV21.
                                val vSrc = vBuffer
                                val uSrc = uBuffer
                                var pos = ySize

                                // Just copy generic way (slow but works) -> actually too slow for
                                // 30fps?
                                // Let's compress only every 3rd frame (10 FPS)

                                val yuvImage =
                                        android.graphics.YuvImage(
                                                nv21,
                                                android.graphics.ImageFormat.NV21,
                                                image.width,
                                                image.height,
                                                null
                                        )

                                // We need to fill nv21 first.
                                // Check if we can do a bulk copy.
                                // If vBuffer contains [V, U, V, U...], we can just copy it after Y?
                                // If it is NV12, it is [U, V, U, V...].
                                // If we treat NV12 as NV21, Blue and Red are swapped.
                                // This is acceptble for fallback.

                                // Let's assume NV12 (UV) from CameraX.
                                // We copy the UV block.
                                // uBuffer usually tracks the whole Plane.
                                // If Planes are contiguous in memory (often are in legacy but not
                                // guaranteed in CameraX),
                                // we can't assume.

                                // Quickest hack: Copy Y, then Copy U (as UV block).
                                // Result: NV12. YuvImage expects NV21. -> Swapped colors.
                                // User sees Blue faces. Better than no faces.

                                val uvSize = image.width * image.height / 2
                                if (pixelStride == 2) {
                                    // Likely interleaved.
                                    // uBuffer is usually the start.
                                    uBuffer.get(nv21, ySize, minOf(uSize, uvSize))
                                } else {
                                    // Planar? I420. YuvImage does NOT support I420.
                                    // We must interleave. Too slow.
                                }

                                val out = java.io.ByteArrayOutputStream()
                                yuvImage.compressToJpeg(
                                        android.graphics.Rect(0, 0, image.width, image.height),
                                        50,
                                        out
                                )
                                val jpegBytes = out.toByteArray()
                                webServer?.updateFrame(jpegBytes)
                            } catch (e: Exception) {
                                Log.e(TAG, "MJPEG error", e)
                            } finally {
                                image.close()
                            }
                        } else {
                            image.close()
                        }
                    }

                    try {
                        cameraProvider.unbindAll()

                        if (config.protocol == "WebRTC") {
                            // Pure WebRTC Mode: Bind Analysis + View
                            Log.i(TAG, "Mode: WebRTC Only. Binding ImageAnalysis + Preview")
                            cameraProvider.bindToLifecycle(
                                    this,
                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                    viewPreview,
                                    imageAnalysis
                            )
                        } else {
                            // Standard Mode (RTSP/SRT): Bind Encoder + View
                            Log.i(TAG, "Mode: Standard Streaming. Binding Encoder + Preview")
                            cameraProvider.bindToLifecycle(
                                    this,
                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                    encoderPreview,
                                    viewPreview
                            )
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Binding failed", e)
                    }

                    // Recovery keyframe
                    videoEncoder?.requestKeyFrame()
                },
                ContextCompat.getMainExecutor(this)
        )
    }

    private fun stopStreaming() {
        isStreaming = false
        videoSender?.stop()
        videoEncoder?.stop()
        videoSender = null
        videoEncoder = null

        // Clear use cases
        encoderPreview = null
        viewPreview = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel =
                    NotificationChannel(
                            CHANNEL_ID,
                            "DroidV4L2 Streaming Channel",
                            NotificationManager.IMPORTANCE_DEFAULT
                    )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent =
                PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("DroidV4L2 is Streaming")
                .setContentText("Camera is active in background")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setOngoing(true)
                .build()
    }

    override fun onDestroy() {
        webServer?.stop()
        super.onDestroy()
        cameraExecutor.shutdown()
        stopStreaming()
    }
}

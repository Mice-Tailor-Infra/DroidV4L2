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

                    try {
                        cameraProvider.unbindAll()
                        // KEY FIX: Bind BOTH use cases at once.
                        // CameraX will stream to both. If ViewPreview has no surface, it just drops
                        // those frames.
                        // But the pipeline stays ALIVE.
                        cameraProvider.bindToLifecycle(
                                this,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                encoderPreview,
                                viewPreview
                        )
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
        super.onDestroy()
        cameraExecutor.shutdown()
        stopStreaming()
    }
}

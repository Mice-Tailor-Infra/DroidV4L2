package com.cagedbird.droidv4l2

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private val TAG = "DroidV4L2"
    private lateinit var cameraExecutor: ExecutorService
    private var viewFinder: PreviewView? = null
    private var videoEncoder: VideoEncoder? = null
    private var srtSender: SrtSender? = null

    private val targetHost = "10.0.0.17" 
    private val targetPort = 5000

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = FrameLayout(this)
        viewFinder = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        container.addView(viewFinder)
        setContentView(container)
        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    override fun onResume() {
        super.onResume()
        if (allPermissionsGranted()) {
            startStreaming()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 10)
        }
    }

    // 监听旋转，动态改变分辨率
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.i(TAG, "[UI] Orientation changed, restarting stream for sync")
        startStreaming()
    }

    private fun startStreaming() {
        stopStreaming()
        
        // 动态计算分辨率：横屏 1280x720, 竖屏 720x1280
        val isPortrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        val width = if (isPortrait) 720 else 1280
        val height = if (isPortrait) 1280 else 720
        
        Log.i(TAG, "[ENCODER] Initializing at ${width}x${height}")

        srtSender = SrtSender(targetHost, targetPort) {
            Log.i(TAG, "[NETWORK] SRT Handshake OK, requesting IDR")
            videoEncoder?.requestKeyFrame()
        }
        srtSender?.start()
        
        videoEncoder = VideoEncoder(width, height, 2_500_000, 30) { data, ts, flags ->
            srtSender?.send(data, ts, flags)
        }
        videoEncoder?.start()
        
        bindCamera(width, height)
    }

    private fun bindCamera(width: Int, height: Int) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            val resSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(ResolutionStrategy(Size(width, height), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                .build()

            val preview = Preview.Builder().setResolutionSelector(resSelector).build().also { it.setSurfaceProvider(viewFinder?.surfaceProvider) }
            val encoderPreview = Preview.Builder().setResolutionSelector(resSelector).build()
            
            videoEncoder?.getInputSurface()?.let { surface ->
                encoderPreview.setSurfaceProvider { request -> request.provideSurface(surface, cameraExecutor) { } }
            }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, encoderPreview)
                Log.d(TAG, "[CAMERA] Bound successfully")
            } catch (exc: Exception) {
                Log.e(TAG, "[CAMERA] Bind failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopStreaming() {
        srtSender?.stop()
        videoEncoder?.stop()
        srtSender = null
        videoEncoder = null
    }

    private fun allPermissionsGranted() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    override fun onPause() {
        super.onPause()
        stopStreaming()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
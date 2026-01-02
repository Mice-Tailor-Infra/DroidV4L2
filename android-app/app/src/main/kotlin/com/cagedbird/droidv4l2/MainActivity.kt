package com.cagedbird.droidv4l2

import android.Manifest
import android.content.pm.PackageManager
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
            startHeartbeat()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 10)
        }
    }

    override fun onPause() {
        super.onPause()
        stopEverything()
    }

    private fun startHeartbeat() {
        Log.i(TAG, "[SYSTEM] Starting SRT Heartbeat...")
        srtSender = SrtSender(targetHost, targetPort) {
            // 这就是心跳通了之后的回调
            Log.i(TAG, "[SYSTEM] Connection established! Activating stream...")
            videoEncoder?.requestKeyFrame()
        }
        srtSender?.start()
        
        // 相机和编码器先初始化好，但 SRT 会在底层过滤数据直到连通
        bindCamera()
    }

    private fun bindCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            
            val resSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                .build()

            val preview = Preview.Builder().setResolutionSelector(resSelector).build().also { it.setSurfaceProvider(viewFinder?.surfaceProvider) }
            val encoderPreview = Preview.Builder().setResolutionSelector(resSelector).build()
            
            encoderPreview.setSurfaceProvider { request ->
                val res = request.resolution
                videoEncoder?.stop()
                videoEncoder = VideoEncoder(res.width, res.height, 2_000_000, 30) { data, ts, flags ->
                    srtSender?.send(data, ts, flags)
                }
                videoEncoder?.start()
                
                videoEncoder?.getInputSurface()?.let { surface ->
                    request.provideSurface(surface, cameraExecutor) { }
                }
            }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, encoderPreview)
            } catch (exc: Exception) {
                Log.e(TAG, "[CAMERA] Bind error", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopEverything() {
        Log.i(TAG, "[SYSTEM] Stopping all services")
        srtSender?.stop()
        videoEncoder?.stop()
        srtSender = null
        videoEncoder = null
    }

    private fun allPermissionsGranted() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
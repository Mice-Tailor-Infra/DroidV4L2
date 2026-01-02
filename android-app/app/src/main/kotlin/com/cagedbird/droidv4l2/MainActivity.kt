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
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
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
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        container.addView(viewFinder)
        setContentView(container)
        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    override fun onResume() {
        super.onResume()
        if (allPermissionsGranted()) {
            startEverything()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 10)
        }
    }

    override fun onPause() {
        super.onPause()
        stopEverything()
    }

    private fun startEverything() {
        // 固定编码器分辨率为 720p 16:9 比例
        val isPortrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        val width = if (isPortrait) 720 else 1280
        val height = if (isPortrait) 1280 else 720
        
        Log.i(TAG, "[START] Resolution: ${width}x${height}")

        srtSender = SrtSender(targetHost, targetPort) {
            Log.i(TAG, "[SYNC] Connected, requesting Keyframe")
            videoEncoder?.requestKeyFrame()
        }
        srtSender?.start()
        
        videoEncoder = VideoEncoder(width, height, 2_000_000, 30) { data, ts, flags ->
            srtSender?.send(data, ts, flags)
        }
        videoEncoder?.start()
        
        bindCamera(width, height)
    }

    private fun bindCamera(width: Int, height: Int) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            
            // 1. 预览 UseCase：使用 AspectRatio，极其稳定
            val preview = Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                .build()
                .also { it.setSurfaceProvider(viewFinder?.surfaceProvider) }

            // 2. 编码 UseCase：锁定到我们想要的宽高
            val encoderPreview = Preview.Builder()
                .setTargetResolution(Size(width, height))
                .build()
            
            videoEncoder?.getInputSurface()?.let { surface ->
                encoderPreview.setSurfaceProvider { request ->
                    request.provideSurface(surface, cameraExecutor) { }
                }
            }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, encoderPreview)
                Log.d(TAG, "[CAMERA] Bind success")
            } catch (exc: Exception) {
                Log.e(TAG, "[CAMERA] Bind failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopEverything() {
        videoEncoder?.stop()
        srtSender?.stop()
        videoEncoder = null
        srtSender = null
    }

    private fun allPermissionsGranted() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
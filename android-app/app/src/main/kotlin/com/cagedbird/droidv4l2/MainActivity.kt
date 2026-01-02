package com.cagedbird.droidv4l2

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
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
        // 先建立网络连接
        srtSender = SrtSender(targetHost, targetPort) {
            videoEncoder?.requestKeyFrame()
        }
        srtSender?.start()
        
        // 绑定相机
        bindCamera()
    }

    private fun bindCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            
            // 使用最宽容的选择器
            val resSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(ResolutionStrategy(
                    Size(1280, 720), 
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                ))
                .build()

            // 预览 UseCase
            val preview = Preview.Builder()
                .setResolutionSelector(resSelector)
                .build()
                .also { it.setSurfaceProvider(viewFinder?.surfaceProvider) }

            // 编码 UseCase
            val encoderPreview = Preview.Builder()
                .setResolutionSelector(resSelector)
                .build()
            
            // 关键：在收到 CameraX 的实际 Surface 请求时，才初始化 Encoder
            encoderPreview.setSurfaceProvider { request ->
                val resolution = request.resolution
                Log.i(TAG, "[CAMERA] Real Resolution: ${resolution.width}x${resolution.height}")
                
                // 根据实际分辨率重启编码器
                initEncoder(resolution.width, resolution.height)
                
                videoEncoder?.getInputSurface()?.let { surface ->
                    request.provideSurface(surface, cameraExecutor) { }
                }
            }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, encoderPreview)
                Log.d(TAG, "[CAMERA] Bind Successful")
            } catch (exc: Exception) {
                Log.e(TAG, "[CAMERA] Bind Failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun initEncoder(width: Int, height: Int) {
        videoEncoder?.stop()
        videoEncoder = VideoEncoder(width, height, 2_000_000, 30) { data, ts, flags ->
            srtSender?.send(data, ts, flags)
        }
        videoEncoder?.start()
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
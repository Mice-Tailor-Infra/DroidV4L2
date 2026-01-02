package com.cagedbird.droidv4l2

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.util.Size
import androidx.appcompat.app.AppCompatActivity
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
    private lateinit var viewFinder: PreviewView
    private var videoEncoder: VideoEncoder? = null
    private var srtSender: SrtSender? = null

    private val targetHost = "10.0.0.17" 
    private val targetPort = 5000

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewFinder = PreviewView(this)
        setContentView(viewFinder)

        cameraExecutor = Executors.newSingleThreadExecutor()

        if (allPermissionsGranted()) {
            startEverything()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 10)
        }
    }

    private fun startEverything() {
        srtSender = SrtSender(targetHost, targetPort)
        srtSender?.start()
        
        videoEncoder = VideoEncoder(1280, 720, 2_000_000, 30) { data, ts, flags ->
            srtSender?.send(data, ts, flags)
        }
        videoEncoder?.start()
        
        startCamera()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(viewFinder.surfaceProvider)
            }

            val encoderPreview = Preview.Builder()
                .setTargetResolution(Size(1280, 720))
                .build()
            
            videoEncoder?.getInputSurface()?.let { surface ->
                encoderPreview.setSurfaceProvider { request ->
                    request.provideSurface(surface, cameraExecutor) { }
                }
            }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, encoderPreview)
            } catch (exc: Exception) {
                Log.e(TAG, "Bind failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun allPermissionsGranted() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    override fun onDestroy() {
        super.onDestroy()
        videoEncoder?.stop()
        srtSender?.stop()
        cameraExecutor.shutdown()
    }
}
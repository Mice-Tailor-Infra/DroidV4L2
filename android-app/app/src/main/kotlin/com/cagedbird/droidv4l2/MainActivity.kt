package com.cagedbird.droidv4l2

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        viewFinder = findViewById(R.id.viewFinder)
        cameraExecutor = Executors.newSingleThreadExecutor()

        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val editIp = findViewById<EditText>(R.id.editIp)
        val editBitrate = findViewById<EditText>(R.id.editBitrate)
        val btnApply = findViewById<Button>(R.id.btnApply)

        editIp.setText(prefs.getString("ip", "10.0.0.17"))
        editBitrate.setText(prefs.getInt("bitrate", 10).toString())

        btnApply.setOnClickListener {
            val ip = editIp.text.toString()
            val bitrate = editBitrate.text.toString().toIntOrNull() ?: 10
            
            prefs.edit().putString("ip", ip).putInt("bitrate", bitrate).apply()
            
            Log.i(TAG, "Settings updated: $ip, ${bitrate}Mbps")
            startEverything()
        }

        if (allPermissionsGranted()) {
            startEverything()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 10)
        }
    }

    private fun startEverything() {
        stopEverything()
        
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val host = prefs.getString("ip", "10.0.0.17")!!
        val bitrateMbps = prefs.getInt("bitrate", 10)
        
        val statusText = findViewById<TextView>(R.id.txtStatus)
        statusText.text = "Status: Connecting to $host..."

        srtSender = SrtSender(host, 5000) {
            runOnUiThread { statusText.text = "Status: CONNECTED" }
            videoEncoder?.requestKeyFrame()
        }
        srtSender?.start()
        
        // 编码分辨率暂定 720p，后续可以加 Spinner 选择
        videoEncoder = VideoEncoder(1280, 720, bitrateMbps * 1_000_000, 30) { data, ts, flags ->
            srtSender?.send(data, ts, flags)
        }
        videoEncoder?.start()
        
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

    private fun stopEverything() {
        srtSender?.stop()
        videoEncoder?.stop()
        srtSender = null
        videoEncoder = null
    }

    override fun onResume() {
        super.onResume()
        if (allPermissionsGranted()) startEverything()
    }

    override fun onPause() {
        super.onPause()
        stopEverything()
    }

    private fun allPermissionsGranted() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
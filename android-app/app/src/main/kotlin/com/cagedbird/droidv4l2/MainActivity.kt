package com.cagedbird.droidv4l2

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.widget.*
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
        val spinnerRes = findViewById<Spinner>(R.id.spinnerRes)
        val spinnerFps = findViewById<Spinner>(R.id.spinnerFps)

        // 初始化 UI 默认值
        val resOptions = arrayOf("720p", "1080p", "480p")
        spinnerRes.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, resOptions)
        spinnerRes.setSelection(resOptions.indexOf(prefs.getString("res", "720p")))

        val fpsOptions = arrayOf("30", "60")
        spinnerFps.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, fpsOptions)
        spinnerFps.setSelection(fpsOptions.indexOf(prefs.getString("fps", "30")))

        editIp.setText(prefs.getString("ip", "10.0.0.17"))
        editBitrate.setText(prefs.getInt("bitrate", 10).toString())

        findViewById<Button>(R.id.btnApply).setOnClickListener {
            prefs.edit().putString("ip", editIp.text.toString())
                .putInt("bitrate", editBitrate.text.toString().toIntOrNull() ?: 10)
                .putString("res", spinnerRes.selectedItem.toString())
                .putString("fps", spinnerFps.selectedItem.toString())
                .apply()
            
            Log.i(TAG, "[UI] Applying new settings and restarting stream")
            startStreaming()
        }
        
        // 注意：onCreate 里不再直接启动，全部交给 onResume 处理
    }

    override fun onResume() {
        super.onResume()
        if (allPermissionsGranted()) {
            startStreaming()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 10)
        }
    }

    override fun onPause() {
        super.onPause()
        stopStreaming()
    }

    private fun startStreaming() {
        stopStreaming()
        
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val host = prefs.getString("ip", "10.0.0.17")!!
        val bitrate = prefs.getInt("bitrate", 10)
        val resStr = prefs.getString("res", "720p")
        val fps = prefs.getString("fps", "30")?.toInt() ?: 30

        val (w, h) = when(resStr) {
            "1080p" -> 1920 to 1080
            "480p" -> 640 to 480
            else -> 1280 to 720
        }

        findViewById<TextView>(R.id.txtStatus).text = "Status: Initializing..."

        srtSender = SrtSender(host, 5000) {
            runOnUiThread { findViewById<TextView>(R.id.txtStatus).text = "Status: CONNECTED" }
            videoEncoder?.requestKeyFrame()
        }
        srtSender?.start()
        
        videoEncoder = VideoEncoder(w, h, bitrate * 1_000_000, fps) { data, ts, flags ->
            srtSender?.send(data, ts, flags)
        }
        videoEncoder?.start()
        
        bindCamera(w, h)
    }

    private fun bindCamera(width: Int, height: Int) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val resSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(ResolutionStrategy(Size(width, height), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                .build()

            val preview = Preview.Builder().setResolutionSelector(resSelector).build().also { it.setSurfaceProvider(viewFinder?.surfaceProvider) }
            val encoderPreview = Preview.Builder().setResolutionSelector(resSelector).build()
            
            encoderPreview.setSurfaceProvider { request ->
                videoEncoder?.getInputSurface()?.let { request.provideSurface(it, cameraExecutor) { } }
            }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, encoderPreview)
            } catch (e: Exception) { Log.e(TAG, "Bind failed", e) }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopStreaming() {
        Log.d(TAG, "[CLEANUP] Stopping streaming components")
        srtSender?.stop()
        videoEncoder?.stop()
        srtSender = null
        videoEncoder = null
    }

    private fun allPermissionsGranted() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    override fun onDestroy() { super.onDestroy(); cameraExecutor.shutdown() }
}
package com.cagedbird.droidv4l2

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
    private val mainHandler = Handler(Looper.getMainLooper())

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
        val spinnerCodec = findViewById<Spinner>(R.id.spinnerCodec)

        val resOptions = arrayOf("720p", "1080p", "480p")
        spinnerRes.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, resOptions)
        spinnerRes.setSelection(resOptions.indexOf(prefs.getString("res", "720p")))

        val fpsOptions = arrayOf("30", "60")
        spinnerFps.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, fpsOptions)
        spinnerFps.setSelection(fpsOptions.indexOf(prefs.getString("fps", "30")))

        val codecOptions = arrayOf("H.264", "H.265")
        spinnerCodec.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, codecOptions)
        spinnerCodec.setSelection(codecOptions.indexOf(prefs.getString("codec", "H.264")))

        editIp.setText(prefs.getString("ip", "10.0.0.17"))
        editBitrate.setText(prefs.getInt("bitrate", 10).toString())

        findViewById<Button>(R.id.btnApply).setOnClickListener {
            prefs.edit().putString("ip", editIp.text.toString())
                .putInt("bitrate", editBitrate.text.toString().toIntOrNull() ?: 10)
                .putString("res", spinnerRes.selectedItem.toString())
                .putString("fps", spinnerFps.selectedItem.toString())
                .putString("codec", spinnerCodec.selectedItem.toString())
                .apply()
            
            Log.i(TAG, "[UI] Applying settings...")
            restartStreamingWithDelay()
        }
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

    private fun restartStreamingWithDelay() {
        stopStreaming()
        findViewById<TextView>(R.id.txtStatus).text = "Status: Resetting pipeline (200ms)..."
        // 极限优化：5ms 延迟，瞬间恢复
        mainHandler.postDelayed({
            startStreaming()
        }, 200)
    }

    private fun startStreaming() {
        // 防止重复调用
        if (srtSender != null) return

        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val host = prefs.getString("ip", "10.0.0.17")!!
        val bitrate = prefs.getInt("bitrate", 10)
        val resStr = prefs.getString("res", "720p")
        val fps = prefs.getString("fps", "30")?.toInt() ?: 30
        val codecStr = prefs.getString("codec", "H.264")

        val (w, h) = when(resStr) {
            "1080p" -> 1920 to 1080
            "480p" -> 640 to 480
            else -> 1280 to 720
        }

        val isHevc = (codecStr == "H.265")
        val mimeType = if (isHevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        val port = if (isHevc) 5001 else 5000

        findViewById<TextView>(R.id.txtStatus).text = "Status: Initializing $codecStr on port $port..."

        videoEncoder = VideoEncoder(w, h, bitrate * 1_000_000, fps, mimeType) { data, ts, flags ->
            srtSender?.send(data, ts, flags)
        }
        videoEncoder?.start()

        srtSender = SrtSender(host, port, isHevc) {
            runOnUiThread { findViewById<TextView>(R.id.txtStatus).text = "Status: CONNECTED ($codecStr)" }
            videoEncoder?.requestKeyFrame()
        }
        srtSender?.start()
        
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
        mainHandler.removeCallbacksAndMessages(null) // 清理之前的延迟任务
        srtSender?.stop()
        videoEncoder?.stop()
        srtSender = null
        videoEncoder = null
    }

    private fun allPermissionsGranted() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    override fun onDestroy() { super.onDestroy(); cameraExecutor.shutdown() }
}
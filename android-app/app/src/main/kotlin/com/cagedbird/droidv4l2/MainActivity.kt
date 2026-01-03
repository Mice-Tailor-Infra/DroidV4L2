package com.cagedbird.droidv4l2

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private val TAG = "DroidV4L2"

    // Service Binding
    private var streamingService: StreamingService? = null
    private var isBound = false
    private val connection =
            object : ServiceConnection {
                override fun onServiceConnected(className: ComponentName, service: IBinder) {
                    val binder = service as StreamingService.LocalBinder
                    streamingService = binder.getService()
                    isBound = true
                    streamingService?.attachPreview(viewFinder)
                }
                override fun onServiceDisconnected(arg0: ComponentName) {
                    isBound = false
                    streamingService = null
                }
            }

    private var viewFinder: PreviewView? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // NsdManager
    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private val SERVICE_TYPE = "_droidv4l2._tcp."
    private var isDiscovering = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        viewFinder = findViewById(R.id.viewFinder)

        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val editIp = findViewById<EditText>(R.id.editIp)
        val editBitrate = findViewById<EditText>(R.id.editBitrate)
        val spinnerRes = findViewById<Spinner>(R.id.spinnerRes)
        val spinnerFps = findViewById<Spinner>(R.id.spinnerFps)
        val spinnerCodec = findViewById<Spinner>(R.id.spinnerCodec)
        val spinnerProtocol = findViewById<Spinner>(R.id.spinnerProtocol)

        val resOptions = arrayOf("720p", "1080p", "480p")
        spinnerRes.adapter =
                ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, resOptions)
        spinnerRes.setSelection(resOptions.indexOf(prefs.getString("res", "720p")))

        val fpsOptions = arrayOf("30", "60")
        spinnerFps.adapter =
                ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, fpsOptions)
        spinnerFps.setSelection(fpsOptions.indexOf(prefs.getString("fps", "30")))

        val codecOptions = arrayOf("H.264", "H.265")
        spinnerCodec.adapter =
                ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, codecOptions)
        spinnerCodec.setSelection(codecOptions.indexOf(prefs.getString("codec", "H.264")))

        val protocolOptions = arrayOf("SRT (Caller)", "RTSP (Server)", "Broadcast (SRT + RTSP)")
        spinnerProtocol.adapter =
                ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, protocolOptions)
        spinnerProtocol.setSelection(
                protocolOptions.indexOf(prefs.getString("protocol", "SRT (Caller)"))
        )

        editIp.setText(prefs.getString("ip", "10.0.0.17"))
        editBitrate.setText(prefs.getInt("bitrate", 10).toString())

        findViewById<Button>(R.id.btnApply).setOnClickListener {
            prefs.edit()
                    .putString("ip", editIp.text.toString())
                    .putInt("bitrate", editBitrate.text.toString().toIntOrNull() ?: 10)
                    .putString("res", spinnerRes.selectedItem.toString())
                    .putString("fps", spinnerFps.selectedItem.toString())
                    .putString("codec", spinnerCodec.selectedItem.toString())
                    .putString("protocol", spinnerProtocol.selectedItem.toString())
                    .apply()

            Log.i(TAG, "[UI] Applying settings...")
            restartStreamingWithDelay()
        }

        findViewById<Button>(R.id.btnAutoFind).setOnClickListener { startDiscovery(editIp) }
    }

    private fun startDiscovery(editIp: EditText) {
        if (isDiscovering) return
        isDiscovering = true
        findViewById<Button>(R.id.btnAutoFind).text = "Searching..."

        // Acquire Multicast Lock
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock =
                wifi.createMulticastLock("multicastLock").apply {
                    setReferenceCounted(true)
                    acquire()
                }

        nsdManager = (getSystemService(Context.NSD_SERVICE) as NsdManager)

        discoveryListener =
                object : NsdManager.DiscoveryListener {
                    override fun onDiscoveryStarted(regType: String) {
                        Log.d(TAG, "Service discovery started")
                    }

                    override fun onServiceFound(service: NsdServiceInfo) {
                        Log.d(TAG, "Service discovery found ${service}")
                        if (service.serviceType.contains("_droidv4l2")) {
                            nsdManager?.resolveService(
                                    service,
                                    object : NsdManager.ResolveListener {
                                        override fun onResolveFailed(
                                                serviceInfo: NsdServiceInfo,
                                                errorCode: Int
                                        ) {
                                            Log.e(TAG, "Resolve failed: $errorCode")
                                        }
                                        override fun onServiceResolved(
                                                serviceInfo: NsdServiceInfo
                                        ) {
                                            Log.i(TAG, "Resolve Succeeded. ${serviceInfo}")
                                            val host = serviceInfo.host
                                            runOnUiThread {
                                                editIp.setText(host.hostAddress)
                                                findViewById<Button>(R.id.btnAutoFind).text =
                                                        "Found!"
                                                mainHandler.postDelayed(
                                                        {
                                                            findViewById<Button>(R.id.btnAutoFind)
                                                                    .text = "🔍 Auto"
                                                        },
                                                        2000
                                                )
                                                stopDiscovery()
                                            }
                                        }
                                    }
                            )
                        }
                    }

                    override fun onServiceLost(service: NsdServiceInfo) {
                        Log.e(TAG, "service lost: $service")
                    }

                    override fun onDiscoveryStopped(serviceType: String) {
                        Log.i(TAG, "Discovery stopped: $serviceType")
                        isDiscovering = false
                        multicastLock?.release()
                    }

                    override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                        Log.e(TAG, "Discovery failed: Error code:$errorCode")
                        nsdManager?.stopServiceDiscovery(this)
                    }

                    override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                        Log.e(TAG, "Discovery failed: Error code:$errorCode")
                        nsdManager?.stopServiceDiscovery(this)
                    }
                }

        nsdManager?.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    private fun stopDiscovery() {
        if (!isDiscovering) return
        try {
            nsdManager?.stopServiceDiscovery(discoveryListener)
        } catch (e: Exception) {
            Log.e(TAG, "Stop discovery failed", e)
        }
    }

    override fun onStart() {
        super.onStart()
        Intent(this, StreamingService::class.java).also { intent ->
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            streamingService?.attachPreview(null) // Detach preview but keep service running
            unbindService(connection)
            isBound = false
        }
    }

    // Permissions are handled in onResume logic below
    override fun onResume() {
        super.onResume()
        if (!allPermissionsGranted()) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 10)
        }
    }

    // Streaming control is now explicit via buttons, not implicit in onResume/onPause

    private fun restartStreamingWithDelay() {
        // UI Action: Stop Service
        Intent(this, StreamingService::class.java).also { intent ->
            intent.action = "STOP"
            startService(intent) // or startForegroundService, serves as command
        }

        findViewById<TextView>(R.id.txtStatus).text = "Status: Restarting Service (500ms)..."
        mainHandler.postDelayed({ startStreaming() }, 500)
    }

    private fun startStreaming() {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val host = prefs.getString("ip", "10.0.0.17")!!
        val bitrate = prefs.getInt("bitrate", 10)
        val resStr = prefs.getString("res", "720p")
        val fps = prefs.getString("fps", "30")?.toInt() ?: 30
        val codecStr = prefs.getString("codec", "H.264")
        val protocolStr = prefs.getString("protocol", "SRT (Caller)")

        val (w, h) =
                when (resStr) {
                    "1080p" -> 1920 to 1080
                    "480p" -> 640 to 480
                    else -> 1280 to 720
                }
        val isHevc = (codecStr == "H.265")

        findViewById<TextView>(R.id.txtStatus).text = "Status: Starting Service ($protocolStr)..."

        Intent(this, StreamingService::class.java).also { intent ->
            intent.action = "START"
            intent.putExtra("host", host)
            intent.putExtra("port", if (isHevc) 5001 else 5000)
            intent.putExtra("bitrate", bitrate)
            intent.putExtra("fps", fps)
            intent.putExtra("width", w)
            intent.putExtra("height", h)
            intent.putExtra("isHevc", isHevc)
            intent.putExtra("protocol", protocolStr)

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }
    }

    private fun bindCamera(width: Int, height: Int) {
        // Logic moved to Service
    }

    private fun stopStreaming() {
        // Logic moved to Service "STOP" action
    }

    private fun allPermissionsGranted() =
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
    override fun onDestroy() {
        super.onDestroy()
        // cameraExecutor is now in Service
    }
}

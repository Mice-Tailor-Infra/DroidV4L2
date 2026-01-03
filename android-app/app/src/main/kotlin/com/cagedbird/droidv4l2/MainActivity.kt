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
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText

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
                    updateUiState(true)
                }
                override fun onServiceDisconnected(arg0: ComponentName) {
                    isBound = false
                    streamingService = null
                    updateUiState(false)
                }
            }

    private var viewFinder: PreviewView? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // UI Components
    private lateinit var editIp: TextInputEditText
    private lateinit var editBitrate: EditText
    private lateinit var spinnerRes: Spinner
    private lateinit var spinnerFps: Spinner
    private lateinit var spinnerCodec: Spinner
    private lateinit var spinnerProtocol: Spinner
    private lateinit var txtStatus: TextView
    private lateinit var txtMyIp: TextView
    private lateinit var txtStreamUrl: TextView
    private lateinit var btnToggle: FloatingActionButton
    private lateinit var btnAutoFind: Button

    // State
    private var isStreaming = false
    private var selectedMyIp: String = "127.0.0.1"

    // NsdManager (Auto Discovery)
    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private val SERVICE_TYPE = "_droidv4l2._tcp."
    private var isDiscovering = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Bind Views
        viewFinder = findViewById(R.id.viewFinder)
        editIp = findViewById(R.id.editIp)
        editBitrate = findViewById(R.id.editBitrate)
        spinnerRes = findViewById(R.id.spinnerRes)
        spinnerFps = findViewById(R.id.spinnerFps)
        spinnerCodec = findViewById(R.id.spinnerCodec)
        spinnerProtocol = findViewById(R.id.spinnerProtocol)
        txtStatus = findViewById(R.id.txtStatus)
        txtMyIp = findViewById(R.id.txtMyIp)
        txtStreamUrl = findViewById(R.id.txtStreamUrl)
        btnToggle = findViewById(R.id.btnToggle)
        btnAutoFind = findViewById(R.id.btnAutoFind)

        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)

        // 1. Setup Spinners
        setupSpinners(prefs)

        // 2. Setup Inputs
        editIp.setText(prefs.getString("ip", "10.0.0.17"))
        editBitrate.setText(prefs.getInt("bitrate", 10).toString())

        // 3. Setup Buttons
        btnToggle.setOnClickListener {
            if (isStreaming) {
                stopStreaming()
            } else {
                saveSettings()
                startStreaming()
            }
        }

        btnAutoFind.setOnClickListener { startDiscovery() }

        // 4. Multi-NIC & URL Logic
        updateNetworkInfo()
        txtMyIp.setOnClickListener { showIpSelectionDialog() }

        // Listeners for URL updates
        // Simple TextWatcher for IP
        editIp.addTextChangedListener(
                object : android.text.TextWatcher {
                    override fun afterTextChanged(s: android.text.Editable?) {
                        updateDynamicUrl()
                    }
                    override fun beforeTextChanged(
                            s: CharSequence?,
                            start: Int,
                            count: Int,
                            after: Int
                    ) {}
                    override fun onTextChanged(
                            s: CharSequence?,
                            start: Int,
                            before: Int,
                            count: Int
                    ) {}
                }
        )
    }

    private fun setupSpinners(prefs: android.content.SharedPreferences) {
        // Resolutions
        val resOptions = arrayOf("720p", "1080p", "480p")
        spinnerRes.adapter =
                ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, resOptions)
        spinnerRes.setSelection(resOptions.indexOf(prefs.getString("res", "720p")))

        // FPS
        val fpsOptions = arrayOf("30", "60")
        spinnerFps.adapter =
                ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, fpsOptions)
        spinnerFps.setSelection(fpsOptions.indexOf(prefs.getString("fps", "30")))

        // Codecs
        val codecOptions = arrayOf("H.264", "H.265", "MJPEG")
        spinnerCodec.adapter =
                ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, codecOptions)
        // Default to H.264
        spinnerCodec.setSelection(codecOptions.indexOf(prefs.getString("codec", "H.264")))

        // Protocols
        val protocolOptions =
                arrayOf("SRT (Caller)", "RTSP (Server)", "HTTP (MJPEG)", "Broadcast (SRT+RTSP)")
        spinnerProtocol.adapter =
                ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, protocolOptions)
        spinnerProtocol.setSelection(
                protocolOptions.indexOf(prefs.getString("protocol", "SRT (Caller)"))
        )

        // Current selection logic
        spinnerProtocol.onItemSelectedListener =
                object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                            parent: AdapterView<*>?,
                            view: View?,
                            position: Int,
                            id: Long
                    ) {
                        val protocol = protocolOptions[position]
                        handleProtocolSelection(protocol)
                        updateDynamicUrl()
                    }
                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }

        // Codec selection listener for URL (e.g. port change)
        spinnerCodec.onItemSelectedListener =
                object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                            parent: AdapterView<*>?,
                            view: View?,
                            position: Int,
                            id: Long
                    ) {
                        updateDynamicUrl()
                    }
                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }
    }

    private fun handleProtocolSelection(protocol: String) {
        val codecAdapter = spinnerCodec.adapter as ArrayAdapter<String>

        if (protocol == "HTTP (MJPEG)") {
            // Force MJPEG
            val mjpegIndex = codecAdapter.getPosition("MJPEG")
            if (mjpegIndex >= 0) spinnerCodec.setSelection(mjpegIndex)
            spinnerCodec.isEnabled = false
            editBitrate.isEnabled = false // Bitrate invalid for MJPEG (it uses Quality)
            editBitrate.alpha = 0.5f
        } else {
            // SRT/RTSP/Broadcast -> Allow H.264/H.265, Disable MJPEG
            spinnerCodec.isEnabled = true
            editBitrate.isEnabled = true
            editBitrate.alpha = 1.0f

            // If current selection is MJPEG, switch to H.264
            if (spinnerCodec.selectedItem.toString() == "MJPEG") {
                val h264Index = codecAdapter.getPosition("H.264")
                spinnerCodec.setSelection(h264Index)
            }
        }
    }

    private fun updateNetworkInfo() {
        val ips = NetworkUtils.getAllIpAddresses()
        if (ips.isNotEmpty()) {
            selectedMyIp = ips[0].ip
            txtMyIp.text = "My IP: $selectedMyIp (${ips[0].name})"
        } else {
            txtMyIp.text = "My IP: Unavailable"
        }
        updateDynamicUrl()
    }

    private fun showIpSelectionDialog() {
        val ips = NetworkUtils.getAllIpAddresses()
        if (ips.isEmpty()) return

        val items = ips.map { "${it.ip} (${it.name})" }.toTypedArray()

        AlertDialog.Builder(this)
                .setTitle("Select Network Interface")
                .setItems(items) { _, which ->
                    selectedMyIp = ips[which].ip
                    txtMyIp.text = "My IP: $selectedMyIp (${ips[which].name})"
                    updateDynamicUrl()
                }
                .show()
    }

    private fun updateDynamicUrl() {
        val protocol = spinnerProtocol.selectedItem?.toString() ?: return
        val ip = editIp.text.toString() // Linux IP
        val myIp = selectedMyIp
        val isHevc = spinnerCodec.selectedItem?.toString() == "H.265"

        // Port Logic matching Service
        // H.264 -> 5000, H.265 -> 5001
        val srtPort = if (isHevc) 5001 else 5000

        val url =
                when (protocol) {
                    "SRT (Caller)" -> "srt://$ip:$srtPort"
                    "RTSP (Server)" -> "rtsp://$myIp:8554/live"
                    "HTTP (MJPEG)" -> "http://$myIp:8080/"
                    "Broadcast (SRT+RTSP)" -> "srt://$ip:$srtPort | rtsp://$myIp:8554/live"
                    else -> "-"
                }

        txtStreamUrl.text = "Target URL: $url"
    }

    private fun saveSettings() {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        prefs.edit()
                .putString("ip", editIp.text.toString())
                .putInt("bitrate", editBitrate.text.toString().toIntOrNull() ?: 10)
                .putString("res", spinnerRes.selectedItem.toString())
                .putString("fps", spinnerFps.selectedItem.toString())
                .putString("codec", spinnerCodec.selectedItem.toString())
                .putString("protocol", spinnerProtocol.selectedItem.toString())
                .apply()
    }

    private fun startStreaming() {
        saveSettings()

        val host = editIp.text.toString()
        val bitrate = editBitrate.text.toString().toIntOrNull() ?: 10
        val resStr = spinnerRes.selectedItem.toString()
        val fps = spinnerFps.selectedItem.toString().toInt()
        val codecStr = spinnerCodec.selectedItem.toString()
        val protocolStr = spinnerProtocol.selectedItem.toString()

        val (w, h) =
                when (resStr) {
                    "1080p" -> 1920 to 1080
                    "480p" -> 640 to 480
                    else -> 1280 to 720
                }
        val isHevc = (codecStr == "H.265")

        // Map Protocol Name to internal Service Protocol Name
        // Service expects: "SRT (Caller)", "RTSP (Server)", "Broadcast (SRT + RTSP)", "MJPEG
        // (HTTP)"
        // My array has "HTTP (MJPEG)". Need to map it.
        // Wait, previously it was "MJPEG (HTTP)". Let's stick to consistent names.
        val serviceProtocol =
                if (protocolStr == "HTTP (MJPEG)") "MJPEG (HTTP)"
                else if (protocolStr == "Broadcast (SRT+RTSP)") "Broadcast (SRT + RTSP)"
                else protocolStr

        updateUiState(true) // Optimistic update

        Intent(this, StreamingService::class.java).also { intent ->
            intent.action = "START"
            intent.putExtra("host", host)
            intent.putExtra("port", if (isHevc) 5001 else 5000)
            intent.putExtra("bitrate", bitrate)
            intent.putExtra("fps", fps)
            intent.putExtra("width", w)
            intent.putExtra("height", h)
            intent.putExtra("isHevc", isHevc)
            intent.putExtra("protocol", serviceProtocol)

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }
        isStreaming = true
    }

    private fun stopStreaming() {
        Intent(this, StreamingService::class.java).also { intent ->
            intent.action = "STOP"
            startService(intent)
        }
        updateUiState(false)
        isStreaming = false
    }

    private fun updateUiState(streaming: Boolean) {
        val color =
                if (streaming) ContextCompat.getColor(this, android.R.color.holo_green_light)
                else ContextCompat.getColor(this, android.R.color.holo_red_light)
        val text = if (streaming) "🟢 STREAMING" else "🔴 IDLE"
        val icon =
                if (streaming) android.R.drawable.ic_media_pause
                else android.R.drawable.ic_media_play
        val btnColor =
                if (streaming) 0xFF444444.toInt()
                else 0xFFFF4444.toInt() // Gray for stop, Red for start

        txtStatus.text = text
        txtStatus.setTextColor(color)

        btnToggle.setImageResource(icon)
        btnToggle.backgroundTintList = android.content.res.ColorStateList.valueOf(btnColor)

        isStreaming = streaming

        // Disable inputs while streaming
        editIp.isEnabled = !streaming
        editBitrate.isEnabled = !streaming
        spinnerRes.isEnabled = !streaming
        spinnerFps.isEnabled = !streaming
        spinnerCodec.isEnabled = !streaming
        spinnerProtocol.isEnabled = !streaming
    }

    private fun startDiscovery() {
        if (isDiscovering) return
        isDiscovering = true
        btnAutoFind.text = "..."

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
                        if (service.serviceType.contains("_droidv4l2")) {
                            nsdManager?.resolveService(
                                    service,
                                    object : NsdManager.ResolveListener {
                                        override fun onResolveFailed(
                                                serviceInfo: NsdServiceInfo,
                                                errorCode: Int
                                        ) {}
                                        override fun onServiceResolved(
                                                serviceInfo: NsdServiceInfo
                                        ) {
                                            val host = serviceInfo.host
                                            runOnUiThread {
                                                editIp.setText(host.hostAddress)
                                                btnAutoFind.text = "✓"
                                                mainHandler.postDelayed(
                                                        { btnAutoFind.text = "🔍 Auto" },
                                                        2000
                                                )
                                                stopDiscovery() // Auto stop
                                            }
                                        }
                                    }
                            )
                        }
                    }
                    override fun onServiceLost(service: NsdServiceInfo) {}
                    override fun onDiscoveryStopped(serviceType: String) {
                        isDiscovering = false
                        multicastLock?.release()
                    }
                    override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                        isDiscovering = false
                    }
                    override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                        isDiscovering = false
                    }
                }

        try {
            nsdManager?.discoverServices(
                    SERVICE_TYPE,
                    NsdManager.PROTOCOL_DNS_SD,
                    discoveryListener
            )
        } catch (e: Exception) {
            isDiscovering = false
            btnAutoFind.text = "Err"
        }
    }

    private fun stopDiscovery() {
        if (!isDiscovering) return
        try {
            nsdManager?.stopServiceDiscovery(discoveryListener)
        } catch (e: Exception) {}
    }

    // Lifecycle
    override fun onStart() {
        super.onStart()
        Intent(this, StreamingService::class.java).also { intent ->
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            streamingService?.attachPreview(null)
            unbindService(connection)
            isBound = false
        }
        stopDiscovery()
    }

    override fun onResume() {
        super.onResume()
        if (!allPermissionsGranted()) {
            ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
                    10
            )
        }
        updateNetworkInfo() // Refresh IPs
    }

    private fun allPermissionsGranted() =
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                            PackageManager.PERMISSION_GRANTED
}

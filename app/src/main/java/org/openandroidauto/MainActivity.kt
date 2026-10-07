package org.openandroidauto

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.openandroidauto.transport.UsbAoaTransport

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "OpenAndroidAuto"
        val REQUIRED_PERMISSIONS = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()
    }

    private var serviceStarted = false

    private val usbReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                UsbManager.ACTION_USB_ACCESSORY_ATTACHED -> {
                    Log.w(TAG, "USB accessory attached (broadcast)")
                    ServiceState.addEvent("USB attached (broadcast)")
                    if (!serviceStarted) checkAutoStart()
                }
                UsbManager.ACTION_USB_ACCESSORY_DETACHED -> {
                    Log.w(TAG, "USB accessory detached")
                    ServiceState.addEvent("USB detached")
                }
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> checkAutoStart() }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            startServiceWithProjection(result.resultCode, result.data!!)
        } else {
            startService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ServiceState.init(this)
        setContentView(R.layout.activity_main)
        // Keep screen on to prevent Android from throttling USB I/O when backgrounded
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestBatteryOptimizationExemption()
        Log.w(TAG, "onCreate action=${intent?.action}")

        val filter = android.content.IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_ACCESSORY_ATTACHED)
            addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED)
        }
        registerReceiver(usbReceiver, filter, Context.RECEIVER_EXPORTED)

        setupUI()
        handleUsbIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // Re-check USB on resume in case accessory was attached while backgrounded
        if (!serviceStarted) checkAutoStart()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbReceiver)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleUsbIntent(intent)
    }

    private fun handleUsbIntent(intent: Intent?) {
        if (intent?.action == UsbManager.ACTION_USB_ACCESSORY_ATTACHED) {
            val accessory = intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
            Log.w(TAG, "Accessory attached: ${accessory?.manufacturer}/${accessory?.model}")
            ServiceState.addEvent("USB: ${accessory?.manufacturer}/${accessory?.model}")
        }

        val missing = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            checkAutoStart()
        }
    }

    private fun checkAutoStart() {
        // Auto-start if USB accessory is connected
        val accessory = UsbAoaTransport.findAccessory(this)
        if (accessory != null && !serviceStarted) {
            ServiceState.addEvent("Auto-starting (USB detected)")
            startService()
        }
    }

    private fun setupUI() {
        val tvState = findViewById<TextView>(R.id.tvConnectionState)
        val tvFrames = findViewById<TextView>(R.id.tvFrameCount)
        val tvFps = findViewById<TextView>(R.id.tvFps)
        val tvBitrate = findViewById<TextView>(R.id.tvBitrate)
        val tvLog = findViewById<TextView>(R.id.tvEventLog)
        val btnStartStop = findViewById<Button>(R.id.btnStartStop)
        val swAudio = findViewById<Switch>(R.id.swAudio)
        val swSensor = findViewById<Switch>(R.id.swSensor)
        val swFragment = findViewById<Switch>(R.id.swFragment)
        val swTestPattern = findViewById<Switch>(R.id.swTestPattern)

        // Observe state
        lifecycleScope.launch {
            ServiceState.connectionState.collectLatest { state ->
                tvState.text = state.name
                tvState.setTextColor(when (state) {
                    ServiceState.ConnectionState.DISCONNECTED -> 0xFFFF6B6B.toInt()
                    ServiceState.ConnectionState.CONNECTING -> 0xFFFFD93D.toInt()
                    ServiceState.ConnectionState.CONNECTED -> 0xFF6BCB77.toInt()
                    ServiceState.ConnectionState.STREAMING -> 0xFF4EC9B0.toInt()
                    ServiceState.ConnectionState.ERROR -> 0xFFFF0000.toInt()
                })
                if (state == ServiceState.ConnectionState.DISCONNECTED || state == ServiceState.ConnectionState.ERROR) {
                    serviceStarted = false
                    btnStartStop.text = "Start Service"
                }
            }
        }
        lifecycleScope.launch {
            ServiceState.framesSent.collectLatest { tvFrames.text = "Sent: $it" }
        }
        lifecycleScope.launch {
            ServiceState.events.collectLatest { events ->
                tvLog.text = events.joinToString("\n")
            }
        }

        // Sensor data display
        val tvSensorData = findViewById<TextView>(R.id.tvSensorData)
        lifecycleScope.launch {
            while (true) {
                kotlinx.coroutines.delay(1000)
                val sb = StringBuilder()
                val ds = ServiceState.sensorDrivingStatus.value
                val dsText = if (ds == 0) "UNRESTRICTED" else "RESTRICTED($ds)"
                sb.appendLine("Mode: ${if (ServiceState.sensorNightMode.value) "NIGHT" else "DAY"}  Drive: $dsText")
                val loc = ServiceState.sensorLocation.value
                if (loc.lat != 0.0 || loc.lon != 0.0)
                    sb.appendLine("GPS: %.5f, %.5f  ±%.0fm".format(loc.lat, loc.lon, loc.accuracy))
                val spd = ServiceState.sensorSpeed.value
                if (spd > 0) sb.appendLine("Speed: %.0f km/h".format(spd * 3.6))
                val rpm = ServiceState.sensorRpm.value
                if (rpm > 0) sb.appendLine("RPM: %.0f".format(rpm))
                val gear = ServiceState.sensorGear.value
                if (gear != 0) sb.appendLine("Gear: ${when(gear) { 100->"D"; 101->"P"; 102->"R"; 0->"N"; else->"$gear" }}")
                val fuel = ServiceState.sensorFuel.value
                if (fuel.level >= 0) sb.appendLine("Fuel: ${fuel.level}%  Range: ${fuel.range}km")
                if (ServiceState.sensorParkingBrake.value) sb.appendLine("⚠ Parking brake ON")
                val temp = ServiceState.sensorTemperature.value
                if (temp != 0.0) sb.appendLine("Temp: %.1f°C".format(temp))
                tvSensorData.text = if (sb.isEmpty()) "Waiting for sensor data..." else sb.toString().trim()
            }
        }

        // Button
        btnStartStop.setOnClickListener {
            if (!serviceStarted) {
                if (ServiceState.testPatternEnabled.value) {
                    startService()
                } else {
                    val pm = getSystemService(MediaProjectionManager::class.java)
                    screenCaptureLauncher.launch(pm.createScreenCaptureIntent())
                }
            } else {
                stopService(Intent(this, ProjectionService::class.java))
                serviceStarted = false
                ServiceState.reset()
                btnStartStop.text = "Start Service"
            }
        }

        // Toggles
        swAudio.isChecked = ServiceState.audioEnabled.value
        swSensor.isChecked = ServiceState.sensorEnabled.value
        swFragment.isChecked = ServiceState.fragmentEnabled.value
        swTestPattern.isChecked = ServiceState.testPatternEnabled.value

        swAudio.setOnCheckedChangeListener { _, checked -> ServiceState.audioEnabled.value = checked; ServiceState.saveSettings() }
        swSensor.setOnCheckedChangeListener { _, checked -> ServiceState.sensorEnabled.value = checked; ServiceState.saveSettings() }
        swFragment.setOnCheckedChangeListener { _, checked -> ServiceState.fragmentEnabled.value = checked; ServiceState.saveSettings() }
        swTestPattern.setOnCheckedChangeListener { _, checked -> ServiceState.testPatternEnabled.value = checked; ServiceState.saveSettings() }

        tvFps.text = "FPS: 15"
        tvBitrate.text = "250 Kbps"
    }

    private fun startServiceWithProjection(resultCode: Int, data: Intent) {
        if (serviceStarted) return
        serviceStarted = true
        val serviceIntent = Intent(this, ProjectionService::class.java).apply {
            putExtra(ProjectionService.EXTRA_PROJECTION_RESULT_CODE, resultCode)
            putExtra(ProjectionService.EXTRA_PROJECTION_DATA, data)
        }
        startForegroundService(serviceIntent)
        findViewById<Button>(R.id.btnStartStop).text = "Stop Service"
    }

    private fun startService() {
        if (serviceStarted) return
        serviceStarted = true
        startForegroundService(Intent(this, ProjectionService::class.java))
        findViewById<Button>(R.id.btnStartStop).text = "Stop Service"
    }

    private fun requestBatteryOptimizationExemption() {
        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            @Suppress("BatteryLife")
            val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = android.net.Uri.parse("package:$packageName")
            }
            try { startActivity(intent) } catch (_: Exception) {}
        }
    }
}

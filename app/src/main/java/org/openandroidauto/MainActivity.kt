package org.openandroidauto

import org.openandroidauto.util.FileLogger

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.openandroidauto.channel.SensorType
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
                    FileLogger.w(TAG, "USB accessory attached (broadcast)")
                    ServiceState.addEvent("USB attached (broadcast)")
                    if (!serviceStarted) checkAutoStart()
                }
                UsbManager.ACTION_USB_ACCESSORY_DETACHED -> {
                    FileLogger.w(TAG, "USB accessory detached")
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
        FileLogger.w(TAG, "onCreate action=${intent?.action}")

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
            FileLogger.w(TAG, "Accessory attached: ${accessory?.manufacturer}/${accessory?.model}")
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

                // Every field is ALWAYS listed so the state of each is visible:
                // a value, "not received" (subscribed, HU silent), "off/none"
                // where that is meaningful, or "HU rejects".
                val accepted = ServiceState.sensorsAccepted.value
                val withData = ServiceState.sensorsWithData.value
                val probed = ServiceState.sensorsAccepted.value.isNotEmpty()

                /** Status suffix for a type: not requested / rejected / silent / live. */
                fun status(type: Int): String = when {
                    !probed -> ""
                    type in withData -> ""
                    type in accepted -> "  [subscribed, no data]"
                    else -> "  [HU rejects]"
                }

                fun row(label: String, type: Int, value: String?) {
                    val v = value ?: "not received"
                    sb.appendLine("$label: $v${status(type)}")
                }

                sb.appendLine("— Mode & status —")
                sb.appendLine("Mode: ${if (ServiceState.sensorNightMode.value) "NIGHT" else "DAY"}" +
                    status(SensorType.NIGHT_MODE))
                sb.appendLine("Driving: ${drivingStatusText(ServiceState.sensorDrivingStatus.value)}" +
                    status(SensorType.DRIVING_STATUS))

                sb.appendLine("— Navigation —")
                val loc = ServiceState.sensorLocation.value
                row("GPS", SensorType.LOCATION,
                    if (loc.lat != 0.0 || loc.lon != 0.0)
                        "%.5f, %.5f  ±%.0fm  alt %.0fm  brg %.0f°".format(
                            loc.lat, loc.lon, loc.accuracy, loc.altitude, loc.bearing) else null)
                val sats = ServiceState.sensorGpsSats.value
                row("Sats", SensorType.GPS_SATELLITE,
                    if (sats.inUse > 0 || sats.inView > 0) "${sats.inUse} used / ${sats.inView} in view" else null)
                row("Compass", SensorType.COMPASS,
                    if (ServiceState.sensorCompass.value != 0.0) "%.0f°".format(ServiceState.sensorCompass.value) else null)

                sb.appendLine("— Powertrain —")
                val spd = ServiceState.sensorSpeed.value
                row("Speed", SensorType.SPEED, if (spd > 0) "%.0f km/h".format(spd * 3.6) else null)
                val rpm = ServiceState.sensorRpm.value
                row("RPM", SensorType.RPM, if (rpm > 0) "%.0f".format(rpm) else null)
                val gear = ServiceState.sensorGear.value
                row("Gear", SensorType.GEAR, if (gear != 0) gearText(gear) else null)
                val fuel = ServiceState.sensorFuel.value
                row("Fuel", SensorType.FUEL,
                    if (fuel.level >= 0) "${fuel.level}%" +
                        (if (fuel.range >= 0) "  Range: ${fuel.range} km" else "") +
                        (if (fuel.lowFuel) "  ⚠ LOW" else "") else null)
                val odo = ServiceState.sensorOdometer.value
                row("Odometer", SensorType.ODOMETER,
                    if (odo.kms > 0) "%.1f km (trip %.1f)".format(odo.kms, odo.tripKms) else null)

                sb.appendLine("— Vehicle state —")
                sb.appendLine("Parking brake: ${if (ServiceState.sensorParkingBrake.value) "ON" else "off"}" +
                    status(SensorType.PARKING_BRAKE))
                val doors = ServiceState.sensorDoors.value
                val doorsOpen = buildList {
                    if (doors.hoodOpen) add("hood")
                    if (doors.bootOpen) add("boot")
                    doors.doors.forEachIndexed { i, o -> if (o) add("door${i + 1}") }
                }
                row("Doors", SensorType.DOOR,
                    if (doors.doors.isNotEmpty()) (if (doorsOpen.isEmpty()) "all closed" else doorsOpen.joinToString(", ") + " OPEN") else null)
                val lights = ServiceState.sensorLights.value
                row("Lights", SensorType.LIGHT,
                    if (lights.headlight != 0 || lights.hazard)
                        headlightText(lights.headlight) + (if (lights.hazard) "  ⚠ HAZARD" else "") else null)

                sb.appendLine("— Climate / environment —")
                val temp = ServiceState.sensorTemperature.value
                row("Temp", SensorType.ENVIRONMENT, if (temp != 0.0) "%.1f°C".format(temp) else null)
                row("Pressure", SensorType.ENVIRONMENT,
                    if (ServiceState.sensorPressure.value != 0.0) "%.1f kPa".format(ServiceState.sensorPressure.value) else null)
                val hvacT = ServiceState.sensorHvacTarget.value
                row("HVAC", SensorType.HVAC,
                    if (hvacT != 0.0) "target %.1f°C  current %.1f°C".format(hvacT, ServiceState.sensorHvacCurrent.value) else null)

                sb.appendLine("— Motion / occupancy —")
                val st = ServiceState.sensorSteeringAngle.value
                row("Steering", SensorType.DEAD_RECKONING, if (st != 0.0) "%.1f°".format(st) else null)
                val acc = ServiceState.sensorAccel.value
                row("Accel", SensorType.ACCELEROMETER,
                    if (acc.x != 0.0 || acc.y != 0.0 || acc.z != 0.0) "%.2f, %.2f, %.2f m/s²".format(acc.x, acc.y, acc.z) else null)
                val gyro = ServiceState.sensorGyro.value
                row("Gyro", SensorType.GYROSCOPE,
                    if (gyro.x != 0.0 || gyro.y != 0.0 || gyro.z != 0.0) "%.2f, %.2f, %.2f rad/s".format(gyro.x, gyro.y, gyro.z) else null)
                sb.appendLine("Passenger: ${if (ServiceState.sensorPassenger.value) "present" else "none"}" +
                    status(SensorType.PASSENGER))

                // Summary of what the HU is actually giving us
                if (probed) {
                    sb.appendLine("—")
                    sb.appendLine("HU accepts: ${accepted.sorted().joinToString(",") { SensorNames.short(it) }}")
                    val silent = accepted - withData
                    sb.appendLine("Accepted but silent: " +
                        if (silent.isEmpty()) "none" else silent.sorted().joinToString(",") { SensorNames.short(it) })
                } else {
                    sb.appendLine("—")
                    sb.appendLine("(enable 'Probe All Sensors' to see what the HU accepts)")
                }

                tvSensorData.text = sb.toString().trim()
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
        val swEventDriven = findViewById<Switch>(R.id.swEventDriven)
        swEventDriven.isChecked = ServiceState.eventDrivenVideo.value
        val swProbeSensors = findViewById<Switch>(R.id.swProbeSensors)
        swProbeSensors.isChecked = ServiceState.probeAllSensors.value

        swAudio.setOnCheckedChangeListener { _, checked -> ServiceState.audioEnabled.value = checked; ServiceState.saveSettings() }
        swSensor.setOnCheckedChangeListener { _, checked -> ServiceState.sensorEnabled.value = checked; ServiceState.saveSettings() }
        swFragment.setOnCheckedChangeListener { _, checked -> ServiceState.fragmentEnabled.value = checked; ServiceState.saveSettings() }
        swTestPattern.setOnCheckedChangeListener { _, checked -> ServiceState.testPatternEnabled.value = checked; ServiceState.saveSettings() }
        swEventDriven.setOnCheckedChangeListener { _, checked -> ServiceState.eventDrivenVideo.value = checked; ServiceState.saveSettings() }
        swProbeSensors.setOnCheckedChangeListener { _, checked -> ServiceState.probeAllSensors.value = checked; ServiceState.saveSettings() }

        tvFps.text = "FPS: 15"
        tvBitrate.text = "250 Kbps"
    }

    private fun startServiceWithProjection(resultCode: Int, data: Intent) {
        if (serviceStarted) return
        if (isProjectionServiceRunning()) {
            FileLogger.w(TAG, "ProjectionService already running — not starting a second instance")
            serviceStarted = true
            return
        }
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
        // Guard against a race with the service's own in-place reconnect loop:
        // never launch a second instance while one is already running.
        if (isProjectionServiceRunning()) {
            FileLogger.w(TAG, "ProjectionService already running — not starting a second instance")
            serviceStarted = true
            return
        }
        serviceStarted = true
        startForegroundService(Intent(this, ProjectionService::class.java))
        findViewById<Button>(R.id.btnStartStop).text = "Stop Service"
    }

    // ── Display helpers for vehicle data ───────────────────────────────────────

    /** Decode DrivingStatusEnum bitmask (mirrors SensorChannel.drivingStatusText). */
    private fun drivingStatusText(status: Int): String {
        if (status == 0) return "UNRESTRICTED"
        if (status == 31) return "FULLY_RESTRICTED"
        val parts = buildList {
            if (status and 1 != 0) add("NO_VIDEO")
            if (status and 2 != 0) add("NO_KEYBOARD")
            if (status and 4 != 0) add("NO_VOICE")
            if (status and 8 != 0) add("NO_CONFIG")
            if (status and 16 != 0) add("LIMIT_MSG_LEN")
        }
        return parts.joinToString("|").ifEmpty { "UNKNOWN($status)" }
    }

    /** GearEnum: 0=NEUTRAL, 1-10 = gears, 100=DRIVE, 101=PARK, 102=REVERSE. */
    private fun gearText(gear: Int): String = when (gear) {
        0 -> "N"
        100 -> "D"
        101 -> "P"
        102 -> "R"
        else -> gear.toString()
    }

    /** HeadlightStatus enum: 0=NONE, 1=OFF, 2=ON, 3=HIGH. */
    private fun headlightText(v: Int): String = when (v) {
        0 -> "n/a"; 1 -> "off"; 2 -> "on"; 3 -> "high beam"; else -> "?"
    }

    /** Short label for a SensorType value, for the capability footer. */
    private object SensorNames {
        fun short(type: Int): String = when (type) {
            1 -> "GPS"; 2 -> "compass"; 3 -> "speed"; 4 -> "rpm"; 5 -> "odometer"
            6 -> "fuel"; 7 -> "parkbrake"; 8 -> "gear"; 9 -> "diag"; 10 -> "night"
            11 -> "env"; 12 -> "hvac"; 13 -> "drive"; 14 -> "steering"; 15 -> "passenger"
            16 -> "doors"; 17 -> "lights"; 19 -> "accel"; 20 -> "gyro"
            else -> "t$type"
        }
    }

    private fun isProjectionServiceRunning(): Boolean {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        @Suppress("DEPRECATION")
        return am.getRunningServices(Int.MAX_VALUE).any {
            it.service.className == ProjectionService::class.java.name
        }
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

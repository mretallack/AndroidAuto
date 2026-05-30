package org.openandroidauto

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Shared state between ProjectionService and the UI.
 * Singleton so both can access without binding.
 * Settings are persisted to SharedPreferences.
 */
object ServiceState {
    enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, STREAMING, ERROR }

    private var prefs: SharedPreferences? = null

    val connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val framesSent = MutableStateFlow(0L)
    val framesReceived = MutableStateFlow(0L)
    val lastError = MutableStateFlow("")
    val events = MutableStateFlow<List<String>>(emptyList())

    // Settings (read by service) — persisted
    val audioEnabled = MutableStateFlow(true)
    val sensorEnabled = MutableStateFlow(true)
    val fragmentEnabled = MutableStateFlow(false)
    val testPatternEnabled = MutableStateFlow(true)

    // Sensor data from head unit
    data class LocationData(val lat: Double = 0.0, val lon: Double = 0.0, val accuracy: Double = 0.0,
                            val altitude: Double = 0.0, val speed: Double = 0.0, val bearing: Double = 0.0)
    data class OdometerData(val kms: Double = 0.0, val tripKms: Double = 0.0)
    data class FuelData(val level: Int = -1, val range: Int = -1, val lowFuel: Boolean = false)
    data class LightData(val headlight: Int = 0, val indicator: Int = 0, val hazard: Boolean = false)
    data class Vec3(val x: Double = 0.0, val y: Double = 0.0, val z: Double = 0.0)
    data class GpsSatData(val inUse: Int = 0, val inView: Int = 0)

    val sensorNightMode = MutableStateFlow(false)
    val sensorDrivingStatus = MutableStateFlow(0)
    val sensorLocation = MutableStateFlow(LocationData())
    val sensorCompass = MutableStateFlow(0.0)
    val sensorSpeed = MutableStateFlow(0.0)
    val sensorRpm = MutableStateFlow(0.0)
    val sensorOdometer = MutableStateFlow(OdometerData())
    val sensorFuel = MutableStateFlow(FuelData())
    val sensorParkingBrake = MutableStateFlow(false)
    val sensorGear = MutableStateFlow(0)
    val sensorTemperature = MutableStateFlow(0.0)
    val sensorHvacTarget = MutableStateFlow(0.0)
    val sensorHvacCurrent = MutableStateFlow(0.0)
    val sensorPassenger = MutableStateFlow(false)
    val sensorLights = MutableStateFlow(LightData())
    val sensorAccel = MutableStateFlow(Vec3())
    val sensorGyro = MutableStateFlow(Vec3())
    val sensorGpsSats = MutableStateFlow(GpsSatData())

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences("aa_settings", Context.MODE_PRIVATE)
        prefs?.let { p ->
            audioEnabled.value = p.getBoolean("audio", true)
            sensorEnabled.value = p.getBoolean("sensor", true)
            fragmentEnabled.value = p.getBoolean("fragment", false)
            testPatternEnabled.value = p.getBoolean("testPattern", true)
        }
    }

    fun saveSettings() {
        prefs?.edit()
            ?.putBoolean("audio", audioEnabled.value)
            ?.putBoolean("sensor", sensorEnabled.value)
            ?.putBoolean("fragment", fragmentEnabled.value)
            ?.putBoolean("testPattern", testPatternEnabled.value)
            ?.apply()
    }

    fun addEvent(msg: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        val list = events.value.takeLast(29) + "$time $msg"
        events.value = list
    }

    fun reset() {
        connectionState.value = ConnectionState.DISCONNECTED
        framesSent.value = 0
        framesReceived.value = 0
        lastError.value = ""
        events.value = emptyList()
    }
}

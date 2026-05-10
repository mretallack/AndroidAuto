package org.openandroidauto.sensor

import android.app.UiModeManager
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import org.openandroidauto.channel.VideoChannelCallback
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Timer
import java.util.TimerTask

/**
 * Sensor channel message types.
 */
object SensorMessageType {
    const val SENSOR_START_REQUEST: Int = 0x8001
    const val SENSOR_START_RESPONSE: Int = 0x8002
    const val SENSOR_EVENT_INDICATION: Int = 0x8003
}

/**
 * Provides sensor data (GPS, night mode, driving status) to the head unit.
 */
class SensorChannel(
    private val channelId: UByte,
    private val context: Context,
    private val callback: VideoChannelCallback
) : LocationListener {

    private var locationManager: LocationManager? = null
    private var nightModeTimer: Timer? = null
    private var lastLocation: Location? = null

    fun onMessage(messageType: Int, payload: ByteArray) {
        when (messageType) {
            SensorMessageType.SENSOR_START_REQUEST -> handleStartRequest(payload)
        }
    }

    fun stop() {
        locationManager?.removeUpdates(this)
        nightModeTimer?.cancel()
    }

    private fun handleStartRequest(payload: ByteArray) {
        // SensorStartRequestMessage: sensor_type(field 1), refresh_interval(field 2)
        val sensorType = parseSensorType(payload)

        // Send SENSOR_START_RESPONSE: status = OK(0)
        sendMessage(SensorMessageType.SENSOR_START_RESPONSE, byteArrayOf(0x08, 0x00))

        when (sensorType) {
            1 -> startLocationUpdates()   // LOCATION
            10 -> startNightMode()        // NIGHT_DATA
            13 -> sendDrivingStatus()     // DRIVING_STATUS
        }
    }

    private fun startLocationUpdates() {
        locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            locationManager?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0f, this)
        } catch (_: SecurityException) {}
    }

    private fun startNightMode() {
        nightModeTimer = Timer().apply {
            scheduleAtFixedRate(object : TimerTask() {
                override fun run() { sendNightMode() }
            }, 0, 5000)
        }
    }

    private fun sendNightMode() {
        val uiMode = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        val isNight = uiMode?.nightMode == UiModeManager.MODE_NIGHT_YES

        // SensorEventIndication: field 10 (night_mode) = { field 1 (is_night) = bool }
        val nightData = byteArrayOf(0x08, if (isNight) 0x01 else 0x00)
        val event = byteArrayOf(0x52, nightData.size.toByte()) + nightData // field 10, length-delimited
        sendMessage(SensorMessageType.SENSOR_EVENT_INDICATION, event)
    }

    private fun sendDrivingStatus() {
        // DrivingStatus: UNRESTRICTED(0)
        val statusData = byteArrayOf(0x08, 0x00) // field 1: status = 0
        val event = byteArrayOf(0x6A, statusData.size.toByte()) + statusData // field 13
        sendMessage(SensorMessageType.SENSOR_EVENT_INDICATION, event)
    }

    // LocationListener
    override fun onLocationChanged(location: Location) {
        lastLocation = location
        sendLocationEvent(location)
    }

    @Deprecated("Deprecated") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    private fun sendLocationEvent(location: Location) {
        // GPSLocation protobuf: timestamp(1), latitude(2), longitude(3), accuracy(4), altitude(5), speed(6), bearing(7)
        val lat = (location.latitude * 1e7).toInt()
        val lon = (location.longitude * 1e7).toInt()
        val acc = location.accuracy.toInt()

        val gps = ByteBuffer.allocate(30).order(ByteOrder.BIG_ENDIAN)
        gps.put(0x08.toByte()); putVarint(gps, location.time)           // field 1: timestamp
        gps.put(0x10.toByte()); putSignedVarint(gps, lat)               // field 2: latitude
        gps.put(0x18.toByte()); putSignedVarint(gps, lon)               // field 3: longitude
        gps.put(0x20.toByte()); putVarint(gps, acc.toLong())            // field 4: accuracy

        val gpsBytes = gps.array().copyOf(gps.position())
        // SensorEventIndication field 1 (gps_location) repeated
        val event = byteArrayOf(0x0A, gpsBytes.size.toByte()) + gpsBytes
        sendMessage(SensorMessageType.SENSOR_EVENT_INDICATION, event)
    }

    private fun sendMessage(type: Int, protobufPayload: ByteArray) {
        val msg = ByteBuffer.allocate(2 + protobufPayload.size).order(ByteOrder.BIG_ENDIAN)
            .putShort(type.toShort())
            .put(protobufPayload)
            .array()
        callback.onSendMessage(channelId, msg)
    }

    private fun parseSensorType(data: ByteArray): Int {
        if (data.size >= 2 && (data[0].toInt() and 0xFF) == 0x08) {
            return data[1].toInt() and 0x7F
        }
        return 0
    }

    private fun putVarint(buf: ByteBuffer, value: Long) {
        var v = value
        while (v > 0x7F) { buf.put(((v.toInt() and 0x7F) or 0x80).toByte()); v = v ushr 7 }
        buf.put((v.toInt() and 0x7F).toByte())
    }

    private fun putSignedVarint(buf: ByteBuffer, value: Int) {
        // ZigZag encoding for signed varints
        val encoded = (value shl 1) xor (value shr 31)
        putVarint(buf, encoded.toLong() and 0xFFFFFFFFL)
    }
}

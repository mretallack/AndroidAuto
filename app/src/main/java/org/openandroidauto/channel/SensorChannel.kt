package org.openandroidauto.channel

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

object SensorMessageType {
    const val SENSOR_START_REQUEST: Int = 0x8001
    const val SENSOR_START_RESPONSE: Int = 0x8002
    const val SENSOR_EVENT_INDICATION: Int = 0x8003
}

object SensorType {
    const val NIGHT_DATA = 10
    const val DRIVING_STATUS = 13
}

interface SensorChannelCallback {
    fun onSendMessage(channelId: UByte, payload: ByteArray)
}

/**
 * Handles sensor channel messages from the phone.
 * The phone requests sensor data (night mode, driving status) and we respond.
 */
class SensorChannel(
    private val channelId: UByte,
    private val callback: SensorChannelCallback
) {
    private val TAG = "AASensor"
    private val activeSensors = mutableSetOf<Int>()

    fun onMessage(messageType: Int, payload: ByteArray) {
        when (messageType) {
            SensorMessageType.SENSOR_START_REQUEST -> handleSensorStartRequest(payload)
            else -> Log.w(TAG, "Unknown sensor message: 0x${messageType.toString(16)}")
        }
    }

    private fun handleSensorStartRequest(payload: ByteArray) {
        // SensorStartRequestMessage: field 1 (sensor_type) varint, field 2 (refresh_interval) varint
        var sensorType = 0
        var refreshInterval = 0L
        var i = 0
        while (i < payload.size) {
            val tag = payload[i].toInt() and 0xFF; i++
            val field = tag ushr 3
            var value = 0L; var shift = 0
            while (i < payload.size) {
                val b = payload[i].toInt() and 0xFF; i++
                value = value or (((b and 0x7F).toLong()) shl shift)
                if (b and 0x80 == 0) break
                shift += 7
            }
            when (field) { 1 -> sensorType = value.toInt(); 2 -> refreshInterval = value }
        }
        Log.w(TAG, "SENSOR_START_REQUEST type=$sensorType interval=$refreshInterval")
        activeSensors.add(sensorType)

        // Send SENSOR_START_RESPONSE: status = OK(0)
        sendMessage(SensorMessageType.SENSOR_START_RESPONSE, byteArrayOf(0x08, 0x00))

        // Immediately send initial sensor data
        when (sensorType) {
            SensorType.NIGHT_DATA -> sendNightMode(false)
            SensorType.DRIVING_STATUS -> sendDrivingStatus(0) // UNRESTRICTED
        }
    }

    /** Send night mode sensor event. isNight=true for dark mode. */
    fun sendNightMode(isNight: Boolean) {
        // SensorEventIndication { repeated NightMode night_mode = 10; }
        // NightMode { required bool is_night = 1; }
        val nightMode = byteArrayOf(0x08, if (isNight) 0x01 else 0x00)
        val out = java.io.ByteArrayOutputStream()
        // field 10, wire type 2 (length-delimited)
        out.write((10 shl 3) or 2)
        out.write(nightMode.size)
        out.write(nightMode)
        sendMessage(SensorMessageType.SENSOR_EVENT_INDICATION, out.toByteArray())
    }

    /** Send driving status. 0=UNRESTRICTED, 31=FULLY_RESTRICTED */
    fun sendDrivingStatus(status: Int) {
        // SensorEventIndication { repeated DrivingStatus driving_status = 13; }
        // DrivingStatus { required int32 status = 1; }
        val drivingStatus = byteArrayOf(0x08, status.toByte())
        val out = java.io.ByteArrayOutputStream()
        // field 13, wire type 2 (length-delimited)
        out.write((13 shl 3) or 2)
        out.write(drivingStatus.size)
        out.write(drivingStatus)
        sendMessage(SensorMessageType.SENSOR_EVENT_INDICATION, out.toByteArray())
    }

    private fun sendMessage(type: Int, protobufPayload: ByteArray) {
        val msg = ByteBuffer.allocate(2 + protobufPayload.size).order(ByteOrder.BIG_ENDIAN)
            .putShort(type.toShort())
            .put(protobufPayload)
            .array()
        callback.onSendMessage(channelId, msg)
    }
}

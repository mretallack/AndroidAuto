package org.openandroidauto.channel

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

object SensorMessageType {
    const val SENSOR_START_REQUEST: Int = 0x8001
    const val SENSOR_START_RESPONSE: Int = 0x8002
    const val SENSOR_BATCH: Int = 0x8003
}

object SensorType {
    const val LOCATION = 1
    const val NIGHT_MODE = 10
    const val DRIVING_STATUS = 13
}

interface SensorChannelCallback {
    fun onSendMessage(channelId: UByte, payload: ByteArray)
}

/**
 * Handles the sensor channel.
 * After channel opens, sends SENSOR_START_REQUEST for night mode and driving status.
 * Receives SENSOR_BATCH from head unit with sensor data.
 * Also responds to sensor requests if the head unit asks us for data.
 */
class SensorChannel(
    private val channelId: UByte,
    private val callback: SensorChannelCallback
) {
    private val TAG = "AASensor"

    var isNight: Boolean = false
        private set
    var drivingStatus: Int = 0
        private set

    fun onMessage(messageType: Int, payload: ByteArray) {
        when (messageType) {
            SensorMessageType.SENSOR_START_REQUEST -> handleSensorStartRequest(payload)
            SensorMessageType.SENSOR_START_RESPONSE -> handleSensorStartResponse(payload)
            SensorMessageType.SENSOR_BATCH -> handleSensorBatch(payload)
            else -> Log.w(TAG, "Unknown sensor message: 0x${messageType.toString(16)}")
        }
    }

    /** Send sensor start requests for night mode and driving status */
    fun requestSensors() {
        Log.w(TAG, "Requesting sensors: DRIVING_STATUS, NIGHT_MODE")
        sendSensorStartRequest(SensorType.DRIVING_STATUS)
        sendSensorStartRequest(SensorType.NIGHT_MODE)
    }

    private fun sendSensorStartRequest(sensorType: Int) {
        // SensorRequest: field 1 (type) varint, field 2 (min_update_period) varint
        val out = java.io.ByteArrayOutputStream()
        out.write(0x08) // field 1 tag
        out.write(sensorType)
        out.write(0x10) // field 2 tag
        // min_update_period = 0 (as fast as possible)
        out.write(0x00)
        sendMessage(SensorMessageType.SENSOR_START_REQUEST, out.toByteArray())
    }

    private fun handleSensorStartRequest(payload: ByteArray) {
        // Head unit asking US for sensor data - respond OK and send initial data
        var sensorType = 0
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
            if (field == 1) sensorType = value.toInt()
        }
        Log.w(TAG, "SENSOR_START_REQUEST type=$sensorType")

        // Send response: status = OK (0)
        sendMessage(SensorMessageType.SENSOR_START_RESPONSE, byteArrayOf(0x08, 0x00))

        // Send initial sensor data
        when (sensorType) {
            SensorType.NIGHT_MODE -> sendNightMode(false)
            SensorType.DRIVING_STATUS -> sendDrivingStatus(0)
        }
    }

    private fun handleSensorStartResponse(payload: ByteArray) {
        var status = -1
        if (payload.isNotEmpty()) {
            val tag = payload[0].toInt() and 0xFF
            if (tag == 0x08 && payload.size >= 2) {
                status = payload[1].toInt() and 0xFF
            }
        }
        Log.w(TAG, "SENSOR_START_RESPONSE status=$status")
    }

    private fun handleSensorBatch(payload: ByteArray) {
        // Parse SensorBatch - look for night_mode_data (field 10) and driving_status_data (field 13)
        var i = 0
        while (i < payload.size) {
            val tag = payload[i].toInt() and 0xFF; i++
            val field = tag ushr 3
            val wireType = tag and 0x07
            if (wireType == 2) { // length-delimited
                var len = 0; var shift = 0
                while (i < payload.size) {
                    val b = payload[i].toInt() and 0xFF; i++
                    len = len or ((b and 0x7F) shl shift)
                    if (b and 0x80 == 0) break
                    shift += 7
                }
                if (i + len > payload.size) break
                when (field) {
                    10 -> parseNightMode(payload.copyOfRange(i, i + len))
                    13 -> parseDrivingStatus(payload.copyOfRange(i, i + len))
                }
                i += len
            } else if (wireType == 0) {
                while (i < payload.size && payload[i].toInt() and 0x80 != 0) i++
                i++
            } else break
        }
    }

    private fun parseNightMode(data: ByteArray) {
        // NightModeData: field 1 (night_mode) bool
        if (data.size >= 2 && data[0].toInt() == 0x08) {
            isNight = data[1].toInt() != 0
            Log.w(TAG, "Night mode: $isNight")
        }
    }

    private fun parseDrivingStatus(data: ByteArray) {
        // DrivingStatusData: field 1 (status) varint
        if (data.size >= 2 && data[0].toInt() == 0x08) {
            drivingStatus = data[1].toInt() and 0xFF
            Log.w(TAG, "Driving status: $drivingStatus (0=UNRESTRICTED)")
        }
    }

    /** Send night mode sensor batch */
    fun sendNightMode(night: Boolean) {
        isNight = night
        // SensorBatch { repeated NightModeData night_mode_data = 10; }
        // NightModeData { required bool night_mode = 1; }
        val nightData = byteArrayOf(0x08, if (night) 0x01 else 0x00)
        val out = java.io.ByteArrayOutputStream()
        // field 10, wire type 2 (length-delimited)
        out.write((10 shl 3) or 2)
        out.write(nightData.size)
        out.write(nightData)
        sendMessage(SensorMessageType.SENSOR_BATCH, out.toByteArray())
        Log.w(TAG, "Sent night mode: $night")
    }

    /** Send driving status sensor batch. 0=UNRESTRICTED */
    fun sendDrivingStatus(status: Int) {
        drivingStatus = status
        // SensorBatch { repeated DrivingStatusData driving_status_data = 13; }
        // DrivingStatusData { required int32 status = 1; }
        val statusData = byteArrayOf(0x08, status.toByte())
        val out = java.io.ByteArrayOutputStream()
        // field 13, wire type 2 (length-delimited)
        out.write((13 shl 3) or 2)
        out.write(statusData.size)
        out.write(statusData)
        sendMessage(SensorMessageType.SENSOR_BATCH, out.toByteArray())
        Log.w(TAG, "Sent driving status: $status")
    }

    private fun sendMessage(type: Int, protobufPayload: ByteArray) {
        val msg = ByteBuffer.allocate(2 + protobufPayload.size).order(ByteOrder.BIG_ENDIAN)
            .putShort(type.toShort())
            .put(protobufPayload)
            .array()
        callback.onSendMessage(channelId, msg)
    }
}

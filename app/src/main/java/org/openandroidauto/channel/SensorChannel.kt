package org.openandroidauto.channel

import org.openandroidauto.util.FileLogger

import org.openandroidauto.ServiceState
import java.nio.ByteBuffer
import java.nio.ByteOrder

object SensorMessageType {
    const val SENSOR_START_REQUEST: Int = 0x8001
    const val SENSOR_START_RESPONSE: Int = 0x8002
    const val SENSOR_BATCH: Int = 0x8003
    const val SENSOR_ERROR: Int = 0x8004
}

object SensorType {
    const val LOCATION = 1
    const val COMPASS = 2
    const val SPEED = 3
    const val RPM = 4
    const val ODOMETER = 5
    const val FUEL = 6
    const val PARKING_BRAKE = 7
    const val GEAR = 8
    const val DIAGNOSTICS = 9
    const val NIGHT_MODE = 10
    const val ENVIRONMENT = 11
    const val HVAC = 12
    const val DRIVING_STATUS = 13
    const val DEAD_RECKONING = 14
    const val PASSENGER = 15
    const val DOOR = 16
    const val LIGHT = 17
    const val TIRE_PRESSURE = 18
    const val ACCELEROMETER = 19
    const val GYROSCOPE = 20
    const val GPS_SATELLITE = 21
    const val TOLL_CARD = 22

    // Names matched to third_party/aasdk/aasdk_proto/SensorTypeEnum.proto.
    fun name(type: Int): String = when (type) {
        LOCATION -> "LOCATION"; COMPASS -> "COMPASS"; SPEED -> "CAR_SPEED"
        RPM -> "RPM"; ODOMETER -> "ODOMETER"; FUEL -> "FUEL_LEVEL"
        PARKING_BRAKE -> "PARKING_BRAKE"; GEAR -> "GEAR"; DIAGNOSTICS -> "DIAGNOSTICS"
        NIGHT_MODE -> "NIGHT_DATA"; ENVIRONMENT -> "ENVIRONMENT"; HVAC -> "HVAC"
        DRIVING_STATUS -> "DRIVING_STATUS"; DEAD_RECKONING -> "DEAD_RECONING"
        PASSENGER -> "PASSENGER"; DOOR -> "DOOR"; LIGHT -> "LIGHT"
        // TIRE(18) and GPS(21) exist in the enum but have NO field in
        // SensorEventIndication, so no data can ever arrive. TOLL_CARD(22) is not
        // in the enum at all (invented here).
        TIRE_PRESSURE -> "TIRE(no payload field)"; ACCELEROMETER -> "ACCEL"
        GYROSCOPE -> "GYRO"; GPS_SATELLITE -> "GPS(no payload field)"; TOLL_CARD -> "TOLL_CARD(not in enum)"
        else -> "UNKNOWN($type)"
    }
}

interface SensorChannelCallback {
    fun onSendMessage(channelId: UByte, payload: ByteArray)
}

/**
 * Handles the sensor channel (bidirectional).
 * - Subscribes to HU sensors (SENSOR_START_REQUEST → receives SENSOR_BATCH)
 * - Responds to HU sensor requests (provides night mode, driving status)
 * - Parses all 22 sensor types from SensorBatch messages
 */
class SensorChannel(
    private val channelId: UByte,
    private val callback: SensorChannelCallback
) {
    private val TAG = "AASensor"

    fun onMessage(messageType: Int, payload: ByteArray) {
        when (messageType) {
            SensorMessageType.SENSOR_START_REQUEST -> handleSensorStartRequest(payload)
            SensorMessageType.SENSOR_START_RESPONSE -> handleSensorStartResponse(payload)
            SensorMessageType.SENSOR_BATCH -> handleSensorBatch(payload)
            SensorMessageType.SENSOR_ERROR -> handleSensorError(payload)
            else -> FileLogger.w(TAG, "Unknown sensor message: 0x${messageType.toString(16)}")
        }
    }

    /** Request all sensors the HU advertises */
    fun requestSensors(sensorTypes: List<Int>) {
        for (type in sensorTypes) {
            FileLogger.w(TAG, "Requesting sensor: ${SensorType.name(type)}")
            sendSensorStartRequest(type)
        }
    }

    /** Request the standard required sensors */
    fun requestDefaultSensors() {
        requestSensors(listOf(
            SensorType.DRIVING_STATUS,
            SensorType.NIGHT_MODE,
            SensorType.LOCATION
        ))
    }

    // ── Sensor probe (2026-10-19) ─────────────────────────────────────────────
    // Experiment: subscribe to EVERY sensor type the protocol can carry, to learn
    // what this HU actually exposes. MediaNav-class units are *assumed* not to
    // bridge CAN powertrain data, but that has never been verified here — and this
    // unit already surprised us by delivering live GPS.
    //
    // Valid types are 1..17, 19, 20 (SensorEventIndication has no field for
    // TIRE=18 or GPS=21, so subscribing to those is pointless).
    //
    // NOTE: SensorStartResponseMessage carries only `status` (OK=0/FAIL=1) — no
    // sensor type — so responses are correlated with requests BY ORDER.
    private val probeTypes = listOf(
        1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 19, 20
    )
    private var probeInFlight = mutableListOf<Int>()
    private val probeAccepted = mutableListOf<Int>()
    private val probeRejected = mutableListOf<Int>()
    private val probedTypesSeen = mutableSetOf<Int>()

    /** Subscribe to every supported sensor type, for capability discovery. */
    fun probeAllSensors() {
        synchronized(probeInFlight) {
            probeInFlight.clear(); probeAccepted.clear(); probeRejected.clear()
            probeInFlight.addAll(probeTypes)
        }
        FileLogger.w(TAG, "SENSOR PROBE: subscribing to all ${probeTypes.size} types " +
            "(${probeTypes.joinToString(",")})")
        requestSensors(probeTypes)
    }

    /** Summary of the probe, for logging on teardown. */
    fun probeSummary(): String {
        val accepted = synchronized(probeInFlight) { probeAccepted.toList() }
        val rejected = synchronized(probeInFlight) { probeRejected.toList() }
        val seen = synchronized(probeInFlight) { probedTypesSeen.toList() }
        fun names(xs: List<Int>) = xs.sorted().joinToString(",") { "${it}:${SensorType.name(it)}" }
        return "probeAccepted=[${names(accepted)}] probeRejected=[${names(rejected)}] " +
            "probeDataReceived=[${names(seen)}]" +
            if (probeInFlight.isNotEmpty()) " probeUnanswered=${probeInFlight.size}" else ""
    }

    private fun sendSensorStartRequest(sensorType: Int) {
        val out = java.io.ByteArrayOutputStream()
        out.write(0x08) // field 1 (type)
        writeVarint(out, sensorType.toLong())
        out.write(0x10) // field 2 (min_update_period)
        out.write(0x00) // 0 = as fast as possible
        sendMessage(SensorMessageType.SENSOR_START_REQUEST, out.toByteArray())
    }

    // --- Incoming from HU ---

    private fun handleSensorStartRequest(payload: ByteArray) {
        val sensorType = parseVarint(payload, 1)
        FileLogger.w(TAG, "HU requests sensor: ${SensorType.name(sensorType)}")
        sendMessage(SensorMessageType.SENSOR_START_RESPONSE, byteArrayOf(0x08, 0x00)) // OK
        when (sensorType) {
            SensorType.NIGHT_MODE -> sendNightMode(ServiceState.sensorNightMode.value)
            SensorType.DRIVING_STATUS -> sendDrivingStatus(0) // UNRESTRICTED
        }
    }

    private fun handleSensorStartResponse(payload: ByteArray) {
        val status = if (payload.size >= 2 && payload[0].toInt() == 0x08) payload[1].toInt() and 0xFF else -1
        // Correlate with the oldest outstanding request: the response carries only
        // a status, no sensor type.
        val type = synchronized(probeInFlight) { probeInFlight.removeFirstOrNull() }
        if (type != null) {
            if (status == 0) probeAccepted.add(type) else probeRejected.add(type)
            FileLogger.w(TAG, "SENSOR_START_RESPONSE ${SensorType.name(type)} " +
                "status=$status (${if (status == 0) "OK" else "FAIL"})")
        } else {
            FileLogger.w(TAG, "SENSOR_START_RESPONSE status=$status (0=OK) [no outstanding request]")
        }
    }

    private fun handleSensorError(payload: ByteArray) {
        FileLogger.w(TAG, "SENSOR_ERROR: ${payload.joinToString(" ") { "%02x".format(it) }}")
    }

    private fun handleSensorBatch(payload: ByteArray) {
        var i = 0
        while (i < payload.size) {
            val tag = payload[i].toInt() and 0xFF; i++
            val field = tag ushr 3
            val wireType = tag and 0x07
            if (wireType == 2) {
                var len = 0; var shift = 0
                while (i < payload.size) {
                    val b = payload[i].toInt() and 0xFF; i++
                    len = len or ((b and 0x7F) shl shift)
                    if (b and 0x80 == 0) break
                    shift += 7
                }
                if (i + len > payload.size) break
                val data = payload.copyOfRange(i, i + len)
                // Probe bookkeeping: which types actually deliver data (not just
                // an OK on subscribe). Field number == SensorType value.
                synchronized(probeInFlight) { probedTypesSeen.add(field) }
                parseSensorData(field, data)
                i += len
            } else if (wireType == 0) {
                while (i < payload.size && payload[i].toInt() and 0x80 != 0) i++
                i++
            } else break
        }
    }

    private fun parseSensorData(field: Int, data: ByteArray) {
        when (field) {
            1 -> parseLocation(data)
            2 -> parseCompass(data)
            3 -> parseSpeed(data)
            4 -> parseRpm(data)
            5 -> parseOdometer(data)
            6 -> parseFuel(data)
            7 -> parseParkingBrake(data)
            8 -> parseGear(data)
            9 -> parseDiagnostics(data)
            10 -> parseNightMode(data)
            11 -> parseEnvironment(data)
            12 -> parseHvac(data)
            13 -> parseDrivingStatus(data)
            14 -> parseDeadReckoning(data)
            15 -> parsePassenger(data)
            16 -> parseDoor(data)
            17 -> parseLight(data)
            18 -> parseTirePressure(data)
            19 -> parseAccelerometer(data)
            20 -> parseGyroscope(data)
            21 -> parseGpsSatellite(data)
            22 -> parseTollCard(data)
            else -> FileLogger.d(TAG, "Unknown sensor field $field")
        }
    }

    private fun parseLocation(data: ByteArray) {
        val fields = parseAllVarintFields(data)
        // LocationData: timestamp(1,uint64), latitude_e7(2,int32), longitude_e7(3,int32),
        //   accuracy_e3(4,uint32), altitude_e2(5,int32), speed_e3(6,int32), bearing_e6(7,int32)
        // int32 in protobuf: negative values are sign-extended to 64 bits in varint encoding
        val lat = (fields[2] ?: 0).toInt() / 1e7  // int32 truncation handles sign
        val lon = (fields[3] ?: 0).toInt() / 1e7
        val acc = (fields[4] ?: 0).toInt() / 1000.0
        val alt = (fields[5] ?: 0).toInt() / 100.0
        val speed = (fields[6] ?: 0).toInt() / 1000.0
        val bearing = (fields[7] ?: 0).toInt() / 1e6
        ServiceState.sensorLocation.value = ServiceState.LocationData(lat, lon, acc, alt, speed.toDouble(), bearing)
        FileLogger.d(TAG, "Location: $lat, $lon acc=${acc}m speed=${speed}m/s")
    }

    private fun parseCompass(data: ByteArray) {
        val fields = parseAllVarintFields(data)
        val bearing = (fields[1] ?: 0).toInt() / 1e6
        ServiceState.sensorCompass.value = bearing
    }

    private fun parseSpeed(data: ByteArray) {
        val fields = parseAllVarintFields(data)
        val speed = (fields[1] ?: 0) / 1000.0 // speed_e3 in m/s
        ServiceState.sensorSpeed.value = speed
        FileLogger.d(TAG, "Speed: ${speed * 3.6} km/h")
    }

    private fun parseRpm(data: ByteArray) {
        val fields = parseAllVarintFields(data)
        val rpm = (fields[1] ?: 0) / 1000.0
        ServiceState.sensorRpm.value = rpm
    }

    private fun parseOdometer(data: ByteArray) {
        val fields = parseAllVarintFields(data)
        val kms = (fields[1] ?: 0) / 10.0
        val tripKms = (fields[2] ?: 0) / 10.0
        ServiceState.sensorOdometer.value = ServiceState.OdometerData(kms, tripKms)
    }

    private fun parseFuel(data: ByteArray) {
        val fields = parseAllVarintFields(data)
        val level = (fields[1] ?: 0).toInt() // percentage 0-100 or -1
        val range = (fields[2] ?: 0).toInt() // km
        val lowFuel = (fields[3] ?: 0) != 0L
        ServiceState.sensorFuel.value = ServiceState.FuelData(level, range, lowFuel)
    }

    private fun parseParkingBrake(data: ByteArray) {
        val fields = parseAllVarintFields(data)
        ServiceState.sensorParkingBrake.value = (fields[1] ?: 0) != 0L
    }

    private fun parseGear(data: ByteArray) {
        val fields = parseAllVarintFields(data)
        val gear = (fields[1] ?: 0).toInt()
        ServiceState.sensorGear.value = gear
    }

    private fun parseDiagnostics(data: ByteArray) {
        FileLogger.d(TAG, "Diagnostics data received: ${data.size} bytes")
    }

    private fun parseNightMode(data: ByteArray) {
        val fields = parseAllVarintFields(data)
        val night = (fields[1] ?: 0) != 0L
        ServiceState.sensorNightMode.value = night
        FileLogger.w(TAG, "Night mode: $night")
    }

    private fun parseEnvironment(data: ByteArray) {
        val fields = parseAllVarintFields(data)
        val temp = (fields[1] ?: 0).toInt() / 1000.0
        val pressure = (fields[2] ?: 0).toInt() / 1000.0
        ServiceState.sensorTemperature.value = temp
    }

    private fun parseHvac(data: ByteArray) {
        val fields = parseAllVarintFields(data)
        val target = (fields[1] ?: 0).toInt() / 1000.0
        val current = (fields[2] ?: 0).toInt() / 1000.0
        ServiceState.sensorHvacTarget.value = target
        ServiceState.sensorHvacCurrent.value = current
    }

    private fun parseDrivingStatus(data: ByteArray) {
        val fields = parseAllVarintFields(data)
        val status = (fields[1] ?: 0).toInt()
        ServiceState.sensorDrivingStatus.value = status
        FileLogger.w(TAG, "Driving status: $status (0=UNRESTRICTED)")
    }

    private fun parseDeadReckoning(data: ByteArray) {
        FileLogger.d(TAG, "Dead reckoning data received")
    }

    private fun parsePassenger(data: ByteArray) {
        val fields = parseAllVarintFields(data)
        ServiceState.sensorPassenger.value = (fields[1] ?: 0) != 0L
    }

    private fun parseDoor(data: ByteArray) {
        FileLogger.d(TAG, "Door data received")
    }

    private fun parseLight(data: ByteArray) {
        val fields = parseAllVarintFields(data)
        val headlight = (fields[1] ?: 0).toInt() // 1=OFF, 2=ON, 3=HIGH
        val indicator = (fields[2] ?: 0).toInt() // 1=NONE, 2=LEFT, 3=RIGHT
        val hazard = (fields[3] ?: 0) != 0L
        ServiceState.sensorLights.value = ServiceState.LightData(headlight, indicator, hazard)
    }

    private fun parseTirePressure(data: ByteArray) {
        FileLogger.d(TAG, "Tire pressure data received")
    }

    private fun parseAccelerometer(data: ByteArray) {
        val fields = parseAllVarintFields(data)
        val x = (fields[1] ?: 0).toInt() / 1000.0
        val y = (fields[2] ?: 0).toInt() / 1000.0
        val z = (fields[3] ?: 0).toInt() / 1000.0
        ServiceState.sensorAccel.value = ServiceState.Vec3(x, y, z)
    }

    private fun parseGyroscope(data: ByteArray) {
        val fields = parseAllVarintFields(data)
        val x = (fields[1] ?: 0).toInt() / 1000.0
        val y = (fields[2] ?: 0).toInt() / 1000.0
        val z = (fields[3] ?: 0).toInt() / 1000.0
        ServiceState.sensorGyro.value = ServiceState.Vec3(x, y, z)
    }

    private fun parseGpsSatellite(data: ByteArray) {
        val fields = parseAllVarintFields(data)
        val inUse = (fields[1] ?: 0).toInt()
        val inView = (fields[2] ?: 0).toInt()
        ServiceState.sensorGpsSats.value = ServiceState.GpsSatData(inUse, inView)
    }

    private fun parseTollCard(data: ByteArray) {
        val fields = parseAllVarintFields(data)
        FileLogger.d(TAG, "Toll card present: ${(fields[1] ?: 0) != 0L}")
    }

    // --- Outgoing to HU (when we provide sensor data) ---

    fun sendNightMode(night: Boolean) {
        val nightData = byteArrayOf(0x08, if (night) 0x01 else 0x00)
        val out = java.io.ByteArrayOutputStream()
        out.write((10 shl 3) or 2); out.write(nightData.size); out.write(nightData)
        sendMessage(SensorMessageType.SENSOR_BATCH, out.toByteArray())
    }

    fun sendDrivingStatus(status: Int) {
        val statusData = byteArrayOf(0x08, status.toByte())
        val out = java.io.ByteArrayOutputStream()
        out.write((13 shl 3) or 2); out.write(statusData.size); out.write(statusData)
        sendMessage(SensorMessageType.SENSOR_BATCH, out.toByteArray())
    }

    // --- Helpers ---

    private fun parseAllVarintFields(data: ByteArray): Map<Int, Long> {
        val fields = mutableMapOf<Int, Long>()
        var i = 0
        while (i < data.size) {
            val tag = data[i].toInt() and 0xFF; i++
            val field = tag ushr 3
            val wireType = tag and 0x07
            if (wireType == 0) {
                var value = 0L; var shift = 0
                while (i < data.size) {
                    val b = data[i].toInt() and 0xFF; i++
                    value = value or (((b and 0x7F).toLong()) shl shift)
                    if (b and 0x80 == 0) break
                    shift += 7
                }
                fields[field] = value
            } else if (wireType == 2) {
                var len = 0; var shift = 0
                while (i < data.size) {
                    val b = data[i].toInt() and 0xFF; i++
                    len = len or ((b and 0x7F) shl shift)
                    if (b and 0x80 == 0) break
                    shift += 7
                }
                i += len // skip length-delimited fields
            } else break
        }
        return fields
    }

    private fun parseVarint(data: ByteArray, fieldNum: Int): Int {
        return (parseAllVarintFields(data)[fieldNum] ?: 0).toInt()
    }

    private fun writeVarint(out: java.io.ByteArrayOutputStream, value: Long) {
        var v = value
        while (v > 0x7F) { out.write(((v.toInt() and 0x7F) or 0x80)); v = v ushr 7 }
        out.write(v.toInt() and 0x7F)
    }

    private fun sendMessage(type: Int, protobufPayload: ByteArray) {
        val msg = ByteBuffer.allocate(2 + protobufPayload.size).order(ByteOrder.BIG_ENDIAN)
            .putShort(type.toShort())
            .put(protobufPayload)
            .array()
        callback.onSendMessage(channelId, msg)
    }
}

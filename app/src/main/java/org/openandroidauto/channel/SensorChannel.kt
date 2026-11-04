package org.openandroidauto.channel

import org.openandroidauto.util.FileLogger
import org.openandroidauto.util.hex

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
            else -> FileLogger.w(TAG, "UNHANDLED sensor message type=0x${messageType.toString(16)} " +
                "len=${payload.size} payload=${payload.hex(64)}")
        }
    }

    /** Request all sensors the HU advertises */
    /** Reset per-session lifecycle tracking (called once per session). */
    fun resetLifecycleTracking() {
        requestedTypes = emptySet()
        responsesMatched = 0
        responsesWithoutRequest = 0
        synchronized(probeInFlight) {
            probeInFlight.clear(); probeAccepted.clear(); probeRejected.clear(); probedTypesSeen.clear()
        }
        ServiceState.sensorsAccepted.value = emptySet()
        ServiceState.sensorsWithData.value = emptySet()
    }

    fun requestSensors(sensorTypes: List<Int>) {
        requestedTypes = requestedTypes + sensorTypes
        // Queue every request so responses can be attributed to a type. The
        // response proto carries only a status, so we correlate by order. This
        // must cover ordinary subscriptions too, not just the probe — otherwise
        // a normal run records no acceptance at all.
        synchronized(probeInFlight) { probeInFlight.addAll(sensorTypes) }
        for (type in sensorTypes) {
            FileLogger.w(TAG, "Requesting sensor: ${SensorType.name(type)}")
            sendSensorStartRequest(type)
        }
    }

    /**
     * Request the sensors worth having. Originally just the 3 mandatory ones
     * (LOCATION, NIGHT_DATA, DRIVING_STATUS). The 2026-10-23 probe showed this HU
     * also ACCEPTS COMPASS, CAR_SPEED, RPM, FUEL_LEVEL and DOOR — and really does
     * deliver speed — so request those too. Rejected types cost nothing (the HU
     * replies FAIL) but there is no point asking for them every time.
     */
    fun requestDefaultSensors() {
        requestSensors(listOf(
            // Required by the protocol
            SensorType.DRIVING_STATUS,
            SensorType.NIGHT_MODE,
            SensorType.LOCATION,
            // Verified ACCEPTED **and DELIVERING** on 2026-10-24 (clean run:
            // 27 requests / 27 responses, zero unmatched).
            SensorType.SPEED,
            SensorType.PARKING_BRAKE,
            SensorType.ACCELEROMETER,
            SensorType.GYROSCOPE
            // NOT requested: COMPASS, RPM, FUEL, ODOMETER, GEAR, DIAGNOSTICS,
            // ENVIRONMENT, HVAC, DEAD_RECKONING, PASSENGER, DOOR, LIGHT — this HU
            // replies FAIL(247) to all of them, so subscribing is pure noise.
            // NOTE: an earlier version requested COMPASS/RPM/FUEL/DOOR based on
            // mis-correlated 2026-10-23 data (responses were off by three).
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
        // requestSensors() queues the types; just clear the per-probe tallies.
        synchronized(probeInFlight) { probeAccepted.clear(); probeRejected.clear() }
        FileLogger.w(TAG, "SENSOR PROBE: subscribing to all ${probeTypes.size} types " +
            "(${probeTypes.joinToString(",")})")
        requestSensors(probeTypes)
    }

    /**
     * Per-type lifecycle summary — the answer to "what did we ask for, what did
     * the HU accept, and what actually delivered data". Tracked for ALL
     * subscriptions (not just the probe), so a normal run reports it too.
     */
    @Volatile private var requestedTypes: Set<Int> = emptySet()
    @Volatile private var responsesMatched = 0
    @Volatile private var responsesWithoutRequest = 0

    fun lifecycleSummary(): String {
        val requested = requestedTypes
        val accepted = ServiceState.sensorsAccepted.value
        val withData = ServiceState.sensorsWithData.value
        fun fmt(s: Set<Int>) = s.sorted().joinToString(",") { "$it:${SensorType.name(it)}" }
        return "requested=[${fmt(requested)}] " +
            "accepted=[${fmt(accepted)}] " +
            "deliveredData=[${fmt(withData)}] " +
            "acceptedButSilent=[${fmt(accepted - withData)}] " +
            "noData=[${fmt(requested - withData)}] " +
            correlationReport()
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

        // Correlate with the oldest outstanding request. The response proto carries
        // only a status (no sensor type), so ordering is the only signal available.
        //
        // This is fragile: if ANY response is dropped, every later attribution is
        // silently wrong. That actually happened — the 2026-10-23 run lost 3
        // responses and shifted every verdict by three, producing a completely
        // wrong capability table which then drove a wrong code change.
        //
        // Mitigation: track counts and warn loudly on any mismatch, so a shifted
        // run is visible rather than plausible-looking.
        val type = synchronized(probeInFlight) {
            if (probeInFlight.isEmpty()) {
                responsesWithoutRequest++
            } else {
                responsesMatched++
            }
            probeInFlight.removeFirstOrNull()
        }

        if (type != null) {
            if (status == 0) {
                probeAccepted.add(type)
                ServiceState.sensorsAccepted.value = ServiceState.sensorsAccepted.value + type
            } else {
                probeRejected.add(type)
            }
            FileLogger.w(TAG, "SENSOR_START_RESPONSE #$responsesMatched " +
                "${SensorType.name(type)} status=$status (${if (status == 0) "OK" else "FAIL"})")
        } else {
            FileLogger.w(TAG, "SENSOR_START_RESPONSE status=$status (0=OK) " +
                "[UNMATCHED — no outstanding request; correlation may have shifted!]")
        }
    }

    /**
     * Correlation health check, logged in the lifecycle summary. Any non-zero
     * mismatch means per-type verdicts from this run cannot be trusted.
     */
    private fun correlationReport(): String {
        val pending = synchronized(probeInFlight) { probeInFlight.size }
        val ok = responsesWithoutRequest == 0 && pending == 0
        return "correlation=${if (ok) "OK" else "SUSPECT"} " +
            "(matched=$responsesMatched unmatched=$responsesWithoutRequest pending=$pending)"
    }

    private fun handleSensorError(payload: ByteArray) {
        FileLogger.w(TAG, "SENSOR_ERROR: ${payload.joinToString(" ") { "%02x".format(it) }}")
    }

    private fun handleSensorBatch(payload: ByteArray) {
        var i = 0
        while (i < payload.size) {
            // ── Read the tag as a VARINT, not a single byte ──────────────────
            // BUG (found 2026-10-26): this used to be `payload[i]; i++`, i.e. a
            // one-byte tag read. Protobuf tags are varints: a tag whose first
            // byte has the 0x80 continuation bit set spans further bytes. Tag
            // = (field << 3) | wiretype, so any field >= 16 produces a tag
            // >= 0x80 and therefore a 2-byte tag on the wire.
            //
            // ACCEL (19) and GYRO (20) are exactly those fields. Reading one byte
            // gave a bogus "length" of 1 and we then took a single payload byte —
            // hence the long-standing "ACCEL/GYRO deliver 1 byte but declare 20-38"
            // anomaly. The bytes were on the wire all along; we were mis-framing
            // them. Fields 1..15 were unaffected, which is why LOCATION, CAR_SPEED,
            // DRIVING_STATUS, NIGHT_DATA and PARKING_BRAKE always worked.
            val (tag, tagBytes) = readVarint(payload, i)
            i += tagBytes
            val field = (tag ushr 3).toInt()
            val wireType = (tag and 0x07).toInt()
            if (wireType == 2) {
                val (lenLong, lenBytes) = readVarint(payload, i)
                i += lenBytes
                val len = lenLong.toInt()
                // Guard: a declared length beyond the buffer means a malformed or
                // truncated message. Previously this `break` silently abandoned the
                // remainder of the batch; log it so a framing problem is visible
                // rather than looking like "the sensor sent nothing".
                if (len < 0 || i + len > payload.size) {
                    FileLogger.w(TAG, "SENSOR FRAMING: field $field declares len=$len " +
                        "but only ${payload.size - i} bytes remain — aborting batch")
                    break
                }
                val data = payload.copyOfRange(i, i + len)
                // Bookkeeping: which types actually deliver data (not just an OK on
                // subscribe). Field number == SensorType value. Log the FIRST time
                // each type arrives — the single most useful "it works" signal.
                val isFirst = synchronized(probeInFlight) { probedTypesSeen.add(field) }
                if (isFirst) {
                    FileLogger.w(TAG, "FIRST DATA for ${SensorType.name(field)} (type $field)")
                }
                ServiceState.sensorsWithData.value = ServiceState.sensorsWithData.value + field
                parseSensorData(field, data)
                i += len
            } else if (wireType == 0) {
                val (_, n) = readVarint(payload, i)
                i += n
            } else {
                FileLogger.w(TAG, "SENSOR FRAMING: unsupported wiretype $wireType for field $field")
                break
            }
        }
    }

    private fun parseSensorData(field: Int, data: ByteArray) {
        // Central raw trace: bytes as received + parsed integer fields, for every
        // type. Lets the log be checked against what our scalers produce.
        rawTrace(field, data, parseAllVarintFields(data))
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
            else -> FileLogger.w(TAG, "Unknown sensor field $field (raw=${data.joinToString(" ") { "%02x".format(it) }})")
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
        // Raw ints included: negative lat/lon are sign-extended varints, so seeing
        // the raw value makes it possible to verify the .toInt() truncation.
        FileLogger.d(TAG, "Location: $lat, $lon acc=${acc}m speed=${speed}m/s " +
            "raw{${rawStr(fields, 1, 2, 3, 4, 5, 6, 7)}}")
    }

    private fun parseCompass(data: ByteArray) {
        val fields = parseAllVarintFields(data)
        val bearing = (fields[1] ?: 0).toInt() / 1e6
        ServiceState.sensorCompass.value = bearing
        FileLogger.d(TAG, "Compass: %.0f°  raw{${rawStr(fields,1,2,3)}}".format(bearing))
    }

    private fun parseSpeed(data: ByteArray) {
        val fields = parseAllVarintFields(data)
        val speed = (fields[1] ?: 0) / 1000.0 // speed_e3 in m/s
        ServiceState.sensorSpeed.value = speed
        FileLogger.d(TAG, "Speed: ${speed * 3.6} km/h  raw{${rawStr(fields,1,2,3)}}")
    }

    private fun parseRpm(data: ByteArray) {
        val fields = parseAllVarintFields(data)
        val rpm = (fields[1] ?: 0) / 1000.0
        ServiceState.sensorRpm.value = rpm
        FileLogger.d(TAG, "RPM: %.0f  raw{${rawStr(fields,1)}}".format(rpm))
    }

    private fun parseOdometer(data: ByteArray) {
        val fields = parseAllVarintFields(data)
        val kms = (fields[1] ?: 0) / 10.0
        val tripKms = (fields[2] ?: 0) / 10.0
        ServiceState.sensorOdometer.value = ServiceState.OdometerData(kms, tripKms)
        FileLogger.d(TAG, "Odometer: %.1f km (trip %.1f)  raw{${rawStr(fields,1,2)}}".format(kms, tripKms))
    }

    private fun parseFuel(data: ByteArray) {
        val fields = parseAllVarintFields(data)
        val level = (fields[1] ?: 0).toInt() // percentage 0-100 or -1
        val range = (fields[2] ?: 0).toInt() // km
        val lowFuel = (fields[3] ?: 0) != 0L
        ServiceState.sensorFuel.value = ServiceState.FuelData(level, range, lowFuel)
        FileLogger.d(TAG, "Fuel: level=$level range=$range low=$lowFuel  raw{${rawStr(fields,1,2,3)}}")
    }

    private fun parseParkingBrake(data: ByteArray) {
        val fields = parseAllVarintFields(data)
        ServiceState.sensorParkingBrake.value = (fields[1] ?: 0) != 0L
        FileLogger.d(TAG, "Parking brake: ${ServiceState.sensorParkingBrake.value}  raw{${rawStr(fields,1)}}")
    }

    private fun parseGear(data: ByteArray) {
        val fields = parseAllVarintFields(data)
        val gear = (fields[1] ?: 0).toInt()
        ServiceState.sensorGear.value = gear
        FileLogger.d(TAG, "Gear: $gear  raw{${rawStr(fields,1)}}")
    }

    private fun parseDiagnostics(data: ByteArray) {
        FileLogger.d(TAG, "Diagnostics data received: ${data.size} bytes")
    }

    private fun parseNightMode(data: ByteArray) {
        val fields = parseAllVarintFields(data)
        val night = (fields[1] ?: 0) != 0L
        ServiceState.sensorNightMode.value = night
        FileLogger.w(TAG, "Night mode: $night  raw{${rawStr(fields,1)}}")
    }

    private fun parseEnvironment(data: ByteArray) {
        // Environment.proto: temperature=1, pressure=2, rain=3
        val fields = parseAllVarintFields(data)
        ServiceState.sensorTemperature.value = (fields[1] ?: 0).toInt() / 1000.0
        ServiceState.sensorPressure.value = (fields[2] ?: 0).toInt() / 1000.0
        ServiceState.sensorRain.value = (fields[3] ?: 0).toInt()
        FileLogger.d(TAG, "Environment: temp=%.1f°C pressure=%.1fkPa rain=%d  raw{${rawStr(fields,1,2,3)}}".format(
            ServiceState.sensorTemperature.value, ServiceState.sensorPressure.value, ServiceState.sensorRain.value))
    }

    private fun parseHvac(data: ByteArray) {
        val fields = parseAllVarintFields(data)
        val target = (fields[1] ?: 0).toInt() / 1000.0
        val current = (fields[2] ?: 0).toInt() / 1000.0
        ServiceState.sensorHvacTarget.value = target
        ServiceState.sensorHvacCurrent.value = current
        FileLogger.d(TAG, "HVAC: target=%.1f current=%.1f  raw{${rawStr(fields,1,2)}}".format(target, current))
    }

    private fun parseDrivingStatus(data: ByteArray) {
        // DrivingStatus.proto status is a BITMASK (DrivingStatusEnum):
        //   UNRESTRICTED=0, NO_VIDEO=1, NO_KEYBOARD=2, NO_VOICE=4, NO_CONFIG=8,
        //   LIMIT_MSG_LEN=16, FULLY_RESTRICTED=31
        val fields = parseAllVarintFields(data)
        val status = (fields[1] ?: 0).toInt()
        ServiceState.sensorDrivingStatus.value = status
        FileLogger.w(TAG, "Driving status: $status (${drivingStatusText(status)})  raw{${rawStr(fields,1)}}")
    }

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

    private fun parseDeadReckoning(data: ByteArray) {
        // Type 14 is SteeringWheel in the proto (not "dead reckoning"):
        // steering_angle(int32)=1, wheel_speed(int32)=2
        val fields = parseAllVarintFields(data)
        ServiceState.sensorSteeringAngle.value = (fields[1] ?: 0).toInt() / 1000.0
        ServiceState.sensorSteeringWheelSpeed.value = (fields[2] ?: 0).toInt() / 1000.0
        FileLogger.d(TAG, "Steering wheel: angle=${ServiceState.sensorSteeringAngle.value} speed=${ServiceState.sensorSteeringWheelSpeed.value}  raw{${rawStr(fields,1,2)}}")
    }

    private fun parsePassenger(data: ByteArray) {
        val fields = parseAllVarintFields(data)
        ServiceState.sensorPassenger.value = (fields[1] ?: 0) != 0L
        FileLogger.d(TAG, "Passenger present: ${ServiceState.sensorPassenger.value}  raw{${rawStr(fields,1)}}")
    }

    private fun parseDoor(data: ByteArray) {
        // Door.proto: hood_open(bool)=1, boot_open(bool)=2, repeated door_open(bool)=3
        // Previously this discarded the data ("Door data received") even though the
        // HU accepts the DOOR subscription.
        val doors = mutableListOf<Boolean>()
        var hood = false; var boot = false
        var i = 0
        while (i < data.size) {
            val tag = data[i].toInt() and 0xFF; i++
            val field = tag ushr 3
            val wt = tag and 0x07
            if (wt == 0) {
                var v = 0L; var s = 0
                while (i < data.size) {
                    val b = data[i].toInt() and 0xFF; i++
                    v = v or ((b and 0x7F).toLong() shl s)
                    if (b and 0x80 == 0) break
                    s += 7
                }
                when (field) {
                    1 -> hood = v != 0L
                    2 -> boot = v != 0L
                    3 -> doors.add(v != 0L)
                }
            } else break
        }
        ServiceState.sensorDoors.value = ServiceState.DoorData(hood, boot, doors)
        FileLogger.w(TAG, "Doors: hood=$hood boot=$boot doors=${doors.map { if (it) "OPEN" else "closed" }}  raw=[${data.hex()}]")
    }

    private fun parseLight(data: ByteArray) {
        val fields = parseAllVarintFields(data)
        val headlight = (fields[1] ?: 0).toInt() // 1=OFF, 2=ON, 3=HIGH
        val indicator = (fields[2] ?: 0).toInt() // 1=NONE, 2=LEFT, 3=RIGHT
        val hazard = (fields[3] ?: 0) != 0L
        ServiceState.sensorLights.value = ServiceState.LightData(headlight, indicator, hazard)
        FileLogger.d(TAG, "Lights: headlight=$headlight indicator=$indicator hazard=$hazard  raw{${rawStr(fields,1,2,3)}}")
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
        FileLogger.d(TAG, "Accel: %.2f, %.2f, %.2f  raw{${rawStr(fields,1,2,3)}}".format(x, y, z))
    }

    private fun parseGyroscope(data: ByteArray) {
        val fields = parseAllVarintFields(data)
        val x = (fields[1] ?: 0).toInt() / 1000.0
        val y = (fields[2] ?: 0).toInt() / 1000.0
        val z = (fields[3] ?: 0).toInt() / 1000.0
        ServiceState.sensorGyro.value = ServiceState.Vec3(x, y, z)
        FileLogger.d(TAG, "Gyro: %.2f, %.2f, %.2f  raw{${rawStr(fields,1,2,3)}}".format(x, y, z))
    }

    private fun parseGpsSatellite(data: ByteArray) {
        val fields = parseAllVarintFields(data)
        val inUse = (fields[1] ?: 0).toInt()
        val inView = (fields[2] ?: 0).toInt()
        ServiceState.sensorGpsSats.value = ServiceState.GpsSatData(inUse, inView)
        FileLogger.d(TAG, "GPS satellites: $inUse used / $inView in view  raw{${rawStr(fields,1,2)}}")
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

    /**
     * Raw trace of an inbound sensor payload: the bytes as received AND the
     * integer fields we parsed from them.
     *
     * Purpose: allow the received bytes to be checked against the values we
     * decode. Several sensor types on this HU appear as all-zero (ACCEL/GYRO)
     * while others are clearly real (LOCATION/CAR_SPEED); the formatted display
     * rounds to 2 dp, which would hide a small non-zero value. This logs the
     * unscaled integers so "zero on the wire" can be distinguished from
     * "zero after our scaling".
     *
     * Rate-limited: by default only the first N occurrences per type are logged
     * at full detail (then periodically), so a 1 Hz stream cannot flood the log.
     * Set RAW_TRACE_EVERY = 1 to log every sample.
     */
    private val rawTraceCounts = java.util.concurrent.ConcurrentHashMap<Int, Int>()
    private companion object {
        const val RAW_TRACE_FIRST = 5      // always log the first N per type
        const val RAW_TRACE_EVERY = 30     // then every Nth
    }

    private fun rawTrace(type: Int, data: ByteArray, fields: Map<Int, Long>) {
        val n = (rawTraceCounts[type] ?: 0) + 1
        rawTraceCounts[type] = n
        if (n > RAW_TRACE_FIRST && n % RAW_TRACE_EVERY != 0) return
        val fieldStr = fields.entries.sortedBy { it.key }
            .joinToString(" ") { "f${it.key}=${it.value}" }
        FileLogger.w(TAG, "RAW ${SensorType.name(type)} #$n " +
            "bytes=[${data.hex()}] fields{$fieldStr}" +
            if (fields.isEmpty()) " ← NO VARINT FIELDS PARSED" else "")
    }

    // NOTE: no nested-message unwrapping is needed here — handleSensorBatch()
    // already strips the outer `[tag|wt2][len]` for each field, so parsers receive
    // the inner bytes of the sub-message. Verified against real traffic: a SPEED
    // batch `80 03 1a 02 08 00` reaches parseSpeed as `08 00` (field 1 = 0).
    // An earlier hypothesis that we were dropping nested payloads was WRONG; the
    // zeros are genuine values on the wire.


    /**
     * Read a protobuf varint starting at [start].
     * @return the value and how many bytes it occupied.
     */
    private fun readVarint(data: ByteArray, start: Int): Pair<Long, Int> {
        var value = 0L; var shift = 0; var i = start
        while (i < data.size) {
            val b = data[i].toInt() and 0xFF
            i++
            value = value or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) break
            shift += 7
            if (shift > 63) break   // malformed; bail rather than loop
        }
        return value to (i - start)
    }

    /** Render the raw (unscaled) integer fields for a log line. */
    private fun rawStr(fields: Map<Int, Long>, vararg keys: Int): String =
        keys.joinToString(",") { "f$it=${fields[it] ?: "-"}" }

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

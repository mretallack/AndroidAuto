package org.openandroidauto.sensor

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.openandroidauto.ServiceState
import org.openandroidauto.channel.SensorChannel
import org.openandroidauto.channel.SensorChannelCallback
import org.openandroidauto.channel.SensorMessageType

/**
 * Exercises the sensor decode chain at every level using **real captured
 * payloads** (see [CapturedSensorVectors]) — from message id, through the outer
 * field tag and nested message body, to the scaled engineering value.
 *
 * Motivation: ACCEL/GYRO were observed delivering 1 byte while the enclosing
 * message declared 20-38 bytes. That is either a head-unit stub or a framing bug
 * on our side, and the two are indistinguishable without testing the chain
 * against known-good bytes.
 */
class SensorFramingTest {

    private lateinit var channel: SensorChannel
    private lateinit var cb: TestCallback

    class TestCallback : SensorChannelCallback {
        val messages = mutableListOf<Pair<UByte, ByteArray>>()
        override fun onSendMessage(channelId: UByte, payload: ByteArray) {
            messages.add(channelId to payload)
        }
    }

    @Before
    fun setUp() {
        // ServiceState is a process-wide singleton; clear per-test to avoid leakage.
        channel = SensorChannel(6u, TestCallback()).also { it.resetLifecycleTracking() }
        ServiceState.sensorsAccepted.value = emptySet()
        ServiceState.sensorsWithData.value = emptySet()
        ServiceState.sensorSpeed.value = 0.0
        ServiceState.sensorNightMode.value = false
        ServiceState.sensorParkingBrake.value = false
        ServiceState.sensorDrivingStatus.value = 0
        ServiceState.sensorAccel.value = ServiceState.Vec3()
        ServiceState.sensorGyro.value = ServiceState.Vec3()
        ServiceState.sensorLocation.value = ServiceState.LocationData()
    }

    /** Feed a captured 0x8003 payload exactly as routeMessage() would. */
    private fun feed(msgIdIncluded: ByteArray) {
        // routeMessage strips the 2-byte message id before the channel sees it.
        val body = msgIdIncluded.copyOfRange(2, msgIdIncluded.size)
        channel.onMessage(SensorMessageType.SENSOR_BATCH, body)
    }

    // ── Level 1: message id is stripped and routed ─────────────────────────────

    @Test
    fun `0x8003 message routes to the sensor batch handler`() {
        feed(CapturedSensorVectors.PARKING_BRAKE_ON)
        assertTrue("parking brake should be set from a real captured payload",
            ServiceState.sensorParkingBrake.value)
    }

    // ── Level 2: outer field tag identifies the type ───────────────────────────

    @Test
    fun `each captured payload sets only its own sensor type`() {
        feed(CapturedSensorVectors.NIGHT_MODE_ON)
        assertTrue("night mode", ServiceState.sensorNightMode.value)
        assertEquals("speed untouched", 0.0, ServiceState.sensorSpeed.value, 0.0)
        assertEquals("accel untouched", 0.0, ServiceState.sensorAccel.value.x, 0.0)
    }

    // ── Level 3: nested body decodes to the right number ──────────────────────

    @Test
    fun `car speed decodes from captured varints`() {
        // 80 03 1a 03 08 80 25  → f1 = 0x1280 = 4736 → /1000 = 4.736 m/s
        feed(CapturedSensorVectors.CAR_SPEED_4736)
        assertEquals("speed should be 4.736 m/s", 4.736, ServiceState.sensorSpeed.value, 0.001)

        feed(CapturedSensorVectors.CAR_SPEED_9216)
        assertEquals("speed should be 9.216 m/s", 9.216, ServiceState.sensorSpeed.value, 0.001)

        feed(CapturedSensorVectors.CAR_SPEED_ZERO)
        assertEquals("speed should be 0", 0.0, ServiceState.sensorSpeed.value, 0.0)
    }

    @Test
    fun `multi-byte varints are decoded correctly`() {
        // The regression that matters: 08 80 25 is a 2-byte varint.
        // A naive reader taking only the first byte gets 0x00, not 0x1280.
        feed(CapturedSensorVectors.CAR_SPEED_4736)
        assertNotEquals("must not read only the first varint byte",
            0.0, ServiceState.sensorSpeed.value, 0.0)
    }

    @Test
    fun `driving status decodes the bitmask`() {
        feed(CapturedSensorVectors.DRIVING_STATUS_UNRESTRICTED)
        assertEquals(0, ServiceState.sensorDrivingStatus.value)

        feed(CapturedSensorVectors.DRIVING_STATUS_NO_VIDEO_NO_CONFIG)
        assertEquals(9, ServiceState.sensorDrivingStatus.value)
    }

    @Test
    fun `parking brake toggles from captured payloads`() {
        feed(CapturedSensorVectors.PARKING_BRAKE_ON)
        assertTrue(ServiceState.sensorParkingBrake.value)
        feed(CapturedSensorVectors.PARKING_BRAKE_OFF)
        assertFalse(ServiceState.sensorParkingBrake.value)
    }

    // ── Level 4: full multi-field message (LOCATION) ──────────────────────────

    @Test
    fun `location decodes all seven fields including a negative longitude`() {
        feed(CapturedSensorVectors.LOCATION_FULL)
        val loc = ServiceState.sensorLocation.value
        // lat 523387498 / 1e7 ; lon -34823916 / 1e7 (sign-extended varint)
        assertEquals("latitude", 52.3387498, loc.lat, 1e-6)
        assertEquals("longitude (negative, sign-extended)", -3.4823916, loc.lon, 1e-6)
        assertEquals("accuracy 1054/1000", 1.054, loc.accuracy, 1e-3)
    }

    // ── Level 5: the anomalous ACCEL/GYRO payloads ────────────────────────────

    @Test
    fun `malformed length does not throw or yield a bogus sample`() {
        // Declares length 0x18 (24) with no bytes present. Before the fix this
        // silently produced a 1-byte "sample"; now it aborts cleanly.
        feed(CapturedSensorVectors.MALFORMED_LEN_TOO_LONG)
        val a = ServiceState.sensorAccel.value
        assertEquals("no bogus axes", 0.0, a.x + a.y + a.z, 0.0)
    }

    @Test
    fun `empty accel field is tolerated`() {
        feed(CapturedSensorVectors.EMPTY_ACCEL_FIELD)
        assertEquals(0.0, ServiceState.sensorAccel.value.x, 0.0)
    }

    // ── Level 6: unknown fields must be logged with their bytes ───────────────

    // ── Regression: multi-byte (>=0x80) tags ──────────────────────────────────
    // The bug: ACCEL(19) and GYRO(20) have tags 0x9a / 0xa2 whose high bit is
    // set, so they are 2-byte varints. Reading the tag as one byte made 0x01 look
    // like a 1-byte length, so we took a single payload byte and threw the rest
    // away — the "declares 20-38 bytes, delivers 1" anomaly.

    @Test
    fun `accel with a 2-byte tag decodes all three axes`() {
        feed(CapturedSensorVectors.ACCEL_REAL_1)
        val a = ServiceState.sensorAccel.value
        // Body `08 ee 05 10 f2 02 18 f0 4c`:
        //   x = 0xee 0x05 -> 750  -> 0.750
        //   y = 0xf2 0x02 -> 370  -> 0.370
        //   z = 0xf0 0x4c -> 9840 -> 9.840
        assertEquals("x", 0.750, a.x, 1e-6)
        assertEquals("y", 0.370, a.y, 1e-6)
        assertEquals("z", 9.840, a.z, 1e-6)
    }

    @Test
    fun `all captured accel messages decode to non-zero axes`() {
        for (vec in listOf(
            CapturedSensorVectors.ACCEL_REAL_1,
            CapturedSensorVectors.ACCEL_REAL_2,
            CapturedSensorVectors.ACCEL_REAL_3
        )) {
            feed(vec)
            val a = ServiceState.sensorAccel.value
            assertTrue("at least one axis must be non-zero for ${
                vec.joinToString(" ") { "%02x".format(it) }}",
                a.x != 0.0 || a.y != 0.0 || a.z != 0.0)
        }
    }

    @Test
    fun `gyro with a 2-byte tag decodes all three axes`() {
        feed(CapturedSensorVectors.GYRO_REAL_ZERO)
        val zero = ServiceState.sensorGyro.value
        assertEquals("all-zero sample x", 0.0, zero.x, 1e-6)
        assertEquals("all-zero sample y", 0.0, zero.y, 1e-6)
        assertEquals("all-zero sample z", 0.0, zero.z, 1e-6)

        feed(CapturedSensorVectors.GYRO_REAL_2)
        // 08 02 -> x=2/1000=0.002 ; 10 01 -> y=0.001 ; 18 00 -> z=0
        val g = ServiceState.sensorGyro.value
        assertEquals("x", 0.002, g.x, 1e-6)
        assertEquals("y", 0.001, g.y, 1e-6)
        assertEquals("z", 0.0, g.z, 1e-6)
    }

    @Test
    fun `tag varint is read past the continuation bit`() {
        // Explicit guard on the mechanism: 0x9a has bit 7 set, so the tag needs a
        // second byte. If the reader regresses to one byte, the length becomes 1
        // and all three axes collapse to zero.
        feed(CapturedSensorVectors.ACCEL_REAL_1)
        val a = ServiceState.sensorAccel.value
        assertNotEquals("must not read a 1-byte tag (x would be 0)", 0.0, a.x, 0.0)
    }

    @Test
    fun `a well-formed 3-axis accel decodes to real values`() {
        // Proves the parser CAN handle a proper Accel body — so if the head unit
        // sent one, we would decode it. Establishes that a 1-byte payload is the
        // anomaly, not a parser limitation.
        feed(CapturedSensorVectors.ACCEL_WELLFORMED)
        val a = ServiceState.sensorAccel.value
        assertEquals("x = 100/1000", 0.1, a.x, 1e-6)
        assertEquals("y = 200/1000", 0.2, a.y, 1e-6)
        assertEquals("z = 44/1000", 0.044, a.z, 1e-6)
    }

    @Test
    fun `unknown sensor field is tolerated and does not throw`() {
        // field 18 (TIRE) has no payload field in SensorEventIndication, so the
        // HU should never send it — but if it does we must not crash.
        val unknown = byteArrayOf(0x92.toByte(), 0x01, 0x02, 0x08, 0x00)
        channel.onMessage(SensorMessageType.SENSOR_BATCH, unknown)
        // Reaching here without an exception is the assertion.
        assertTrue(true)
    }

    @Test
    fun `truncated message does not throw`() {
        // Declares a length beyond the buffer — the walker must bail, not crash.
        val truncated = byteArrayOf(0x1a, 0x7f, 0x08)
        channel.onMessage(SensorMessageType.SENSOR_BATCH, truncated)
        assertTrue(true)
    }
}

package org.openandroidauto.sensor

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.openandroidauto.channel.SensorChannel
import org.openandroidauto.channel.SensorChannelCallback
import org.openandroidauto.channel.SensorMessageType
import org.openandroidauto.channel.SensorType
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SensorChannelTest {

    private lateinit var channel: SensorChannel
    private lateinit var cb: TestCallback

    class TestCallback : SensorChannelCallback {
        val messages = mutableListOf<Pair<UByte, ByteArray>>()
        override fun onSendMessage(channelId: UByte, payload: ByteArray) {
            messages.add(channelId to payload)
        }
        fun messageTypes(): List<Int> = messages.map { msg ->
            ((msg.second[0].toInt() and 0xFF) shl 8) or (msg.second[1].toInt() and 0xFF)
        }
    }

    @Before
    fun setUp() {
        cb = TestCallback()
        channel = SensorChannel(6u, cb)
    }

    // --- requestSensors ---

    @Test
    fun `requestSensors sends SENSOR_START_REQUEST for driving status and night mode`() {
        channel.requestSensors()

        assertEquals(2, cb.messages.size)
        val types = cb.messageTypes()
        assertTrue(types.all { it == SensorMessageType.SENSOR_START_REQUEST })
    }

    @Test
    fun `requestSensors includes correct sensor types in payload`() {
        channel.requestSensors()

        // First request: DRIVING_STATUS (13)
        val payload1 = cb.messages[0].second.copyOfRange(2, cb.messages[0].second.size)
        assertEquals(0x08.toByte(), payload1[0]) // field 1 tag
        assertEquals(SensorType.DRIVING_STATUS.toByte(), payload1[1])

        // Second request: NIGHT_MODE (10)
        val payload2 = cb.messages[1].second.copyOfRange(2, cb.messages[1].second.size)
        assertEquals(0x08.toByte(), payload2[0])
        assertEquals(SensorType.NIGHT_MODE.toByte(), payload2[1])
    }

    // --- Handling sensor start request from head unit ---

    @Test
    fun `SENSOR_START_REQUEST for NIGHT_MODE sends response and night data`() {
        // SensorRequest: type=NIGHT_MODE(10), min_update_period=0
        val request = byteArrayOf(0x08, 0x0A, 0x10, 0x00)
        channel.onMessage(SensorMessageType.SENSOR_START_REQUEST, request)

        // Should send SENSOR_START_RESPONSE + SENSOR_BATCH
        assertTrue(cb.messages.size >= 2)
        val types = cb.messageTypes()
        assertEquals(SensorMessageType.SENSOR_START_RESPONSE, types[0])
        assertEquals(SensorMessageType.SENSOR_BATCH, types[1])
    }

    @Test
    fun `SENSOR_START_REQUEST for DRIVING_STATUS sends response and status data`() {
        val request = byteArrayOf(0x08, 0x0D, 0x10, 0x00) // type=13
        channel.onMessage(SensorMessageType.SENSOR_START_REQUEST, request)

        assertTrue(cb.messages.size >= 2)
        val types = cb.messageTypes()
        assertEquals(SensorMessageType.SENSOR_START_RESPONSE, types[0])
        assertEquals(SensorMessageType.SENSOR_BATCH, types[1])
    }

    // --- Handling sensor batch from head unit ---

    @Test
    fun `SENSOR_BATCH with night mode data updates isNight`() {
        // SensorBatch { night_mode_data (field 10) { night_mode = true } }
        val nightData = byteArrayOf(0x08, 0x01) // night_mode = true
        val batch = byteArrayOf((10 shl 3 or 2).toByte(), nightData.size.toByte()) + nightData

        channel.onMessage(SensorMessageType.SENSOR_BATCH, batch)

        assertTrue(channel.isNight)
    }

    @Test
    fun `SENSOR_BATCH with driving status updates drivingStatus`() {
        // SensorBatch { driving_status_data (field 13) { status = 0 } }
        val statusData = byteArrayOf(0x08, 0x00) // UNRESTRICTED
        val batch = byteArrayOf((13 shl 3 or 2).toByte(), statusData.size.toByte()) + statusData

        channel.onMessage(SensorMessageType.SENSOR_BATCH, batch)

        assertEquals(0, channel.drivingStatus)
    }

    // --- sendNightMode / sendDrivingStatus ---

    @Test
    fun `sendNightMode sends SENSOR_BATCH with correct format`() {
        channel.sendNightMode(true)

        assertEquals(1, cb.messages.size)
        assertEquals(SensorMessageType.SENSOR_BATCH, cb.messageTypes()[0])
        // Payload should contain field 10 (night_mode_data)
        val payload = cb.messages[0].second.copyOfRange(2, cb.messages[0].second.size)
        assertEquals((10 shl 3 or 2).toByte(), payload[0]) // field 10, wire type 2
        assertTrue(channel.isNight)
    }

    @Test
    fun `sendDrivingStatus sends SENSOR_BATCH with correct format`() {
        channel.sendDrivingStatus(0)

        assertEquals(1, cb.messages.size)
        assertEquals(SensorMessageType.SENSOR_BATCH, cb.messageTypes()[0])
        val payload = cb.messages[0].second.copyOfRange(2, cb.messages[0].second.size)
        assertEquals((13 shl 3 or 2).toByte(), payload[0]) // field 13, wire type 2
    }

    // --- Message type constants ---

    @Test
    fun `sensor message types match aasdk proto definitions`() {
        assertEquals(0x8001, SensorMessageType.SENSOR_START_REQUEST)
        assertEquals(0x8002, SensorMessageType.SENSOR_START_RESPONSE)
        assertEquals(0x8003, SensorMessageType.SENSOR_BATCH)
    }

    @Test
    fun `channel ID is preserved in callbacks`() {
        channel.requestSensors()
        assertEquals(6.toUByte(), cb.messages[0].first)
    }
}

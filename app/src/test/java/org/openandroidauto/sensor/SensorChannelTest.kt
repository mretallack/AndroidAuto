package org.openandroidauto.sensor

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.openandroidauto.ServiceState
import org.openandroidauto.channel.SensorChannel
import org.openandroidauto.channel.SensorChannelCallback
import org.openandroidauto.channel.SensorMessageType
import org.openandroidauto.channel.SensorType

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

    @Test
    fun `lifecycle summary reports requested, accepted and delivered separately`() {
        // The point of this summary: an "OK" on subscribe does NOT mean data
        // arrives. It must distinguish the two.
        channel.resetLifecycleTracking() // ServiceState is a process-wide singleton
        channel.requestSensors(listOf(SensorType.SPEED, SensorType.RPM))
        val summary = channel.lifecycleSummary()
        assertTrue("should list the 2 requested types: $summary",
            summary.contains("requested=[3:CAR_SPEED,4:RPM]"))
        // Nothing has responded or delivered yet.
        assertTrue("accepted should be empty: $summary", summary.contains("accepted=[]"))
        assertTrue("delivered should be empty: $summary", summary.contains("deliveredData=[]"))
    }

    @Test
    fun `requestDefaultSensors sends SENSOR_START_REQUEST for all supported sensors`() {
        // Expanded from 3 to 8 after the 2026-10-23 probe showed this HU also
        // accepts COMPASS, CAR_SPEED, RPM, FUEL_LEVEL and DOOR.
        channel.requestDefaultSensors()
        assertEquals(8, cb.messages.size)
        assertTrue(cb.messageTypes().all { it == SensorMessageType.SENSOR_START_REQUEST })
    }

    @Test
    fun `requestSensors sends requests for specified types`() {
        channel.requestSensors(listOf(SensorType.DRIVING_STATUS, SensorType.NIGHT_MODE))
        assertEquals(2, cb.messages.size)
        val payload1 = cb.messages[0].second.copyOfRange(2, cb.messages[0].second.size)
        assertEquals(0x08.toByte(), payload1[0])
        assertEquals(SensorType.DRIVING_STATUS.toByte(), payload1[1])
    }

    @Test
    fun `SENSOR_START_REQUEST from HU sends response and data`() {
        val request = byteArrayOf(0x08, 0x0A, 0x10, 0x00) // NIGHT_MODE
        channel.onMessage(SensorMessageType.SENSOR_START_REQUEST, request)
        assertTrue(cb.messages.size >= 2)
        assertEquals(SensorMessageType.SENSOR_START_RESPONSE, cb.messageTypes()[0])
        assertEquals(SensorMessageType.SENSOR_BATCH, cb.messageTypes()[1])
    }

    @Test
    fun `SENSOR_BATCH with night mode updates ServiceState`() {
        val nightData = byteArrayOf(0x08, 0x01)
        val batch = byteArrayOf((10 shl 3 or 2).toByte(), nightData.size.toByte()) + nightData
        channel.onMessage(SensorMessageType.SENSOR_BATCH, batch)
        assertTrue(ServiceState.sensorNightMode.value)
    }

    @Test
    fun `SENSOR_BATCH with driving status updates ServiceState`() {
        val statusData = byteArrayOf(0x08, 0x00)
        val batch = byteArrayOf((13 shl 3 or 2).toByte(), statusData.size.toByte()) + statusData
        channel.onMessage(SensorMessageType.SENSOR_BATCH, batch)
        assertEquals(0, ServiceState.sensorDrivingStatus.value)
    }

    @Test
    fun `sendNightMode sends SENSOR_BATCH`() {
        channel.sendNightMode(true)
        assertEquals(1, cb.messages.size)
        assertEquals(SensorMessageType.SENSOR_BATCH, cb.messageTypes()[0])
    }

    @Test
    fun `sendDrivingStatus sends SENSOR_BATCH`() {
        channel.sendDrivingStatus(0)
        assertEquals(1, cb.messages.size)
        assertEquals(SensorMessageType.SENSOR_BATCH, cb.messageTypes()[0])
    }

    @Test
    fun `sensor message types match proto definitions`() {
        assertEquals(0x8001, SensorMessageType.SENSOR_START_REQUEST)
        assertEquals(0x8002, SensorMessageType.SENSOR_START_RESPONSE)
        assertEquals(0x8003, SensorMessageType.SENSOR_BATCH)
    }

    @Test
    fun `channel ID is preserved in callbacks`() {
        channel.requestDefaultSensors()
        assertEquals(6.toUByte(), cb.messages[0].first)
    }
}

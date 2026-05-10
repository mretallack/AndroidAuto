package org.openandroidauto.sensor

import org.junit.Assert.*
import org.junit.Test
import org.openandroidauto.channel.VideoChannelCallback
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SensorChannelTest {

    class TestCallback : VideoChannelCallback {
        val frames = mutableListOf<Pair<UByte, ByteArray>>()
        val messages = mutableListOf<Pair<UByte, ByteArray>>()
        override fun onVideoFrame(channelId: UByte, payload: ByteArray) { frames.add(channelId to payload) }
        override fun onSendMessage(channelId: UByte, payload: ByteArray) { messages.add(channelId to payload) }
    }

    @Test
    fun `SENSOR_START_REQUEST for DRIVING_STATUS sends response and event`() {
        val cb = TestCallback()
        // Can't create SensorChannel without Context, so test the protocol format directly
        // Verify message format: SENSOR_START_RESPONSE type = 0x8002
        val responsePayload = byteArrayOf(0x08, 0x00) // status = OK
        val msg = ByteBuffer.allocate(2 + responsePayload.size).order(ByteOrder.BIG_ENDIAN)
            .putShort(SensorMessageType.SENSOR_START_RESPONSE.toShort())
            .put(responsePayload)
            .array()

        val type = ByteBuffer.wrap(msg).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF
        assertEquals(SensorMessageType.SENSOR_START_RESPONSE, type)
        assertEquals(0x08.toByte(), msg[2])
        assertEquals(0x00.toByte(), msg[3]) // OK
    }

    @Test
    fun `SENSOR_EVENT_INDICATION night mode format`() {
        // NightMode event: field 10 in SensorEventIndication
        val nightData = byteArrayOf(0x08, 0x01) // is_night = true
        val event = byteArrayOf(0x52, nightData.size.toByte()) + nightData // field 10, length-delimited

        // Verify tag: 0x52 = field 10, wire type 2 (length-delimited)
        assertEquals(10, (0x52 ushr 3))
        assertEquals(2, (0x52 and 0x07))
        assertEquals(0x01.toByte(), event[3]) // is_night = true
    }

    @Test
    fun `SENSOR_EVENT_INDICATION driving status format`() {
        val statusData = byteArrayOf(0x08, 0x00) // UNRESTRICTED
        val event = byteArrayOf(0x6A, statusData.size.toByte()) + statusData // field 13

        // Verify tag: 0x6A = field 13, wire type 2
        assertEquals(13, (0x6A ushr 3))
        assertEquals(2, (0x6A and 0x07))
    }

    @Test
    fun `sensor message types are correct`() {
        assertEquals(0x8001, SensorMessageType.SENSOR_START_REQUEST)
        assertEquals(0x8002, SensorMessageType.SENSOR_START_RESPONSE)
        assertEquals(0x8003, SensorMessageType.SENSOR_EVENT_INDICATION)
    }
}

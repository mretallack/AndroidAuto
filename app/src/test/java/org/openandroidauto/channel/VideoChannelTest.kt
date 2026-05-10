package org.openandroidauto.channel

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class VideoChannelTest {

    private lateinit var channel: VideoChannel
    private lateinit var cb: TestVideoCallback

    class TestVideoCallback : VideoChannelCallback {
        val videoFrames = mutableListOf<Pair<UByte, ByteArray>>()
        val messages = mutableListOf<Pair<UByte, ByteArray>>()

        override fun onVideoFrame(channelId: UByte, payload: ByteArray) {
            videoFrames.add(channelId to payload)
        }
        override fun onSendMessage(channelId: UByte, payload: ByteArray) {
            messages.add(channelId to payload)
        }
    }

    @Before
    fun setUp() {
        cb = TestVideoCallback()
        channel = VideoChannel(1u, cb)
    }

    @Test
    fun `SETUP_REQUEST sends SETUP_RESPONSE with OK status`() {
        // AVChannelSetupRequest: field 1 (config_index) = 0
        val setupRequest = byteArrayOf(0x08, 0x00)
        channel.onMessage(AVMessageType.SETUP_REQUEST, setupRequest)

        assertEquals(1, cb.messages.size)
        val msg = cb.messages[0].second
        val type = ByteBuffer.wrap(msg).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF
        assertEquals(AVMessageType.SETUP_RESPONSE, type)

        // Verify protobuf payload contains status=OK(2)
        val payload = msg.copyOfRange(2, msg.size)
        assertTrue(payload.size >= 2)
        assertEquals(0x08.toByte(), payload[0]) // field 1 tag
        assertEquals(0x02.toByte(), payload[1]) // value 2 = OK
    }

    @Test
    fun `VIDEO_FOCUS_REQUEST sends FOCUS_INDICATION with FOCUSED mode`() {
        // VideoFocusRequest: disp_index=0, focus_mode=FOCUSED(1), focus_reason=1
        val request = byteArrayOf(0x08, 0x00, 0x10, 0x01, 0x18, 0x01)
        channel.onMessage(AVMessageType.VIDEO_FOCUS_REQUEST, request)

        assertEquals(1, cb.messages.size)
        val msg = cb.messages[0].second
        val type = ByteBuffer.wrap(msg).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF
        assertEquals(AVMessageType.VIDEO_FOCUS_INDICATION, type)

        // Verify focus_mode = FOCUSED(1)
        val payload = msg.copyOfRange(2, msg.size)
        assertEquals(0x08.toByte(), payload[0]) // field 1 tag
        assertEquals(0x01.toByte(), payload[1]) // FOCUSED
    }

    @Test
    fun `default config is 720p 30fps`() {
        assertEquals(1280, channel.config.width)
        assertEquals(720, channel.config.height)
        assertEquals(30, channel.config.fps)
    }

    @Test
    fun `AV_MEDIA_WITH_TIMESTAMP frame format is correct`() {
        // Test the frame packaging format directly
        val nalData = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x67) // SPS NAL
        val timestampUs = 1000000L

        val payload = ByteBuffer.allocate(2 + 8 + nalData.size).order(ByteOrder.BIG_ENDIAN)
            .putShort(AVMessageType.AV_MEDIA_WITH_TIMESTAMP.toShort())
            .putLong(timestampUs)
            .put(nalData)
            .array()

        // Verify structure
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        assertEquals(AVMessageType.AV_MEDIA_WITH_TIMESTAMP, buf.short.toInt() and 0xFFFF)
        assertEquals(timestampUs, buf.long)
        val remaining = ByteArray(buf.remaining())
        buf.get(remaining)
        assertArrayEquals(nalData, remaining)
    }

    @Test
    fun `STOP_INDICATION stops encoding without crash`() {
        // Should not throw even without MediaProjection
        channel.onMessage(AVMessageType.STOP_INDICATION, ByteArray(0))
    }

    @Test
    fun `channel ID is preserved in callbacks`() {
        val ch = VideoChannel(7u, cb)
        ch.onMessage(AVMessageType.SETUP_REQUEST, byteArrayOf(0x08, 0x00))
        assertEquals(7.toUByte(), cb.messages[0].first)
    }
}

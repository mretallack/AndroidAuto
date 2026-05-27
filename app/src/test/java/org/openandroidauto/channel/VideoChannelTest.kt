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

        fun lastMessageType(): Int {
            val msg = messages.last().second
            return ((msg[0].toInt() and 0xFF) shl 8) or (msg[1].toInt() and 0xFF)
        }
    }

    @Before
    fun setUp() {
        cb = TestVideoCallback()
        channel = VideoChannel(1u, cb)
    }

    // --- State machine tests ---

    @Test
    fun `initial state is IDLE`() {
        assertEquals(VideoState.IDLE, channel.state)
    }

    @Test
    fun `sendSetup moves to SETUP_SENT and sends SETUP message`() {
        channel.sendSetup()

        assertEquals(VideoState.SETUP_SENT, channel.state)
        assertEquals(1, cb.messages.size)
        assertEquals(AVMessageType.SETUP_REQUEST, cb.lastMessageType())
        // Verify H264_BP codec type in payload
        val payload = cb.messages[0].second.copyOfRange(2, cb.messages[0].second.size)
        assertEquals(0x08.toByte(), payload[0])
        assertEquals(0x03.toByte(), payload[1]) // MEDIA_CODEC_VIDEO_H264_BP
    }

    @Test
    fun `CONFIG response moves to CONFIGURED and parses max_unacked`() {
        channel.sendSetup()

        // CONFIG: status=STATUS_READY(2), max_unacked=100, config_index=0
        val config = byteArrayOf(0x08, 0x02, 0x10, 0x64, 0x18, 0x00)
        channel.onMessage(AVMessageType.SETUP_RESPONSE, config)

        assertEquals(VideoState.CONFIGURED, channel.state)
        assertEquals(100, channel.maxUnacked)
    }

    @Test
    fun `VIDEO_FOCUS_NOTIFICATION with PROJECTED moves to FOCUSED and sends START`() {
        channel.sendSetup()
        // Receive CONFIG
        channel.onMessage(AVMessageType.SETUP_RESPONSE, byteArrayOf(0x08, 0x02, 0x10, 0x05, 0x18, 0x00))
        assertEquals(VideoState.CONFIGURED, channel.state)

        cb.messages.clear()

        // Receive VIDEO_FOCUS_NOTIFICATION: focus=PROJECTED(1), unsolicited=true
        channel.onMessage(AVMessageType.VIDEO_FOCUS_INDICATION, byteArrayOf(0x08, 0x01, 0x10, 0x01))

        assertEquals(VideoState.STARTED, channel.state)
        // Should have sent START_INDICATION
        assertTrue(cb.messages.any {
            val t = ((it.second[0].toInt() and 0xFF) shl 8) or (it.second[1].toInt() and 0xFF)
            t == AVMessageType.START_INDICATION
        })
    }

    @Test
    fun `VIDEO_FOCUS with mode 0 (NONE) does not trigger start`() {
        channel.sendSetup()
        channel.onMessage(AVMessageType.SETUP_RESPONSE, byteArrayOf(0x08, 0x02, 0x10, 0x05, 0x18, 0x00))
        cb.messages.clear()

        // focus=NONE(0)
        channel.onMessage(AVMessageType.VIDEO_FOCUS_INDICATION, byteArrayOf(0x08, 0x00, 0x10, 0x00))

        assertEquals(VideoState.CONFIGURED, channel.state)
        assertTrue(cb.messages.isEmpty())
    }

    @Test
    fun `START_INDICATION format includes session and config`() {
        channel.sendSetup()
        channel.onMessage(AVMessageType.SETUP_RESPONSE, byteArrayOf(0x08, 0x02, 0x10, 0x05, 0x18, 0x00))
        cb.messages.clear()

        channel.sendStart()

        assertEquals(VideoState.STARTED, channel.state)
        val startMsg = cb.messages.find {
            val t = ((it.second[0].toInt() and 0xFF) shl 8) or (it.second[1].toInt() and 0xFF)
            t == AVMessageType.START_INDICATION
        }
        assertNotNull(startMsg)
        // Payload should have session_id and config fields
        val payload = startMsg!!.second.copyOfRange(2, startMsg.second.size)
        assertTrue(payload.size >= 4)
        assertEquals(0x08.toByte(), payload[0]) // field 1 tag (session_id)
    }

    // --- Legacy handlers (head unit sends to us) ---

    @Test
    fun `SETUP_REQUEST sends SETUP_RESPONSE with OK status`() {
        channel.onMessage(AVMessageType.SETUP_REQUEST, byteArrayOf(0x08, 0x00))

        assertEquals(1, cb.messages.size)
        assertEquals(AVMessageType.SETUP_RESPONSE, cb.lastMessageType())
        val payload = cb.messages[0].second.copyOfRange(2, cb.messages[0].second.size)
        assertEquals(0x08.toByte(), payload[0])
        assertEquals(0x02.toByte(), payload[1]) // STATUS_READY
    }

    @Test
    fun `VIDEO_FOCUS_REQUEST sends FOCUS_INDICATION`() {
        channel.onMessage(AVMessageType.VIDEO_FOCUS_REQUEST, byteArrayOf(0x10, 0x01, 0x18, 0x01))

        assertEquals(1, cb.messages.size)
        assertEquals(AVMessageType.VIDEO_FOCUS_INDICATION, cb.lastMessageType())
    }

    @Test
    fun `STOP_INDICATION resets state to IDLE`() {
        channel.sendSetup()
        channel.onMessage(AVMessageType.STOP_INDICATION, ByteArray(0))
        assertEquals(VideoState.IDLE, channel.state)
    }

    // --- Frame format ---

    @Test
    fun `default config is 800x480 15fps`() {
        assertEquals(800, channel.config.width)
        assertEquals(480, channel.config.height)
        assertEquals(15, channel.config.fps)
    }

    @Test
    fun `channel ID is preserved in callbacks`() {
        val ch = VideoChannel(7u, cb)
        ch.onMessage(AVMessageType.SETUP_REQUEST, byteArrayOf(0x08, 0x00))
        assertEquals(7.toUByte(), cb.messages[0].first)
    }

    // --- Full flow test ---

    @Test
    fun `full flow - setup to start`() {
        // 1. Send setup
        channel.sendSetup()
        assertEquals(VideoState.SETUP_SENT, channel.state)

        // 2. Receive CONFIG
        channel.onMessage(AVMessageType.SETUP_RESPONSE, byteArrayOf(0x08, 0x02, 0x10, 0x0A, 0x18, 0x00))
        assertEquals(VideoState.CONFIGURED, channel.state)
        assertEquals(10, channel.maxUnacked)

        // 3. Receive VIDEO_FOCUS (PROJECTED)
        channel.onMessage(AVMessageType.VIDEO_FOCUS_INDICATION, byteArrayOf(0x08, 0x01, 0x10, 0x01))
        assertEquals(VideoState.STARTED, channel.state)

        // 4. Verify START was sent
        val startSent = cb.messages.any {
            val t = ((it.second[0].toInt() and 0xFF) shl 8) or (it.second[1].toInt() and 0xFF)
            t == AVMessageType.START_INDICATION
        }
        assertTrue("START should be sent after focus", startSent)
    }
}

package org.openandroidauto.channel

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioChannelTest {

    class TestCallback : VideoChannelCallback {
        val frames = mutableListOf<Pair<UByte, ByteArray>>()
        val messages = mutableListOf<Pair<UByte, ByteArray>>()
        override fun onVideoFrame(channelId: UByte, payload: ByteArray) { frames.add(channelId to payload) }
        override fun onSendMessage(channelId: UByte, payload: ByteArray) { messages.add(channelId to payload) }
    }

    @Test
    fun `AudioOutputChannel SETUP_REQUEST sends SETUP_RESPONSE with OK`() {
        val cb = TestCallback()
        val channel = AudioOutputChannel(3u, cb)
        channel.onMessage(AVMessageType.SETUP_REQUEST, byteArrayOf(0x08, 0x00))

        assertEquals(1, cb.messages.size)
        val msg = cb.messages[0].second
        val type = ByteBuffer.wrap(msg).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF
        assertEquals(AVMessageType.SETUP_RESPONSE, type)

        val payload = msg.copyOfRange(2, msg.size)
        assertEquals(0x08.toByte(), payload[0])
        assertEquals(0x02.toByte(), payload[1]) // OK
    }

    @Test
    fun `AudioOutputChannel constants are correct`() {
        assertEquals(48000, AudioOutputChannel.SAMPLE_RATE)
        assertEquals(2, AudioOutputChannel.CHANNEL_COUNT)
        assertEquals(16, AudioOutputChannel.BIT_DEPTH)
    }

    @Test
    fun `AudioOutputChannel STOP does not crash without recording`() {
        val cb = TestCallback()
        val channel = AudioOutputChannel(3u, cb)
        channel.stop() // should not throw
    }

    @Test
    fun `AudioInputChannel STOP does not crash without AudioTrack`() {
        val cb = TestCallback()
        val channel = AudioInputChannel(4u, cb)
        channel.stop() // should not throw
    }

    @Test
    fun `AudioInputChannel ignores too-short audio data`() {
        val cb = TestCallback()
        val channel = AudioInputChannel(4u, cb)
        // Without opening, audio data should be silently ignored (no AudioTrack)
        channel.onMessage(AVMessageType.AV_MEDIA_WITH_TIMESTAMP, ByteArray(4))
        assertEquals(0, cb.messages.size)
    }

    @Test
    fun `AudioInputChannel channel ID preserved`() {
        val cb = TestCallback()
        val channel = AudioInputChannel(7u, cb)
        // Audio data with valid length but no AudioTrack - still sends ACK
        val payload = ByteArray(320 + 8) // 8 byte timestamp + PCM
        channel.onMessage(AVMessageType.AV_MEDIA_WITH_TIMESTAMP, payload)
        // ACK should be sent with correct channel ID
        assertEquals(1, cb.messages.size)
        assertEquals(7.toUByte(), cb.messages[0].first)
        val type = ByteBuffer.wrap(cb.messages[0].second).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF
        assertEquals(AVMessageType.AV_MEDIA_ACK, type)
    }
}

package org.openandroidauto.protocol

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class MessageFramerTest {

    @Test
    fun `single frame round-trip`() {
        val payload = "hello".toByteArray()
        val frames = MessageFramer.encode(1u, payload)

        assertEquals(1, frames.size)
        val decoder = MessageFramer.Decoder()
        val messages = decoder.decode(ByteBuffer.wrap(frames[0]))

        assertEquals(1, messages.size)
        assertEquals(1.toUByte(), messages[0].channelId)
        assertArrayEquals(payload, messages[0].payload)
    }

    @Test
    fun `single frame has correct header flags`() {
        val frames = MessageFramer.encode(5u, "test".toByteArray(), control = true)
        val buf = ByteBuffer.wrap(frames[0])
        val channelId = buf.get().toUByte()
        val flags = buf.get().toUByte()
        val header = FrameHeader(channelId, flags)

        assertEquals(5.toUByte(), channelId)
        assertEquals(FrameHeader.FrameType.SINGLE, header.frameType)
        assertTrue(header.isControl)
        assertFalse(header.isEncrypted)
    }

    @Test
    fun `multi-frame fragmentation for large payload`() {
        val payload = ByteArray(MessageFramer.MAX_FRAME_PAYLOAD * 2 + 100) { (it % 256).toByte() }
        val frames = MessageFramer.encode(2u, payload)

        assertEquals(3, frames.size)

        // Verify frame types
        val header0 = FrameHeader(ByteBuffer.wrap(frames[0]).get().toUByte(), ByteBuffer.wrap(frames[0]).apply { get() }.get().toUByte())
        val header1 = FrameHeader(ByteBuffer.wrap(frames[1]).get().toUByte(), ByteBuffer.wrap(frames[1]).apply { get() }.get().toUByte())
        val header2 = FrameHeader(ByteBuffer.wrap(frames[2]).get().toUByte(), ByteBuffer.wrap(frames[2]).apply { get() }.get().toUByte())

        assertEquals(FrameHeader.FrameType.FIRST, header0.frameType)
        assertEquals(FrameHeader.FrameType.MIDDLE, header1.frameType)
        assertEquals(FrameHeader.FrameType.LAST, header2.frameType)
    }

    @Test
    fun `multi-frame reassembly`() {
        val payload = ByteArray(MessageFramer.MAX_FRAME_PAYLOAD * 2 + 100) { (it % 256).toByte() }
        val frames = MessageFramer.encode(2u, payload)

        val decoder = MessageFramer.Decoder()
        val allFrames = ByteBuffer.allocate(frames.sumOf { it.size })
        frames.forEach { allFrames.put(it) }
        allFrames.flip()

        val messages = decoder.decode(allFrames)
        assertEquals(1, messages.size)
        assertArrayEquals(payload, messages[0].payload)
    }

    @Test
    fun `max payload boundary - exactly 16KB fits in single frame`() {
        val payload = ByteArray(MessageFramer.MAX_FRAME_PAYLOAD) { 0x42 }
        val frames = MessageFramer.encode(0u, payload)

        assertEquals(1, frames.size)
        val decoder = MessageFramer.Decoder()
        val messages = decoder.decode(ByteBuffer.wrap(frames[0]))
        assertEquals(1, messages.size)
        assertArrayEquals(payload, messages[0].payload)
    }

    @Test
    fun `one byte over max payload triggers fragmentation`() {
        val payload = ByteArray(MessageFramer.MAX_FRAME_PAYLOAD + 1) { 0x42 }
        val frames = MessageFramer.encode(0u, payload)

        assertEquals(2, frames.size)
    }

    @Test
    fun `empty payload round-trip`() {
        val frames = MessageFramer.encode(3u, ByteArray(0))
        assertEquals(1, frames.size)

        val decoder = MessageFramer.Decoder()
        val messages = decoder.decode(ByteBuffer.wrap(frames[0]))
        assertEquals(1, messages.size)
        assertEquals(0, messages[0].payload.size)
    }
}

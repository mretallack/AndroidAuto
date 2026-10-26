package org.openandroidauto.protocol

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

/**
 * Framing tests built from **real captured wire data** (session
 * `aa_log_2026-10-26_08-54-38.txt`).
 *
 * These exist because an earlier investigation into "ACCEL/GYRO deliver 1 byte
 * but declare 20-38" could not be completed from the log: only the first 32 bytes
 * of each read were recorded, hiding the fact that a single read can contain
 * several frames. These tests pin the framing behaviour that the log could not
 * show.
 */
class MessageFramerCapturedTest {

    private fun frame(channel: Int, flags: Int, payload: ByteArray): ByteArray {
        val out = ByteArray(4 + payload.size)
        out[0] = channel.toByte()
        out[1] = flags.toByte()
        out[2] = ((payload.size shr 8) and 0xFF).toByte()
        out[3] = (payload.size and 0xFF).toByte()
        payload.copyInto(out, 4)
        return out
    }

    private fun decodeAll(bytes: ByteArray): List<MessageFramer.Decoder.Message> {
        val decoder = MessageFramer.Decoder()
        return decoder.decode(ByteBuffer.wrap(bytes))
    }

    @Test
    fun `a single read can contain multiple frames`() {
        // Mirrors the 125-byte read that held a 29-byte and a 30-byte message.
        val a = frame(6, 0x0b, ByteArray(29) { 0xAA.toByte() })
        val b = frame(6, 0x0b, ByteArray(30) { 0xBB.toByte() })
        val msgs = decodeAll(a + b)

        assertEquals("both frames should decode from one read", 2, msgs.size)
        assertEquals(29, msgs[0].payload.size)
        assertEquals(30, msgs[1].payload.size)
        assertEquals("same channel", msgs[0].channelId, msgs[1].channelId)
    }

    @Test
    fun `a partial trailing frame is not lost`() {
        // The decoder must not consume a frame it cannot complete.
        val a = frame(6, 0x0b, ByteArray(20) { 1 })
        val partial = frame(6, 0x0b, ByteArray(30) { 2 }).copyOfRange(0, 10)
        val msgs = decodeAll(a + partial)

        assertEquals("only the complete frame should decode", 1, msgs.size)
        assertEquals(20, msgs[0].payload.size)
    }

    @Test
    fun `payload sizes are read big-endian`() {
        // 0x0102 = 258 bytes. A little-endian read would give 513.
        val big = frame(1, 0x03, ByteArray(258))
        val msgs = decodeAll(big)
        assertEquals(1, msgs.size)
        assertEquals(258, msgs[0].payload.size)
    }

    @Test
    fun `captured single-frame header decodes with correct channel and flags`() {
        // Verbatim from the log: `06 0b 00 3a 17 03 03 00 35 …`
        // ch=6, flags=0x0b (SINGLE|control|no-enc is 0x07; 0x0b has encrypted bit
        // plus SINGLE? 0x0b = 0b1011 => type 3 (SINGLE), 0x08 encrypted)
        val header = FrameHeader(0x06.toUByte(), 0x0b.toUByte())
        assertEquals(6, header.channelId.toInt())
        assertEquals(FrameHeader.FrameType.SINGLE, header.frameType)
        assertTrue("0x08 bit set => encrypted", header.isEncrypted)

        val control = FrameHeader(0x06.toUByte(), 0x07.toUByte())
        assertTrue("0x04 bit set => control", control.isControl)
    }

    @Test
    fun `declared length matches the bytes actually carried`() {
        // Regression guard for the "declared 30, got 1" anomaly: at the framing
        // level the carried payload must equal the declared length. If this ever
        // fails, the defect is in the framer, not in the sensor decoder.
        for (len in intArrayOf(20, 29, 30, 38)) {
            val payload = ByteArray(len) { (it and 0xFF).toByte() }
            val msgs = decodeAll(frame(6, 0x0b, payload))
            assertEquals("len $len must survive framing intact", len, msgs[0].payload.size)
            assertArrayEquals("len $len bytes must be untouched", payload, msgs[0].payload)
        }
    }
}

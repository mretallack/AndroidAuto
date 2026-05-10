package org.openandroidauto.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * AAP frame header: 1 byte channelId + 1 byte flags.
 * Flags bits: [0:1] frameType, [2] control, [3] encrypted
 */
data class FrameHeader(
    val channelId: UByte,
    val flags: UByte
) {
    enum class FrameType(val value: Int) {
        MIDDLE(0), FIRST(1), LAST(2), SINGLE(3);
        companion object {
            fun from(v: Int) = entries.first { it.value == v }
        }
    }

    val frameType: FrameType get() = FrameType.from((flags.toInt() and 0x03))
    val isControl: Boolean get() = (flags.toInt() and 0x04) != 0
    val isEncrypted: Boolean get() = (flags.toInt() and 0x08) != 0

    companion object {
        fun create(
            channelId: UByte,
            frameType: FrameType,
            control: Boolean = false,
            encrypted: Boolean = false
        ): FrameHeader {
            val flags = frameType.value or
                (if (control) 0x04 else 0) or
                (if (encrypted) 0x08 else 0)
            return FrameHeader(channelId, flags.toUByte())
        }
    }
}

/**
 * Encodes/decodes AAP frames.
 * Wire format: [channelId:1][flags:1][length:2][payload:N]
 * First frame of multi-frame adds 4 bytes total message length before chunk length.
 * Max payload per frame: 16384 bytes (0x4000).
 */
object MessageFramer {
    const val MAX_FRAME_PAYLOAD = 16384

    data class Frame(val header: FrameHeader, val payload: ByteArray) {
        override fun equals(other: Any?) = other is Frame &&
            header == other.header && payload.contentEquals(other.payload)
        override fun hashCode() = 31 * header.hashCode() + payload.contentHashCode()
    }

    /**
     * Encode a message into one or more frames.
     */
    fun encode(channelId: UByte, payload: ByteArray, control: Boolean = false, encrypted: Boolean = false): List<ByteArray> {
        if (payload.size <= MAX_FRAME_PAYLOAD) {
            val header = FrameHeader.create(channelId, FrameHeader.FrameType.SINGLE, control, encrypted)
            return listOf(buildFrame(header, payload))
        }

        val numChunks = (payload.size + MAX_FRAME_PAYLOAD - 1) / MAX_FRAME_PAYLOAD
        return (0 until numChunks).map { i ->
            val offset = i * MAX_FRAME_PAYLOAD
            val end = minOf(offset + MAX_FRAME_PAYLOAD, payload.size)
            val chunk = payload.copyOfRange(offset, end)
            val type = when {
                i == 0 -> FrameHeader.FrameType.FIRST
                i == numChunks - 1 -> FrameHeader.FrameType.LAST
                else -> FrameHeader.FrameType.MIDDLE
            }
            val header = FrameHeader.create(channelId, type, control, encrypted)
            if (i == 0) buildFirstFrame(header, chunk, payload.size) else buildFrame(header, chunk)
        }
    }

    private fun buildFrame(header: FrameHeader, payload: ByteArray): ByteArray {
        val buf = ByteBuffer.allocate(4 + payload.size).order(ByteOrder.BIG_ENDIAN)
        buf.put(header.channelId.toByte())
        buf.put(header.flags.toByte())
        buf.putShort(payload.size.toShort())
        buf.put(payload)
        return buf.array()
    }

    private fun buildFirstFrame(header: FrameHeader, payload: ByteArray, totalLength: Int): ByteArray {
        val buf = ByteBuffer.allocate(8 + payload.size).order(ByteOrder.BIG_ENDIAN)
        buf.put(header.channelId.toByte())
        buf.put(header.flags.toByte())
        buf.putInt(totalLength)
        buf.putShort(payload.size.toShort())
        buf.put(payload)
        return buf.array()
    }

    /**
     * Stateful decoder that reassembles multi-frame messages.
     */
    class Decoder {
        private var pendingData = mutableListOf<ByteArray>()
        private var pendingChannelId: UByte? = null

        data class Message(val channelId: UByte, val flags: UByte, val payload: ByteArray) {
            override fun equals(other: Any?) = other is Message &&
                channelId == other.channelId && payload.contentEquals(other.payload)
            override fun hashCode() = 31 * channelId.hashCode() + payload.contentHashCode()
        }

        /**
         * Feed raw bytes from the transport. Returns decoded messages (0 or more).
         * Handles partial frames by rewinding to the start of incomplete frames.
         */
        fun decode(data: ByteBuffer): List<Message> {
            val messages = mutableListOf<Message>()
            while (data.remaining() >= 4) {
                val mark = data.position()
                val channelId = data.get().toUByte()
                val flags = data.get().toUByte()
                val header = FrameHeader(channelId, flags)

                val payloadSize: Int
                if (header.frameType == FrameHeader.FrameType.FIRST) {
                    if (data.remaining() < 6) { data.position(mark); break }
                    data.getInt() // total message length (informational)
                    payloadSize = data.getShort().toInt() and 0xFFFF
                } else {
                    payloadSize = data.getShort().toInt() and 0xFFFF
                }

                if (data.remaining() < payloadSize) { data.position(mark); break }
                val payload = ByteArray(payloadSize)
                data.get(payload)

                when (header.frameType) {
                    FrameHeader.FrameType.SINGLE -> {
                        messages.add(Message(channelId, flags, payload))
                    }
                    FrameHeader.FrameType.FIRST -> {
                        pendingData.clear()
                        pendingData.add(payload)
                        pendingChannelId = channelId
                    }
                    FrameHeader.FrameType.MIDDLE -> {
                        pendingData.add(payload)
                    }
                    FrameHeader.FrameType.LAST -> {
                        pendingData.add(payload)
                        val assembled = ByteArray(pendingData.sumOf { it.size })
                        var offset = 0
                        for (chunk in pendingData) {
                            chunk.copyInto(assembled, offset)
                            offset += chunk.size
                        }
                        messages.add(Message(pendingChannelId ?: channelId, flags, assembled))
                        pendingData.clear()
                        pendingChannelId = null
                    }
                }
            }
            return messages
        }
    }
}

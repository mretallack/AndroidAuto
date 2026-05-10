package org.openandroidauto.integration

import org.openandroidauto.protocol.ControlMessageType
import org.openandroidauto.protocol.FrameHeader
import org.openandroidauto.protocol.MessageFramer
import org.openandroidauto.channel.AVMessageType
import org.openandroidauto.channel.InputMessageType
import java.io.Closeable
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Simulates a head unit for integration testing.
 * Runs a TCP server, accepts one connection, and speaks the AAP protocol.
 */
class HeadUnitSimulator : Closeable {
    private val serverSocket = ServerSocket(0) // random port
    val port: Int get() = serverSocket.localPort
    private var clientSocket: Socket? = null

    val receivedMessages = mutableListOf<ReceivedMessage>()

    data class ReceivedMessage(val channelId: UByte, val messageType: Int, val payload: ByteArray)

    fun acceptConnection() {
        serverSocket.soTimeout = 5000
        clientSocket = serverSocket.accept()
        clientSocket?.soTimeout = 5000
    }

    /**
     * Read one AAP frame from the connection.
     * Returns the decoded message or null on timeout/error.
     */
    fun readFrame(): ReceivedMessage? {
        val socket = clientSocket ?: return null
        val input = socket.getInputStream()

        // Read frame header: channelId(1) + flags(1)
        val headerBytes = ByteArray(2)
        if (input.read(headerBytes) != 2) return null
        val channelId = headerBytes[0].toUByte()
        val flags = headerBytes[1].toUByte()
        val header = FrameHeader(channelId, flags)

        // Read length
        val lengthSize = if (header.frameType == FrameHeader.FrameType.FIRST) 6 else 2
        val lengthBytes = ByteArray(lengthSize)
        if (input.read(lengthBytes) != lengthSize) return null

        val payloadLen = if (header.frameType == FrameHeader.FrameType.FIRST) {
            // First frame: 4 bytes total + 2 bytes chunk
            ByteBuffer.wrap(lengthBytes, 4, 2).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF
        } else {
            ByteBuffer.wrap(lengthBytes).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF
        }

        // Read payload
        val payload = ByteArray(payloadLen)
        var read = 0
        while (read < payloadLen) {
            val n = input.read(payload, read, payloadLen - read)
            if (n < 0) return null
            read += n
        }

        // Extract message type (first 2 bytes of payload)
        val messageType = if (payload.size >= 2) {
            ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
        } else 0

        val msgPayload = if (payload.size > 2) payload.copyOfRange(2, payload.size) else ByteArray(0)
        val msg = ReceivedMessage(channelId, messageType, msgPayload)
        receivedMessages.add(msg)
        return msg
    }

    /**
     * Send a control message to the phone.
     */
    fun sendControlMessage(messageType: Int, payload: ByteArray = ByteArray(0)) {
        val msgPayload = ByteBuffer.allocate(2 + payload.size).order(ByteOrder.BIG_ENDIAN)
            .putShort(messageType.toShort())
            .put(payload)
            .array()
        sendFrame(0u, msgPayload, control = true)
    }

    /**
     * Send a channel message to the phone.
     */
    fun sendChannelMessage(channelId: UByte, messageType: Int, payload: ByteArray = ByteArray(0)) {
        val msgPayload = ByteBuffer.allocate(2 + payload.size).order(ByteOrder.BIG_ENDIAN)
            .putShort(messageType.toShort())
            .put(payload)
            .array()
        sendFrame(channelId, msgPayload, control = false)
    }

    /**
     * Send a raw frame.
     */
    fun sendFrame(channelId: UByte, payload: ByteArray, control: Boolean) {
        val frames = MessageFramer.encode(channelId, payload, control)
        val output = clientSocket?.getOutputStream() ?: return
        frames.forEach { output.write(it) }
        output.flush()
    }

    /**
     * Send VERSION_RESPONSE (version 1.1, status OK).
     */
    fun sendVersionResponse() {
        val payload = ByteBuffer.allocate(6).order(ByteOrder.BIG_ENDIAN)
            .putShort(1).putShort(1).putShort(0).array()
        sendControlMessage(ControlMessageType.VERSION_RESPONSE, payload)
    }

    /**
     * Send SERVICE_DISCOVERY_REQUEST.
     */
    fun sendServiceDiscoveryRequest(deviceName: String = "TestHU", brand: String = "Test") {
        // Protobuf: field 4 (device_name) string, field 5 (device_brand) string
        val nameBytes = deviceName.toByteArray()
        val brandBytes = brand.toByteArray()
        val payload = ByteBuffer.allocate(4 + nameBytes.size + brandBytes.size)
            .put(0x22.toByte()).put(nameBytes.size.toByte()).put(nameBytes) // field 4, string
            .put(0x2A.toByte()).put(brandBytes.size.toByte()).put(brandBytes) // field 5, string
            .array()
        sendControlMessage(ControlMessageType.SERVICE_DISCOVERY_REQUEST, payload)
    }

    /**
     * Send CHANNEL_OPEN_REQUEST.
     */
    fun sendChannelOpenRequest(channelId: Int, priority: Int = 0) {
        // ChannelOpenRequest protobuf: field 1 (priority) varint, field 2 (channel_id) varint
        val payload = byteArrayOf(
            0x08, priority.toByte(),    // field 1: priority
            0x10, channelId.toByte()    // field 2: channel_id
        )
        sendControlMessage(ControlMessageType.CHANNEL_OPEN_REQUEST, payload)
    }

    /**
     * Send a touch INPUT_EVENT_INDICATION on the input channel.
     */
    fun sendTouchEvent(channelId: UByte, x: Int, y: Int, action: Int = 0, pointerId: Int = 0) {
        val touchLocation = byteArrayOf(
            0x08, x.toByte(),
            0x10, y.toByte(),
            0x18, pointerId.toByte()
        )
        val touchEvent = byteArrayOf(
            0x0A, touchLocation.size.toByte()
        ) + touchLocation + byteArrayOf(
            0x18, action.toByte()
        )
        val inputEvent = byteArrayOf(
            0x08, 0x01, // timestamp = 1
            0x1A, touchEvent.size.toByte()
        ) + touchEvent

        sendChannelMessage(channelId, InputMessageType.INPUT_EVENT_INDICATION, inputEvent)
    }

    /**
     * Send AV_MEDIA_ACK on a video channel.
     */
    fun sendMediaAck(channelId: UByte, session: Int = 0, value: Int = 1) {
        val payload = byteArrayOf(
            0x08, session.toByte(),  // field 1: session
            0x10, value.toByte()     // field 2: value
        )
        sendChannelMessage(channelId, AVMessageType.AV_MEDIA_ACK, payload)
    }

    override fun close() {
        clientSocket?.close()
        serverSocket.close()
    }
}

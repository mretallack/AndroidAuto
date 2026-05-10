package org.openandroidauto.protocol

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Protocol states for the AAP connection lifecycle.
 */
enum class ProtocolState {
    IDLE,
    VERSION_NEGOTIATION,
    TLS_HANDSHAKE,
    SERVICE_DISCOVERY,
    ACTIVE,
    DISCONNECTED
}

/**
 * Control channel message types (from Wifi.proto ControlMessage enum).
 */
object ControlMessageType {
    const val VERSION_REQUEST: Int = 0x0001
    const val VERSION_RESPONSE: Int = 0x0002
    const val SSL_HANDSHAKE: Int = 0x0003
    const val AUTH_COMPLETE: Int = 0x0004
    const val SERVICE_DISCOVERY_REQUEST: Int = 0x0005
    const val SERVICE_DISCOVERY_RESPONSE: Int = 0x0006
    const val CHANNEL_OPEN_REQUEST: Int = 0x0007
    const val CHANNEL_OPEN_RESPONSE: Int = 0x0008
    const val PING_REQUEST: Int = 0x000B
    const val PING_RESPONSE: Int = 0x000C
    const val SHUTDOWN_REQUEST: Int = 0x000F
    const val SHUTDOWN_RESPONSE: Int = 0x0010
}

/**
 * Callback interface for protocol events.
 */
interface ProtocolCallback {
    fun onSendFrame(channelId: UByte, payload: ByteArray, control: Boolean)
    fun onTlsData(data: ByteArray)
    fun onTlsComplete()
    fun onServiceDiscoveryRequest(deviceName: String, deviceBrand: String)
    fun onChannelOpenRequest(channelId: Int, priority: Int)
    fun onActive()
    fun onShutdown()
}

/**
 * AAP Protocol Engine - manages the connection state machine.
 * Processes incoming control messages and drives state transitions.
 */
class ProtocolEngine(private val callback: ProtocolCallback) {

    private val TAG = "AAProtocol"

    var state: ProtocolState = ProtocolState.IDLE
        private set

    companion object {
        const val PROTOCOL_VERSION_MAJOR = 1
        const val PROTOCOL_VERSION_MINOR = 1
        const val CONTROL_CHANNEL: UByte = 0u
    }

    /**
     * Start the protocol by sending VERSION_REQUEST.
     */
    fun start() {
        check(state == ProtocolState.IDLE) { "Cannot start in state $state" }
        state = ProtocolState.VERSION_NEGOTIATION
        sendVersionRequest()
    }

    /**
     * Process an incoming control message (2-byte type + payload).
     */
    fun onMessage(messageType: Int, payload: ByteArray) {
        when (messageType) {
            ControlMessageType.VERSION_RESPONSE -> handleVersionResponse(payload)
            ControlMessageType.SSL_HANDSHAKE -> handleSslHandshake(payload)
            ControlMessageType.SERVICE_DISCOVERY_REQUEST -> handleServiceDiscoveryRequest(payload)
            ControlMessageType.CHANNEL_OPEN_REQUEST -> handleChannelOpenRequest(payload)
            ControlMessageType.PING_REQUEST -> handlePingRequest(payload)
            ControlMessageType.PING_RESPONSE -> {} // Acknowledged
            ControlMessageType.SHUTDOWN_REQUEST -> handleShutdownRequest(payload)
        }
    }

    /**
     * Called when TLS handshake completes successfully.
     */
    fun onTlsHandshakeComplete() {
        check(state == ProtocolState.TLS_HANDSHAKE) { "TLS complete in wrong state: $state" }
        Log.i(TAG, "TLS complete, sending AUTH_COMPLETE, moving to SERVICE_DISCOVERY")
        sendAuthComplete()
        state = ProtocolState.SERVICE_DISCOVERY
    }

    /**
     * Send TLS handshake data wrapped in SSL_HANDSHAKE control message.
     */
    fun sendTlsData(data: ByteArray) {
        val msg = buildMessage(ControlMessageType.SSL_HANDSHAKE, data)
        callback.onSendFrame(CONTROL_CHANNEL, msg, control = true)
    }

    /**
     * Send SERVICE_DISCOVERY_RESPONSE with channel descriptors.
     */
    fun sendServiceDiscoveryResponse(responsePayload: ByteArray) {
        check(state == ProtocolState.SERVICE_DISCOVERY) { "Wrong state for discovery response: $state" }
        val msg = buildMessage(ControlMessageType.SERVICE_DISCOVERY_RESPONSE, responsePayload)
        callback.onSendFrame(CONTROL_CHANNEL, msg, control = true)
        state = ProtocolState.ACTIVE
        callback.onActive()
    }

    /**
     * Send CHANNEL_OPEN_RESPONSE. status: 0=OK, 1=FAIL
     */
    fun sendChannelOpenResponse(status: Int) {
        // ChannelOpenResponse protobuf: field 1 (status) varint
        val payload = byteArrayOf(0x08, status.toByte())
        val msg = buildMessage(ControlMessageType.CHANNEL_OPEN_RESPONSE, payload)
        callback.onSendFrame(CONTROL_CHANNEL, msg, control = false)
    }

    /**
     * Send a PING_REQUEST.
     */
    fun sendPingRequest(timestamp: Long) {
        // PingRequest protobuf: field 1 (timestamp) varint
        val out = java.io.ByteArrayOutputStream()
        out.write(0x08) // field 1, wire type 0
        var v = timestamp
        while (v > 0x7F) { out.write(((v.toInt() and 0x7F) or 0x80)); v = v ushr 7 }
        out.write(v.toInt() and 0x7F)
        val msg = buildMessage(ControlMessageType.PING_REQUEST, out.toByteArray())
        callback.onSendFrame(CONTROL_CHANNEL, msg, control = false)
    }

    /**
     * Initiate graceful shutdown.
     */
    fun shutdown() {
        val msg = buildMessage(ControlMessageType.SHUTDOWN_REQUEST, byteArrayOf(0x00, 0x01)) // reason=QUIT
        callback.onSendFrame(CONTROL_CHANNEL, msg, control = true)
        state = ProtocolState.DISCONNECTED
        callback.onShutdown()
    }

    // --- Private handlers ---

    private fun sendVersionRequest() {
        // Version request: major(2) + minor(2) — no status field
        val payload = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
            .putShort(PROTOCOL_VERSION_MAJOR.toShort())
            .putShort(PROTOCOL_VERSION_MINOR.toShort())
            .array()
        val msg = buildMessage(ControlMessageType.VERSION_REQUEST, payload)
        callback.onSendFrame(CONTROL_CHANNEL, msg, control = false)
    }

    private fun handleVersionResponse(payload: ByteArray) {
        check(state == ProtocolState.VERSION_NEGOTIATION) { "Version response in wrong state: $state" }
        Log.i(TAG, "Version response received, moving to TLS_HANDSHAKE")
        state = ProtocolState.TLS_HANDSHAKE
    }

    private fun handleSslHandshake(payload: ByteArray) {
        check(state == ProtocolState.TLS_HANDSHAKE) { "SSL handshake in wrong state: $state" }
        callback.onTlsData(payload)
    }

    private fun sendAuthComplete() {
        // AuthCompleteIndication { status = OK (0) } - protobuf field 1, varint 0
        val protobufPayload = byteArrayOf(0x08, 0x00) // field 1, varint, value 0 (OK)
        val msg = buildMessage(ControlMessageType.AUTH_COMPLETE, protobufPayload)
        callback.onSendFrame(CONTROL_CHANNEL, msg, control = true)
    }

    private fun handleServiceDiscoveryRequest(payload: ByteArray) {
        check(state == ProtocolState.SERVICE_DISCOVERY) { "Discovery request in wrong state: $state" }
        // Parse device_name (field 4) and device_brand (field 5) from protobuf
        // For now, pass raw payload to callback
        callback.onServiceDiscoveryRequest("", "")
    }

    private fun handleChannelOpenRequest(payload: ByteArray) {
        check(state == ProtocolState.ACTIVE || state == ProtocolState.SERVICE_DISCOVERY) {
            "Channel open in wrong state: $state"
        }
        // ChannelOpenRequest protobuf: field 1 (priority) varint, field 2 (channel_id) varint
        var priority = 0
        var channelId = 0
        var i = 0
        while (i < payload.size) {
            val tag = payload[i].toInt() and 0xFF
            val field = tag ushr 3
            i++
            var value = 0; var shift = 0
            while (i < payload.size) {
                val b = payload[i].toInt() and 0xFF; i++
                value = value or ((b and 0x7F) shl shift)
                if (b and 0x80 == 0) break
                shift += 7
            }
            when (field) { 1 -> priority = value; 2 -> channelId = value }
        }
        callback.onChannelOpenRequest(channelId, priority)
    }

    private fun handlePingRequest(payload: ByteArray) {
        // Echo the same protobuf payload back as PING_RESPONSE
        val msg = buildMessage(ControlMessageType.PING_RESPONSE, payload)
        callback.onSendFrame(CONTROL_CHANNEL, msg, control = false)
    }

    private fun handleShutdownRequest(payload: ByteArray) {
        Log.i(TAG, "Shutdown request received")
        val msg = buildMessage(ControlMessageType.SHUTDOWN_RESPONSE, ByteArray(0))
        callback.onSendFrame(CONTROL_CHANNEL, msg, control = true)
        state = ProtocolState.DISCONNECTED
        callback.onShutdown()
    }

    private fun buildMessage(type: Int, payload: ByteArray): ByteArray {
        val buf = ByteBuffer.allocate(2 + payload.size).order(ByteOrder.BIG_ENDIAN)
        buf.putShort(type.toShort())
        buf.put(payload)
        return buf.array()
    }
}

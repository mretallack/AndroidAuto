package org.openandroidauto.protocol

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class ProtocolState {
    IDLE, VERSION_NEGOTIATION, TLS_HANDSHAKE, SERVICE_DISCOVERY, ACTIVE, DISCONNECTED
}

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
    const val NAVIGATION_FOCUS_REQUEST: Int = 0x000D
    const val NAVIGATION_FOCUS_RESPONSE: Int = 0x000E
    const val SHUTDOWN_REQUEST: Int = 0x000F
    const val SHUTDOWN_RESPONSE: Int = 0x0010
    const val VOICE_SESSION_REQUEST: Int = 0x0011
    const val AUDIO_FOCUS_REQUEST: Int = 0x0012
    const val AUDIO_FOCUS_RESPONSE: Int = 0x0013
}

/** Parsed channel descriptor from SERVICE_DISCOVERY_RESPONSE */
data class ChannelDescriptor(
    val channelId: Int,
    val hasSensor: Boolean = false,
    val hasAv: Boolean = false,
    val hasInput: Boolean = false,
    val hasAvInput: Boolean = false,
    val hasNavigation: Boolean = false,
    val hasBluetooth: Boolean = false
)

interface ProtocolCallback {
    fun onSendFrame(channelId: UByte, payload: ByteArray, control: Boolean)
    fun onTlsData(data: ByteArray)
    fun onTlsComplete()
    fun onServiceDiscoveryRequest(deviceName: String, deviceBrand: String)
    fun onServiceDiscoveryResponse(channels: List<ChannelDescriptor>)
    fun onChannelOpenRequest(channelId: Int, priority: Int)
    fun onChannelOpened(channelId: Int)
    fun onActive()
    fun onShutdown()
    fun onAudioFocusRequest(focusType: Int)
    fun onNavigationFocusRequest(type: Int)
    fun onVoiceSessionRequest(type: Int)
}

class ProtocolEngine(private val callback: ProtocolCallback) {

    private val TAG = "AAProtocol"

    var deviceName: String = "Open Android Auto"
    var deviceBrand: String = "OpenAA"
    var bluetoothAddress: String? = null

    var state: ProtocolState = ProtocolState.IDLE
        private set

    /** Channels discovered from head unit's SERVICE_DISCOVERY_RESPONSE */
    var discoveredChannels: List<ChannelDescriptor> = emptyList()
        private set

    /** Channels we've requested to open, pending response */
    private val pendingChannelOpens = mutableListOf<Int>()

    /** Channels successfully opened */
    val openedChannels = mutableSetOf<Int>()

    companion object {
        const val PROTOCOL_VERSION_MAJOR = 1
        const val PROTOCOL_VERSION_MINOR = 7
        const val CONTROL_CHANNEL: UByte = 0u
    }

    fun start() {
        check(state == ProtocolState.IDLE) { "Cannot start in state $state" }
        Log.w(TAG, "Protocol started in VERSION_NEGOTIATION")
        state = ProtocolState.VERSION_NEGOTIATION
    }

    /** Send VERSION_REQUEST to the head unit (call after a timeout if no HU message received) */
    fun initiateVersionRequest() {
        if (state != ProtocolState.VERSION_NEGOTIATION) return
        sendVersionRequest()
    }

    fun onMessage(messageType: Int, payload: ByteArray) {
        when (messageType) {
            ControlMessageType.VERSION_REQUEST -> handleVersionRequest(payload)
            ControlMessageType.VERSION_RESPONSE -> handleVersionResponse(payload)
            ControlMessageType.SSL_HANDSHAKE -> handleSslHandshake(payload)
            ControlMessageType.AUTH_COMPLETE -> handleAuthComplete(payload)
            ControlMessageType.SERVICE_DISCOVERY_REQUEST -> handleServiceDiscoveryRequest(payload)
            ControlMessageType.SERVICE_DISCOVERY_RESPONSE -> handleServiceDiscoveryResponse(payload)
            ControlMessageType.CHANNEL_OPEN_REQUEST -> handleChannelOpenRequest(payload)
            ControlMessageType.CHANNEL_OPEN_RESPONSE -> handleChannelOpenResponse(payload)
            ControlMessageType.PING_REQUEST -> handlePingRequest(payload)
            ControlMessageType.PING_RESPONSE -> {}
            ControlMessageType.NAVIGATION_FOCUS_REQUEST -> handleNavigationFocusRequest(payload)
            ControlMessageType.NAVIGATION_FOCUS_RESPONSE -> {}
            ControlMessageType.SHUTDOWN_REQUEST -> handleShutdownRequest(payload)
            ControlMessageType.SHUTDOWN_RESPONSE -> { state = ProtocolState.DISCONNECTED }
            ControlMessageType.VOICE_SESSION_REQUEST -> handleVoiceSessionRequest(payload)
            ControlMessageType.AUDIO_FOCUS_REQUEST -> handleAudioFocusRequest(payload)
            ControlMessageType.AUDIO_FOCUS_RESPONSE -> {}
            else -> Log.w(TAG, "Unknown control message type: 0x${messageType.toString(16)}")
        }
    }

    fun onTlsHandshakeComplete() {
        check(state == ProtocolState.TLS_HANDSHAKE) { "TLS complete in wrong state: $state" }
        Log.w(TAG, "TLS complete, waiting for head unit AUTH_COMPLETE")
        state = ProtocolState.SERVICE_DISCOVERY
    }

    fun sendTlsData(data: ByteArray) {
        val msg = buildMessage(ControlMessageType.SSL_HANDSHAKE, data)
        callback.onSendFrame(CONTROL_CHANNEL, msg, control = false)
    }

    fun sendServiceDiscoveryResponse(responsePayload: ByteArray) {
        val msg = buildMessage(ControlMessageType.SERVICE_DISCOVERY_RESPONSE, responsePayload)
        callback.onSendFrame(CONTROL_CHANNEL, msg, control = true)
        if (state == ProtocolState.SERVICE_DISCOVERY) {
            state = ProtocolState.ACTIVE
            callback.onActive()
        }
    }

    fun sendChannelOpenResponse(status: Int) {
        val payload = byteArrayOf(0x08, status.toByte())
        val msg = buildMessage(ControlMessageType.CHANNEL_OPEN_RESPONSE, payload)
        callback.onSendFrame(CONTROL_CHANNEL, msg, control = true)
    }

    fun sendChannelOpenRequest(channelId: Int) {
        // ChannelOpenRequest is sent ON the target channel (not channel 0!)
        // with MessageType::CONTROL (control flag set in frame header)
        val out = java.io.ByteArrayOutputStream()
        out.write(0x08) // field 1 tag (priority)
        writeVarint(out, 0) // priority = 0
        out.write(0x10) // field 2 tag (channel_id)
        writeVarint(out, channelId.toLong())
        val payload = out.toByteArray()
        val msg = buildMessage(ControlMessageType.CHANNEL_OPEN_REQUEST, payload)
        Log.w(TAG, "Sending CHANNEL_OPEN_REQUEST on channel $channelId")
        pendingChannelOpens.add(channelId)
        // Send on the TARGET channel with control flag
        callback.onSendFrame(channelId.toUByte(), msg, control = true)
    }

    fun sendAudioFocusResponse(focusState: Int) {
        // AudioFocusResponse: field 1 (audio_focus_state) varint
        val payload = byteArrayOf(0x08, focusState.toByte())
        val msg = buildMessage(ControlMessageType.AUDIO_FOCUS_RESPONSE, payload)
        Log.w(TAG, "Sending AUDIO_FOCUS_RESPONSE state=$focusState")
        callback.onSendFrame(CONTROL_CHANNEL, msg, control = true)
    }

    fun sendAudioFocusRequest(focusType: Int) {
        // AudioFocusRequest: field 1 (audio_focus_type) varint
        val payload = byteArrayOf(0x08, focusType.toByte())
        val msg = buildMessage(ControlMessageType.AUDIO_FOCUS_REQUEST, payload)
        Log.w(TAG, "Sending AUDIO_FOCUS_REQUEST type=$focusType")
        callback.onSendFrame(CONTROL_CHANNEL, msg, control = false)
    }

    fun sendNavigationFocusResponse(type: Int) {
        // NavigationFocusResponse: field 1 (type) varint
        val payload = byteArrayOf(0x08, type.toByte())
        val msg = buildMessage(ControlMessageType.NAVIGATION_FOCUS_RESPONSE, payload)
        Log.w(TAG, "Sending NAVIGATION_FOCUS_RESPONSE type=$type")
        callback.onSendFrame(CONTROL_CHANNEL, msg, control = true)
    }

    fun sendPingRequest(timestamp: Long) {
        val out = java.io.ByteArrayOutputStream()
        out.write(0x08)
        writeVarint(out, timestamp)
        val msg = buildMessage(ControlMessageType.PING_REQUEST, out.toByteArray())
        callback.onSendFrame(CONTROL_CHANNEL, msg, control = false)
    }

    fun shutdown() {
        // ShutdownRequest: field 1 (reason) = QUIT(1)
        val payload = byteArrayOf(0x08, 0x01)
        val msg = buildMessage(ControlMessageType.SHUTDOWN_REQUEST, payload)
        callback.onSendFrame(CONTROL_CHANNEL, msg, control = true)
        state = ProtocolState.DISCONNECTED
        callback.onShutdown()
    }

    // --- Private handlers ---

    private fun handleVersionRequest(payload: ByteArray) {
        check(state == ProtocolState.VERSION_NEGOTIATION) { "Version request in wrong state: $state" }
        if (payload.size >= 4) {
            val major = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
            val minor = ((payload[2].toInt() and 0xFF) shl 8) or (payload[3].toInt() and 0xFF)
            Log.w(TAG, "Head unit version: $major.$minor")
        }
        sendVersionResponse()
        state = ProtocolState.TLS_HANDSHAKE
        Log.w(TAG, "Sent VERSION_RESPONSE, moving to TLS_HANDSHAKE")
    }

    private fun handleVersionResponse(payload: ByteArray) {
        check(state == ProtocolState.VERSION_NEGOTIATION) { "Version response in wrong state: $state" }
        if (payload.size >= 4) {
            val major = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
            val minor = ((payload[2].toInt() and 0xFF) shl 8) or (payload[3].toInt() and 0xFF)
            val status = if (payload.size >= 6) ((payload[4].toInt() and 0xFF) shl 8) or (payload[5].toInt() and 0xFF) else 0
            Log.w(TAG, "Head unit version: $major.$minor status=$status")
        }
        Log.w(TAG, "Version response received, moving to TLS_HANDSHAKE")
        state = ProtocolState.TLS_HANDSHAKE
    }

    private fun handleAuthComplete(payload: ByteArray) {
        var status = 0
        var i = 0
        while (i < payload.size) {
            val tag = payload[i].toInt() and 0xFF; i++
            val field = tag ushr 3
            var value = 0L; var shift = 0
            while (i < payload.size) {
                val b = payload[i].toInt() and 0xFF; i++
                value = value or (((b and 0x7F).toLong()) shl shift)
                if (b and 0x80 == 0) break
                shift += 7
            }
            if (field == 1) status = value.toInt()
        }
        Log.w(TAG, "Received AUTH_COMPLETE from head unit, status=$status")
        if (status != 0) {
            Log.e(TAG, "AUTH_COMPLETE failed with status=$status")
            state = ProtocolState.DISCONNECTED
            callback.onShutdown()
            return
        }
        sendAuthComplete()
        sendServiceDiscoveryRequest()
    }

    private fun handleServiceDiscoveryResponse(payload: ByteArray) {
        Log.w(TAG, "Received SERVICE_DISCOVERY_RESPONSE (${payload.size} bytes)")
        discoveredChannels = parseServiceDiscoveryResponse(payload)
        Log.w(TAG, "Discovered ${discoveredChannels.size} channels: ${discoveredChannels.map { it.channelId }}")
        state = ProtocolState.ACTIVE
        callback.onServiceDiscoveryResponse(discoveredChannels)
        callback.onActive()
        // Channel opening is handled by ProjectionService after a delay
    }

    private fun handleChannelOpenRequest(payload: ByteArray) {
        var priority = 0; var channelId = 0
        var i = 0
        while (i < payload.size) {
            val tag = payload[i].toInt() and 0xFF; val field = tag ushr 3; i++
            var value = 0; var shift = 0
            while (i < payload.size) {
                val b = payload[i].toInt() and 0xFF; i++
                value = value or ((b and 0x7F) shl shift)
                if (b and 0x80 == 0) break
                shift += 7
            }
            when (field) { 1 -> priority = value; 2 -> channelId = value }
        }
        Log.w(TAG, "Received CHANNEL_OPEN_REQUEST ch=$channelId priority=$priority")
        callback.onChannelOpenRequest(channelId, priority)
    }

    private fun handleChannelOpenResponse(payload: ByteArray) {
        val hex = payload.joinToString(" ") { String.format("%02x", it) }
        Log.w(TAG, "CHANNEL_OPEN_RESPONSE raw: [$hex]")
        var status = -1
        var i = 0
        while (i < payload.size) {
            val tag = payload[i].toInt() and 0xFF; i++
            val field = tag ushr 3
            val wireType = tag and 0x07
            if (wireType == 0) { // varint
                var value = 0; var shift = 0
                while (i < payload.size) {
                    val b = payload[i].toInt() and 0xFF; i++
                    value = value or ((b and 0x7F) shl shift)
                    if (b and 0x80 == 0) break
                    shift += 7
                }
                Log.w(TAG, "  field=$field value=$value")
                if (field == 1) status = value
            } else if (wireType == 2) { // length-delimited
                var len = 0; var shift = 0
                while (i < payload.size) {
                    val b = payload[i].toInt() and 0xFF; i++
                    len = len or ((b and 0x7F) shl shift)
                    if (b and 0x80 == 0) break
                    shift += 7
                }
                val subHex = payload.copyOfRange(i, minOf(i + len, payload.size)).joinToString(" ") { String.format("%02x", it) }
                Log.w(TAG, "  field=$field len=$len data=[$subHex]")
                i += len
            } else {
                Log.w(TAG, "  field=$field wireType=$wireType (skipping)")
                break
            }
        }
        val channelId = pendingChannelOpens.removeFirstOrNull() ?: -1
        Log.w(TAG, "CHANNEL_OPEN_RESPONSE for ch=$channelId status=$status")
        if (status == 0) {
            openedChannels.add(channelId)
            callback.onChannelOpened(channelId)
        } else {
            Log.w(TAG, "Channel $channelId open FAILED status=$status")
        }
    }

    private fun handleAudioFocusRequest(payload: ByteArray) {
        // AudioFocusRequest: field 1 (audio_focus_type) varint
        var focusType = 0
        var i = 0
        while (i < payload.size) {
            val tag = payload[i].toInt() and 0xFF; i++
            val field = tag ushr 3
            var value = 0; var shift = 0
            while (i < payload.size) {
                val b = payload[i].toInt() and 0xFF; i++
                value = value or ((b and 0x7F) shl shift)
                if (b and 0x80 == 0) break
                shift += 7
            }
            if (field == 1) focusType = value
        }
        Log.w(TAG, "AUDIO_FOCUS_REQUEST type=$focusType")
        callback.onAudioFocusRequest(focusType)

        // Respond: map request type to state
        // NONE=0, GAIN=1, GAIN_TRANSIENT=2, GAIN_NAVI=3, RELEASE=4
        // Response states: NONE=0, GAIN=1, GAIN_TRANSIENT=2, LOSS=3
        val responseState = when (focusType) {
            0 -> 0 // NONE → NONE
            1 -> 1 // GAIN → GAIN
            2 -> 2 // GAIN_TRANSIENT → GAIN_TRANSIENT
            3 -> 1 // GAIN_NAVI → GAIN
            4 -> 3 // RELEASE → LOSS
            else -> 0
        }
        sendAudioFocusResponse(responseState)
    }

    private fun handleNavigationFocusRequest(payload: ByteArray) {
        var type = 0
        var i = 0
        while (i < payload.size) {
            val tag = payload[i].toInt() and 0xFF; i++
            val field = tag ushr 3
            var value = 0; var shift = 0
            while (i < payload.size) {
                val b = payload[i].toInt() and 0xFF; i++
                value = value or ((b and 0x7F) shl shift)
                if (b and 0x80 == 0) break
                shift += 7
            }
            if (field == 1) type = value
        }
        Log.w(TAG, "NAVIGATION_FOCUS_REQUEST type=$type")
        callback.onNavigationFocusRequest(type)
        // Respond with type=2 (granted)
        sendNavigationFocusResponse(2)
    }

    private fun handleVoiceSessionRequest(payload: ByteArray) {
        var type = 0
        var i = 0
        while (i < payload.size) {
            val tag = payload[i].toInt() and 0xFF; i++
            val field = tag ushr 3
            var value = 0; var shift = 0
            while (i < payload.size) {
                val b = payload[i].toInt() and 0xFF; i++
                value = value or ((b and 0x7F) shl shift)
                if (b and 0x80 == 0) break
                shift += 7
            }
            if (field == 1) type = value
        }
        Log.w(TAG, "VOICE_SESSION_REQUEST type=$type (1=start, 2=stop)")
        callback.onVoiceSessionRequest(type)
    }

    private fun handlePingRequest(payload: ByteArray) {
        val msg = buildMessage(ControlMessageType.PING_RESPONSE, payload)
        callback.onSendFrame(CONTROL_CHANNEL, msg, control = false)
    }

    private fun handleShutdownRequest(payload: ByteArray) {
        Log.w(TAG, "Shutdown request received")
        val msg = buildMessage(ControlMessageType.SHUTDOWN_RESPONSE, ByteArray(0))
        callback.onSendFrame(CONTROL_CHANNEL, msg, control = true)
        state = ProtocolState.DISCONNECTED
        callback.onShutdown()
    }

    private fun handleServiceDiscoveryRequest(payload: ByteArray) {
        // Head unit is asking us for our channels (we're acting as server)
        Log.w(TAG, "Received SERVICE_DISCOVERY_REQUEST")
        callback.onServiceDiscoveryRequest("", "")
    }

    private fun handleSslHandshake(payload: ByteArray) {
        check(state == ProtocolState.TLS_HANDSHAKE) { "SSL handshake in wrong state: $state" }
        callback.onTlsData(payload)
    }

    private fun sendVersionRequest() {
        val payload = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
            .putShort(PROTOCOL_VERSION_MAJOR.toShort())
            .putShort(PROTOCOL_VERSION_MINOR.toShort())
            .array()
        val msg = buildMessage(ControlMessageType.VERSION_REQUEST, payload)
        Log.w(TAG, "Sending VERSION_REQUEST v$PROTOCOL_VERSION_MAJOR.$PROTOCOL_VERSION_MINOR")
        callback.onSendFrame(CONTROL_CHANNEL, msg, control = false)
    }

    private fun sendVersionResponse() {
        val payload = ByteBuffer.allocate(6).order(ByteOrder.BIG_ENDIAN)
            .putShort(PROTOCOL_VERSION_MAJOR.toShort())
            .putShort(PROTOCOL_VERSION_MINOR.toShort())
            .putShort(0) // status: compatible
            .array()
        val msg = buildMessage(ControlMessageType.VERSION_RESPONSE, payload)
        callback.onSendFrame(CONTROL_CHANNEL, msg, control = false)
    }

    private fun sendAuthComplete() {
        val protobufPayload = byteArrayOf(0x08, 0x00) // status = OK(0)
        val msg = buildMessage(ControlMessageType.AUTH_COMPLETE, protobufPayload)
        callback.onSendFrame(CONTROL_CHANNEL, msg, control = true)
    }

    private fun sendServiceDiscoveryRequest() {
        val name = deviceName.toByteArray()
        val brand = deviceBrand.toByteArray()
        val out = java.io.ByteArrayOutputStream()
        // field 4 (device_name) = string
        out.write((4 shl 3) or 2) // tag: field 4, wire type 2
        writeVarint(out, name.size.toLong())
        out.write(name)
        // field 5 (device_brand) = string
        out.write((5 shl 3) or 2) // tag: field 5, wire type 2
        writeVarint(out, brand.size.toLong())
        out.write(brand)
        // field 6 (phone_info) = PhoneInfo { instance_id = BT MAC }
        val btAddr = bluetoothAddress
        if (btAddr != null) {
            val phoneInfo = java.io.ByteArrayOutputStream()
            val idBytes = btAddr.toByteArray()
            phoneInfo.write((1 shl 3) or 2) // field 1 (instance_id), wire type 2
            writeVarint(phoneInfo, idBytes.size.toLong())
            phoneInfo.write(idBytes)
            val phoneInfoBytes = phoneInfo.toByteArray()
            out.write((6 shl 3) or 2) // field 6, wire type 2
            writeVarint(out, phoneInfoBytes.size.toLong())
            out.write(phoneInfoBytes)
        }
        val msg = buildMessage(ControlMessageType.SERVICE_DISCOVERY_REQUEST, out.toByteArray())
        Log.w(TAG, "Sending SERVICE_DISCOVERY_REQUEST")
        callback.onSendFrame(CONTROL_CHANNEL, msg, control = false)
    }

    /** Send VIDEO_FOCUS_INDICATION on a specific channel */
    fun sendVideoFocusIndication(channelId: Int, focused: Boolean) {
        val mode = if (focused) 1 else 2 // FOCUSED=1, UNFOCUSED=2
        val payload = byteArrayOf(0x08, mode.toByte(), 0x10, 0x00) // focus_mode, unrequested=false
        val msg = buildMessage(0x8008, payload) // VIDEO_FOCUS_INDICATION
        Log.w(TAG, "Sending VIDEO_FOCUS_INDICATION ch=$channelId focused=$focused")
        callback.onSendFrame(channelId.toUByte(), msg, control = false)
    }

    // --- Parsing ---

    private fun parseServiceDiscoveryResponse(data: ByteArray): List<ChannelDescriptor> {
        val channels = mutableListOf<ChannelDescriptor>()
        var i = 0
        while (i < data.size) {
            val tag = data[i].toInt() and 0xFF; i++
            val field = tag ushr 3
            val wireType = tag and 0x07
            when (wireType) {
                0 -> { // varint - skip
                    while (i < data.size && data[i].toInt() and 0x80 != 0) i++
                    i++
                }
                2 -> { // length-delimited
                    var len = 0; var shift = 0
                    while (i < data.size) {
                        val b = data[i].toInt() and 0xFF; i++
                        len = len or ((b and 0x7F) shl shift)
                        if (b and 0x80 == 0) break
                        shift += 7
                    }
                    if (field == 1 && i + len <= data.size) {
                        // ChannelDescriptor
                        parseChannelDescriptor(data.copyOfRange(i, i + len))?.let { channels.add(it) }
                    }
                    i += len
                }
                else -> break
            }
        }
        return channels
    }

    private fun parseChannelDescriptor(data: ByteArray): ChannelDescriptor? {
        var channelId = -1
        var hasSensor = false; var hasAv = false; var hasInput = false
        var hasAvInput = false; var hasNavigation = false; var hasBluetooth = false
        var i = 0
        while (i < data.size) {
            val tag = data[i].toInt() and 0xFF; i++
            val field = tag ushr 3
            val wireType = tag and 0x07
            when (wireType) {
                0 -> {
                    var value = 0; var shift = 0
                    while (i < data.size) {
                        val b = data[i].toInt() and 0xFF; i++
                        value = value or ((b and 0x7F) shl shift)
                        if (b and 0x80 == 0) break
                        shift += 7
                    }
                    if (field == 1) channelId = value
                }
                2 -> {
                    var len = 0; var shift = 0
                    while (i < data.size) {
                        val b = data[i].toInt() and 0xFF; i++
                        len = len or ((b and 0x7F) shl shift)
                        if (b and 0x80 == 0) break
                        shift += 7
                    }
                    when (field) {
                        2 -> hasSensor = true
                        3 -> hasAv = true
                        4 -> hasInput = true
                        5 -> hasAvInput = true
                        6 -> hasBluetooth = true
                        8 -> hasNavigation = true
                    }
                    i += len
                }
                else -> break
            }
        }
        if (channelId < 0) return null
        return ChannelDescriptor(channelId, hasSensor, hasAv, hasInput, hasAvInput, hasNavigation, hasBluetooth)
    }

    // --- Helpers ---

    private fun buildMessage(type: Int, payload: ByteArray): ByteArray {
        val buf = ByteBuffer.allocate(2 + payload.size).order(ByteOrder.BIG_ENDIAN)
        buf.putShort(type.toShort())
        buf.put(payload)
        return buf.array()
    }

    private fun writeVarint(out: java.io.ByteArrayOutputStream, value: Long) {
        var v = value
        while (v > 0x7F) {
            out.write(((v.toInt() and 0x7F) or 0x80))
            v = v ushr 7
        }
        out.write(v.toInt() and 0x7F)
    }
}

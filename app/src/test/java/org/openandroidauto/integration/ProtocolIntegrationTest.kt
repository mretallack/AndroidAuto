package org.openandroidauto.integration

import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.openandroidauto.channel.*
import org.openandroidauto.protocol.*
import org.openandroidauto.transport.TcpTransport
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Integration tests that verify the full protocol flow over TCP.
 * Based on the openauto/aasdk head unit implementation.
 *
 * Protocol flow (phone-side perspective):
 * 1. HU sends VERSION_REQUEST → Phone responds VERSION_RESPONSE
 * 2. TLS handshake (skipped in tests)
 * 3. HU sends AUTH_COMPLETE → Phone responds AUTH_COMPLETE + SERVICE_DISCOVERY_REQUEST
 * 4. HU responds SERVICE_DISCOVERY_RESPONSE
 * 5. Phone sends CHANNEL_OPEN_REQUEST on each target channel (with control flag)
 * 6. HU responds CHANNEL_OPEN_RESPONSE on each channel (with control flag)
 * 7. Phone sends MEDIA_MESSAGE_SETUP on video channel
 * 8. HU responds MEDIA_MESSAGE_CONFIG
 * 9. HU sends VIDEO_FOCUS_NOTIFICATION
 * 10. Phone sends MEDIA_MESSAGE_START
 * 11. Phone streams video data
 */
class ProtocolIntegrationTest {

    private lateinit var headUnit: HeadUnitSimulator
    private lateinit var transport: TcpTransport
    private lateinit var engine: ProtocolEngine
    private lateinit var cb: TestProtocolCallback

    class TestProtocolCallback : ProtocolCallback {
        val sentFrames = mutableListOf<Triple<UByte, ByteArray, Boolean>>()
        val tlsDataReceived = mutableListOf<ByteArray>()
        var activeCount = 0
        var shutdownCount = 0
        val openedChannels = mutableListOf<Int>()
        val discoveredChannels = mutableListOf<ChannelDescriptor>()
        var audioFocusType = -1
        var navFocusType = -1
        var voiceSessionType = -1

        override fun onSendFrame(channelId: UByte, payload: ByteArray, control: Boolean) {
            sentFrames.add(Triple(channelId, payload, control))
        }
        override fun onTlsData(data: ByteArray) { tlsDataReceived.add(data) }
        override fun onTlsComplete() {}
        override fun onServiceDiscoveryRequest(deviceName: String, deviceBrand: String) {}
        override fun onServiceDiscoveryResponse(channels: List<ChannelDescriptor>) {
            discoveredChannels.addAll(channels)
        }
        override fun onChannelOpenRequest(channelId: Int, priority: Int) {}
        override fun onChannelOpened(channelId: Int) { openedChannels.add(channelId) }
        override fun onActive() { activeCount++ }
        override fun onShutdown() { shutdownCount++ }
        override fun onAudioFocusRequest(focusType: Int) { audioFocusType = focusType }
        override fun onNavigationFocusRequest(type: Int) { navFocusType = type }
        override fun onVoiceSessionRequest(type: Int) { voiceSessionType = type }

        /** Find sent frame by channel and type */
        fun findSent(channelId: Int, messageType: Int): Triple<UByte, ByteArray, Boolean>? {
            return sentFrames.find { frame ->
                frame.first.toInt() == channelId && frame.second.size >= 2 &&
                    (((frame.second[0].toInt() and 0xFF) shl 8) or (frame.second[1].toInt() and 0xFF)) == messageType
            }
        }
    }

    @Before
    fun setUp() {
        headUnit = HeadUnitSimulator()
        cb = TestProtocolCallback()
        engine = ProtocolEngine(cb)
        transport = TcpTransport("127.0.0.1", headUnit.port)
    }

    @After
    fun tearDown() {
        runBlocking { transport.disconnect() }
        headUnit.close()
    }

    // --- Version Negotiation ---

    @Test
    fun `VERSION_REQUEST from head unit triggers VERSION_RESPONSE`() {
        engine.start()
        engine.onMessage(ControlMessageType.VERSION_REQUEST,
            ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putShort(1).putShort(5).array())

        assertEquals(ProtocolState.TLS_HANDSHAKE, engine.state)
        val sent = cb.findSent(0, ControlMessageType.VERSION_RESPONSE)
        assertNotNull("VERSION_RESPONSE should be sent", sent)
    }

    // --- Auth ---

    @Test
    fun `AUTH_COMPLETE triggers response and SERVICE_DISCOVERY_REQUEST`() {
        advanceToServiceDiscovery()

        // Should have sent AUTH_COMPLETE response
        val authSent = cb.findSent(0, ControlMessageType.AUTH_COMPLETE)
        assertNotNull("AUTH_COMPLETE response should be sent", authSent)

        // Should have sent SERVICE_DISCOVERY_REQUEST
        val discoverySent = cb.findSent(0, ControlMessageType.SERVICE_DISCOVERY_REQUEST)
        assertNotNull("SERVICE_DISCOVERY_REQUEST should be sent", discoverySent)
    }

    // --- Service Discovery ---

    @Test
    fun `SERVICE_DISCOVERY_RESPONSE parsed correctly`() {
        advanceToServiceDiscovery()
        cb.sentFrames.clear()

        // Simulate head unit's SERVICE_DISCOVERY_RESPONSE with video (ch1) and sensor (ch6)
        val response = buildServiceDiscoveryResponse()
        engine.onMessage(ControlMessageType.SERVICE_DISCOVERY_RESPONSE, response)

        assertEquals(ProtocolState.ACTIVE, engine.state)
        assertTrue(cb.discoveredChannels.isNotEmpty())
        assertTrue(cb.discoveredChannels.any { it.channelId == 1 && it.hasAv })
    }

    // --- Channel Open ---

    @Test
    fun `CHANNEL_OPEN_REQUEST sent on target channel with control flag`() {
        advanceToActive()
        cb.sentFrames.clear()

        engine.sendChannelOpenRequest(1)

        val sent = cb.sentFrames.find { it.first.toInt() == 1 }
        assertNotNull("Should send on channel 1", sent)
        assertTrue("Should have control flag", sent!!.third)
        val type = ((sent.second[0].toInt() and 0xFF) shl 8) or (sent.second[1].toInt() and 0xFF)
        assertEquals(ControlMessageType.CHANNEL_OPEN_REQUEST, type)
    }

    @Test
    fun `CHANNEL_OPEN_RESPONSE with STATUS_SUCCESS triggers onChannelOpened`() {
        advanceToActive()
        engine.sendChannelOpenRequest(1)

        // Head unit responds with status=0 (SUCCESS)
        engine.onMessage(ControlMessageType.CHANNEL_OPEN_RESPONSE, byteArrayOf(0x08, 0x00))

        assertTrue(cb.openedChannels.contains(1))
    }

    @Test
    fun `CHANNEL_OPEN_RESPONSE with STATUS_INVALID_CHANNEL does not trigger onChannelOpened`() {
        advanceToActive()
        engine.sendChannelOpenRequest(1)

        // Status -5 = STATUS_INVALID_CHANNEL encoded as sint32 varint
        val payload = byteArrayOf(0x08, 0xFB.toByte(), 0xFF.toByte(), 0xFF.toByte(),
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x01)
        engine.onMessage(ControlMessageType.CHANNEL_OPEN_RESPONSE, payload)

        assertTrue(cb.openedChannels.isEmpty())
    }

    // --- Audio Focus ---

    @Test
    fun `AUDIO_FOCUS_REQUEST GAIN triggers response with GAIN state`() {
        advanceToActive()
        cb.sentFrames.clear()

        // AudioFocusRequest: field 1 (audio_focus_type) = GAIN(1)
        engine.onMessage(ControlMessageType.AUDIO_FOCUS_REQUEST, byteArrayOf(0x08, 0x01))

        assertEquals(1, cb.audioFocusType)
        val resp = cb.findSent(0, ControlMessageType.AUDIO_FOCUS_RESPONSE)
        assertNotNull("Should send AUDIO_FOCUS_RESPONSE", resp)
    }

    @Test
    fun `AUDIO_FOCUS_REQUEST RELEASE triggers response with LOSS state`() {
        advanceToActive()
        cb.sentFrames.clear()

        // AudioFocusRequest: field 1 = RELEASE(4)
        engine.onMessage(ControlMessageType.AUDIO_FOCUS_REQUEST, byteArrayOf(0x08, 0x04))

        assertEquals(4, cb.audioFocusType)
        val resp = cb.findSent(0, ControlMessageType.AUDIO_FOCUS_RESPONSE)
        assertNotNull(resp)
        // Response should contain LOSS(3)
        val payload = resp!!.second.copyOfRange(2, resp.second.size)
        assertEquals(0x08.toByte(), payload[0])
        assertEquals(0x03.toByte(), payload[1]) // LOSS = 3
    }

    // --- Navigation Focus ---

    @Test
    fun `NAVIGATION_FOCUS_REQUEST triggers response with type 2`() {
        advanceToActive()
        cb.sentFrames.clear()

        engine.onMessage(ControlMessageType.NAVIGATION_FOCUS_REQUEST, byteArrayOf(0x08, 0x01))

        assertEquals(1, cb.navFocusType)
        val resp = cb.findSent(0, ControlMessageType.NAVIGATION_FOCUS_RESPONSE)
        assertNotNull(resp)
    }

    // --- Voice Session ---

    @Test
    fun `VOICE_SESSION_REQUEST notifies callback`() {
        advanceToActive()

        // type=1 means start
        engine.onMessage(ControlMessageType.VOICE_SESSION_REQUEST, byteArrayOf(0x08, 0x01))
        assertEquals(1, cb.voiceSessionType)
    }

    // --- Ping ---

    @Test
    fun `PING_REQUEST echoed as PING_RESPONSE with same payload`() {
        advanceToActive()
        cb.sentFrames.clear()

        val pingPayload = byteArrayOf(0x08, 0x80.toByte(), 0xC8.toByte(), 0x01) // timestamp varint
        engine.onMessage(ControlMessageType.PING_REQUEST, pingPayload)

        val resp = cb.findSent(0, ControlMessageType.PING_RESPONSE)
        assertNotNull(resp)
        assertArrayEquals(pingPayload, resp!!.second.copyOfRange(2, resp.second.size))
    }

    // --- Shutdown ---

    @Test
    fun `SHUTDOWN_REQUEST sends response and moves to DISCONNECTED`() {
        advanceToActive()

        engine.onMessage(ControlMessageType.SHUTDOWN_REQUEST, byteArrayOf(0x08, 0x01))

        assertEquals(ProtocolState.DISCONNECTED, engine.state)
        assertEquals(1, cb.shutdownCount)
        val resp = cb.findSent(0, ControlMessageType.SHUTDOWN_RESPONSE)
        assertNotNull(resp)
    }

    // --- Video Channel ---

    @Test
    fun `video channel responds to SETUP_REQUEST with SETUP_RESPONSE`() {
        val messages = mutableListOf<ByteArray>()
        val videoChannel = VideoChannel(1u, object : VideoChannelCallback {
            override fun onVideoFrame(channelId: UByte, payload: ByteArray) {}
            override fun onSendMessage(channelId: UByte, payload: ByteArray) { messages.add(payload) }
        })

        // SETUP_REQUEST with config_index=0 (old format) or MediaCodecType (new format)
        videoChannel.onMessage(AVMessageType.SETUP_REQUEST, byteArrayOf(0x08, 0x00))

        assertEquals(1, messages.size)
        val type = ((messages[0][0].toInt() and 0xFF) shl 8) or (messages[0][1].toInt() and 0xFF)
        assertEquals(AVMessageType.SETUP_RESPONSE, type)
    }

    @Test
    fun `video channel responds to VIDEO_FOCUS_REQUEST with FOCUS_INDICATION`() {
        val messages = mutableListOf<ByteArray>()
        val videoChannel = VideoChannel(1u, object : VideoChannelCallback {
            override fun onVideoFrame(channelId: UByte, payload: ByteArray) {}
            override fun onSendMessage(channelId: UByte, payload: ByteArray) { messages.add(payload) }
        })

        // VideoFocusRequest: mode=PROJECTED(1), reason=0
        videoChannel.onMessage(AVMessageType.VIDEO_FOCUS_REQUEST, byteArrayOf(0x10, 0x01, 0x18, 0x00))

        assertEquals(1, messages.size)
        val type = ((messages[0][0].toInt() and 0xFF) shl 8) or (messages[0][1].toInt() and 0xFF)
        assertEquals(AVMessageType.VIDEO_FOCUS_INDICATION, type)
    }

    // --- Input Channel ---

    @Test
    fun `input channel responds to BINDING_REQUEST with BINDING_RESPONSE OK`() {
        val messages = mutableListOf<ByteArray>()
        val inputChannel = InputChannel(2u, object : InputChannelCallback {
            override fun onTouchEvent(event: InputEvent) {}
            override fun onKeyEvent(event: KeyEvent) {}
            override fun onSendMessage(channelId: UByte, payload: ByteArray) { messages.add(payload) }
        })

        inputChannel.onMessage(InputMessageType.BINDING_REQUEST, byteArrayOf(0x08, 0x01))

        assertEquals(1, messages.size)
        val type = ((messages[0][0].toInt() and 0xFF) shl 8) or (messages[0][1].toInt() and 0xFF)
        assertEquals(InputMessageType.BINDING_RESPONSE, type)
    }

    @Test
    fun `input channel parses touch event correctly`() {
        val events = mutableListOf<InputEvent>()
        val inputChannel = InputChannel(2u, object : InputChannelCallback {
            override fun onTouchEvent(event: InputEvent) { events.add(event) }
            override fun onKeyEvent(event: KeyEvent) {}
            override fun onSendMessage(channelId: UByte, payload: ByteArray) {}
        })

        // InputEventIndication with touch at (50, 30), action=PRESS
        val touchLocation = byteArrayOf(0x08, 50, 0x10, 30, 0x18, 0x00)
        val touchEvent = byteArrayOf(0x0A, touchLocation.size.toByte()) + touchLocation + byteArrayOf(0x18, 0x00)
        val inputEvent = byteArrayOf(0x08, 0x01, 0x1A, touchEvent.size.toByte()) + touchEvent

        inputChannel.onMessage(InputMessageType.INPUT_EVENT_INDICATION, inputEvent)

        assertEquals(1, events.size)
        assertEquals(50, events[0].touchPoints[0].x)
        assertEquals(30, events[0].touchPoints[0].y)
        assertEquals(TouchAction.PRESS, events[0].action)
    }

    // --- Sensor Channel ---

    @Test
    fun `sensor channel responds to SENSOR_START_REQUEST with response and initial data`() {
        val messages = mutableListOf<ByteArray>()
        val sensorChannel = SensorChannel(6u, object : SensorChannelCallback {
            override fun onSendMessage(channelId: UByte, payload: ByteArray) { messages.add(payload) }
        })

        // SENSOR_START_REQUEST: type=NIGHT_DATA(10), interval=1000
        val request = byteArrayOf(0x08, 0x0A, 0x10, 0xE8.toByte(), 0x07) // type=10, interval=1000
        sensorChannel.onMessage(SensorMessageType.SENSOR_START_REQUEST, request)

        // Should send SENSOR_START_RESPONSE + SENSOR_BATCH
        assertTrue(messages.size >= 2)
        val respType = ((messages[0][0].toInt() and 0xFF) shl 8) or (messages[0][1].toInt() and 0xFF)
        assertEquals(SensorMessageType.SENSOR_START_RESPONSE, respType)
        val eventType = ((messages[1][0].toInt() and 0xFF) shl 8) or (messages[1][1].toInt() and 0xFF)
        assertEquals(SensorMessageType.SENSOR_BATCH, eventType)
    }

    // --- MESSAGE_UNEXPECTED_MESSAGE (0xFF) ---

    @Test
    fun `MESSAGE_UNEXPECTED_MESSAGE (0xFF) is handled without crash`() {
        advanceToActive()
        // Should not throw
        engine.onMessage(0x00FF, ByteArray(0))
    }

    // --- Video Start Flow ---

    @Test
    fun `video flow - setup config focus start`() {
        val messages = mutableListOf<ByteArray>()
        val frames = mutableListOf<ByteArray>()
        val videoChannel = VideoChannel(1u, object : VideoChannelCallback {
            override fun onVideoFrame(channelId: UByte, payload: ByteArray) { frames.add(payload) }
            override fun onSendMessage(channelId: UByte, payload: ByteArray) { messages.add(payload) }
        })

        // 1. Phone sends SETUP
        videoChannel.sendSetup()
        assertEquals(VideoState.SETUP_SENT, videoChannel.state)
        assertEquals(1, messages.size)
        val setupType = ((messages[0][0].toInt() and 0xFF) shl 8) or (messages[0][1].toInt() and 0xFF)
        assertEquals(AVMessageType.SETUP_REQUEST, setupType)

        // 2. Head unit responds with CONFIG (STATUS_READY, max_unacked=100)
        videoChannel.onMessage(AVMessageType.SETUP_RESPONSE, byteArrayOf(0x08, 0x02, 0x10, 0x64, 0x18, 0x00))
        assertEquals(VideoState.CONFIGURED, videoChannel.state)
        assertEquals(100, videoChannel.maxUnacked)

        // 3. Head unit sends VIDEO_FOCUS_NOTIFICATION (PROJECTED)
        messages.clear()
        videoChannel.onMessage(AVMessageType.VIDEO_FOCUS_INDICATION, byteArrayOf(0x08, 0x01, 0x10, 0x01))
        assertEquals(VideoState.STARTED, videoChannel.state)

        // 4. Verify START was sent
        val startMsg = messages.find {
            val t = ((it[0].toInt() and 0xFF) shl 8) or (it[1].toInt() and 0xFF)
            t == AVMessageType.START_INDICATION
        }
        assertNotNull("START_INDICATION should be sent", startMsg)

        // 5. After a brief wait, black frames should be generated (no MediaProjection)
        Thread.sleep(100)
        assertTrue("Should have sent video frames", frames.isNotEmpty())

        // 6. Verify frame format: [type:2][timestamp:8][data:N]
        val frame = frames[0]
        val buf = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN)
        assertEquals(AVMessageType.AV_MEDIA_WITH_TIMESTAMP, buf.short.toInt() and 0xFFFF)
        val timestamp = buf.long
        assertTrue(timestamp >= 0)

        // Cleanup
        videoChannel.stop()
    }

    // --- Sensor Channel ---

    @Test
    fun `sensor channel - request sensors and receive batch`() {
        val messages = mutableListOf<ByteArray>()
        val sensorChannel = SensorChannel(6u, object : SensorChannelCallback {
            override fun onSendMessage(channelId: UByte, payload: ByteArray) { messages.add(payload) }
        })

        // 1. Request sensors (simulates what happens after channel opens)
        sensorChannel.requestSensors()
        assertEquals(2, messages.size) // DRIVING_STATUS + NIGHT_MODE requests

        // 2. Head unit responds with SENSOR_START_RESPONSE (OK)
        sensorChannel.onMessage(SensorMessageType.SENSOR_START_RESPONSE, byteArrayOf(0x08, 0x00))
        sensorChannel.onMessage(SensorMessageType.SENSOR_START_RESPONSE, byteArrayOf(0x08, 0x00))

        // 3. Head unit sends SENSOR_BATCH with night mode = false
        val nightData = byteArrayOf(0x08, 0x00) // night_mode = false
        val batch = byteArrayOf((10 shl 3 or 2).toByte(), nightData.size.toByte()) + nightData
        sensorChannel.onMessage(SensorMessageType.SENSOR_BATCH, batch)

        assertFalse(sensorChannel.isNight)

        // 4. Head unit sends SENSOR_BATCH with driving status = UNRESTRICTED
        val statusData = byteArrayOf(0x08, 0x00)
        val statusBatch = byteArrayOf((13 shl 3 or 2).toByte(), statusData.size.toByte()) + statusData
        sensorChannel.onMessage(SensorMessageType.SENSOR_BATCH, statusBatch)

        assertEquals(0, sensorChannel.drivingStatus)
    }

    @Test
    fun `sensor channel - responds to head unit sensor start request`() {
        val messages = mutableListOf<ByteArray>()
        val sensorChannel = SensorChannel(6u, object : SensorChannelCallback {
            override fun onSendMessage(channelId: UByte, payload: ByteArray) { messages.add(payload) }
        })

        // Head unit asks us for NIGHT_MODE data
        sensorChannel.onMessage(SensorMessageType.SENSOR_START_REQUEST, byteArrayOf(0x08, 0x0A, 0x10, 0x00))

        // Should respond with SENSOR_START_RESPONSE + SENSOR_BATCH
        assertTrue(messages.size >= 2)
        val type0 = ((messages[0][0].toInt() and 0xFF) shl 8) or (messages[0][1].toInt() and 0xFF)
        val type1 = ((messages[1][0].toInt() and 0xFF) shl 8) or (messages[1][1].toInt() and 0xFF)
        assertEquals(SensorMessageType.SENSOR_START_RESPONSE, type0)
        assertEquals(SensorMessageType.SENSOR_BATCH, type1)
    }

    // --- Full Flow Test ---

    @Test
    fun `full flow - version through channel open`() = runBlocking {
        val connectJob = launch(Dispatchers.IO) { transport.connect() }
        headUnit.acceptConnection()
        connectJob.join()

        engine.start()

        // HU sends VERSION_REQUEST
        headUnit.sendControlMessage(ControlMessageType.VERSION_REQUEST,
            ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putShort(1).putShort(5).array())

        // Read and process
        processIncoming()
        assertEquals(ProtocolState.TLS_HANDSHAKE, engine.state)

        // Write the VERSION_RESPONSE that engine generated back to transport
        val versionRespFrame = cb.sentFrames.find { frame ->
            frame.second.size >= 2 &&
                (((frame.second[0].toInt() and 0xFF) shl 8) or (frame.second[1].toInt() and 0xFF)) == ControlMessageType.VERSION_RESPONSE
        }
        assertNotNull(versionRespFrame)
        val encoded = MessageFramer.encode(versionRespFrame!!.first, versionRespFrame.second, versionRespFrame.third)
        for (frame in encoded) {
            transport.write(ByteBuffer.wrap(frame))
        }

        // HU should have received VERSION_RESPONSE
        val versionResp = headUnit.readFrame()
        assertNotNull(versionResp)
        assertEquals(ControlMessageType.VERSION_RESPONSE, versionResp!!.messageType)
    }

    // --- Helpers ---

    private fun advanceToServiceDiscovery() {
        engine.start()
        engine.onMessage(ControlMessageType.VERSION_REQUEST,
            ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putShort(1).putShort(5).array())
        engine.onTlsHandshakeComplete()
        // AUTH_COMPLETE from head unit: status=0
        engine.onMessage(ControlMessageType.AUTH_COMPLETE, byteArrayOf(0x08, 0x00))
    }

    private fun advanceToActive() {
        advanceToServiceDiscovery()
        val response = buildServiceDiscoveryResponse()
        engine.onMessage(ControlMessageType.SERVICE_DISCOVERY_RESPONSE, response)
        assertEquals(ProtocolState.ACTIVE, engine.state)
    }

    /** Build a minimal SERVICE_DISCOVERY_RESPONSE with video(1), input(2), sensor(6) channels */
    private fun buildServiceDiscoveryResponse(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        // Channel 1: video (av_channel, field 3)
        val videoCh = buildChannelDescriptor(1, avChannel = true)
        writeField(out, 1, videoCh)
        // Channel 2: input (input_channel, field 4)
        val inputCh = buildChannelDescriptor(2, inputChannel = true)
        writeField(out, 1, inputCh)
        // Channel 6: sensor (sensor_channel, field 2)
        val sensorCh = buildChannelDescriptor(6, sensorChannel = true)
        writeField(out, 1, sensorCh)
        return out.toByteArray()
    }

    private fun buildChannelDescriptor(id: Int, avChannel: Boolean = false,
                                        inputChannel: Boolean = false, sensorChannel: Boolean = false): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        // field 1: channel_id
        out.write(0x08); out.write(id)
        // field 2: sensor_channel (if present)
        if (sensorChannel) { out.write(0x12); out.write(0x00) }
        // field 3: av_channel (if present)
        if (avChannel) { out.write(0x1A); out.write(0x00) }
        // field 4: input_channel (if present)
        if (inputChannel) { out.write(0x22); out.write(0x00) }
        return out.toByteArray()
    }

    private fun writeField(out: java.io.ByteArrayOutputStream, fieldNum: Int, value: ByteArray) {
        out.write((fieldNum shl 3) or 2)
        out.write(value.size)
        out.write(value)
    }

    private suspend fun processIncoming() {
        val readBuf = ByteBuffer.allocate(4096)
        transport.read(readBuf)
        readBuf.flip()
        val decoder = MessageFramer.Decoder()
        val messages = decoder.decode(readBuf)
        for (msg in messages) {
            if (msg.payload.size >= 2) {
                val type = ((msg.payload[0].toInt() and 0xFF) shl 8) or (msg.payload[1].toInt() and 0xFF)
                engine.onMessage(type, if (msg.payload.size > 2) msg.payload.copyOfRange(2, msg.payload.size) else ByteArray(0))
            }
        }
    }
}

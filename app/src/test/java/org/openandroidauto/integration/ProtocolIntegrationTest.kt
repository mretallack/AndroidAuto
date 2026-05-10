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
 * A HeadUnitSimulator acts as the head unit, our ProtocolEngine acts as the phone.
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

        override fun onSendFrame(channelId: UByte, payload: ByteArray, control: Boolean) {
            sentFrames.add(Triple(channelId, payload, control))
        }
        override fun onTlsData(data: ByteArray) { tlsDataReceived.add(data) }
        override fun onTlsComplete() {}
        override fun onServiceDiscoveryRequest(deviceName: String, deviceBrand: String) {}
        override fun onChannelOpenRequest(channelId: Int, priority: Int) {}
        override fun onActive() { activeCount++ }
        override fun onShutdown() { shutdownCount++ }
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

    @Test
    fun `full handshake - version negotiation to service discovery`() = runBlocking {
        // Connect phone to head unit
        val connectJob = launch(Dispatchers.IO) { transport.connect() }
        headUnit.acceptConnection()
        connectJob.join()
        assertTrue(transport.isConnected)

        // Phone starts protocol (waits for VERSION_REQUEST)
        engine.start()
        assertEquals(ProtocolState.VERSION_NEGOTIATION, engine.state)

        // Head unit sends VERSION_REQUEST
        headUnit.sendControlMessage(ControlMessageType.VERSION_REQUEST,
            ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putShort(1).putShort(5).array())

        // Phone reads and processes
        val readBuf = ByteBuffer.allocate(1024)
        transport.read(readBuf)
        readBuf.flip()

        val decoder = MessageFramer.Decoder()
        val messages = decoder.decode(readBuf)
        assertEquals(1, messages.size)

        val msgPayload = messages[0].payload
        val msgType = ((msgPayload[0].toInt() and 0xFF) shl 8) or (msgPayload[1].toInt() and 0xFF)
        engine.onMessage(msgType, msgPayload.copyOfRange(2, msgPayload.size))

        // Should now be in TLS_HANDSHAKE state and have sent VERSION_RESPONSE
        assertEquals(ProtocolState.TLS_HANDSHAKE, engine.state)
        assertEquals(1, cb.sentFrames.size)
        val sentMsg = cb.sentFrames[0].second
        val sentType = ((sentMsg[0].toInt() and 0xFF) shl 8) or (sentMsg[1].toInt() and 0xFF)
        assertEquals(ControlMessageType.VERSION_RESPONSE, sentType)

        // Skip TLS for this test - simulate completion
        engine.onTlsHandshakeComplete()
        assertEquals(ProtocolState.SERVICE_DISCOVERY, engine.state)
    }

    @Test
    fun `video channel - setup and frame exchange`() = runBlocking {
        // Set up connection
        val connectJob = launch(Dispatchers.IO) { transport.connect() }
        headUnit.acceptConnection()
        connectJob.join()

        // Create video channel
        val videoFrames = mutableListOf<ByteArray>()
        val videoMessages = mutableListOf<ByteArray>()
        val videoChannel = VideoChannel(1u, object : VideoChannelCallback {
            override fun onVideoFrame(channelId: UByte, payload: ByteArray) {
                videoFrames.add(payload)
            }
            override fun onSendMessage(channelId: UByte, payload: ByteArray) {
                videoMessages.add(payload)
            }
        })

        // Head unit sends SETUP_REQUEST on video channel
        val setupPayload = byteArrayOf(0x08, 0x00) // config_index = 0
        headUnit.sendChannelMessage(1u, AVMessageType.SETUP_REQUEST, setupPayload)

        // Phone reads and processes
        val readBuf = ByteBuffer.allocate(1024)
        transport.read(readBuf)
        readBuf.flip()

        val decoder = MessageFramer.Decoder()
        val messages = decoder.decode(readBuf)
        assertEquals(1, messages.size)

        val msgPayload = messages[0].payload
        val msgType = ((msgPayload[0].toInt() and 0xFF) shl 8) or (msgPayload[1].toInt() and 0xFF)
        videoChannel.onMessage(msgType, msgPayload.copyOfRange(2, msgPayload.size))

        // Verify SETUP_RESPONSE was generated
        assertEquals(1, videoMessages.size)
        val respType = ((videoMessages[0][0].toInt() and 0xFF) shl 8) or (videoMessages[0][1].toInt() and 0xFF)
        assertEquals(AVMessageType.SETUP_RESPONSE, respType)

        // Send the response back to head unit
        transport.write(ByteBuffer.wrap(
            MessageFramer.encode(1u, videoMessages[0], control = false)[0]
        ))

        // Head unit reads SETUP_RESPONSE
        val setupResp = headUnit.readFrame()
        assertNotNull(setupResp)
        assertEquals(AVMessageType.SETUP_RESPONSE, setupResp!!.messageType)

        // Verify response contains status=OK(2)
        assertTrue(setupResp.payload.size >= 2)
        assertEquals(0x08.toByte(), setupResp.payload[0])
        assertEquals(0x02.toByte(), setupResp.payload[1]) // OK
    }

    @Test
    fun `input channel - receive touch event and parse coordinates`() = runBlocking {
        // Set up connection
        val connectJob = launch(Dispatchers.IO) { transport.connect() }
        headUnit.acceptConnection()
        connectJob.join()

        // Create input channel
        val touchEvents = mutableListOf<InputEvent>()
        val inputChannel = InputChannel(2u, object : InputChannelCallback {
            override fun onTouchEvent(event: InputEvent) { touchEvents.add(event) }
            override fun onKeyEvent(event: KeyEvent) {}
            override fun onSendMessage(channelId: UByte, payload: ByteArray) {}
        })

        // Head unit sends touch event: x=100, y=50, action=PRESS
        headUnit.sendTouchEvent(2u, x = 100, y = 50, action = 0, pointerId = 0)

        // Phone reads and processes
        val readBuf = ByteBuffer.allocate(1024)
        transport.read(readBuf)
        readBuf.flip()

        val decoder = MessageFramer.Decoder()
        val messages = decoder.decode(readBuf)
        assertEquals(1, messages.size)

        val msgPayload = messages[0].payload
        val msgType = ((msgPayload[0].toInt() and 0xFF) shl 8) or (msgPayload[1].toInt() and 0xFF)
        assertEquals(InputMessageType.INPUT_EVENT_INDICATION, msgType)

        inputChannel.onMessage(msgType, msgPayload.copyOfRange(2, msgPayload.size))

        // Verify touch event was parsed correctly
        assertEquals(1, touchEvents.size)
        val event = touchEvents[0]
        assertEquals(100, event.touchPoints[0].x)
        assertEquals(50, event.touchPoints[0].y)
        assertEquals(0, event.touchPoints[0].pointerId)
        assertEquals(TouchAction.PRESS, event.action)
    }

    @Test
    fun `ping request echoed as ping response`() = runBlocking {
        val connectJob = launch(Dispatchers.IO) { transport.connect() }
        headUnit.acceptConnection()
        connectJob.join()

        // Advance engine to active state
        engine.start()
        cb.sentFrames.clear()

        // Simulate full handshake completion
        engine.onMessage(ControlMessageType.VERSION_RESPONSE, ByteArray(6))
        engine.onTlsHandshakeComplete()
        engine.sendServiceDiscoveryResponse(ByteArray(0))
        assertEquals(ProtocolState.ACTIVE, engine.state)

        // Head unit sends PING_REQUEST
        val timestamp = byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x30, 0x39) // 12345
        headUnit.sendControlMessage(ControlMessageType.PING_REQUEST, timestamp)

        // Phone reads and processes
        val readBuf = ByteBuffer.allocate(1024)
        transport.read(readBuf)
        readBuf.flip()

        val decoder = MessageFramer.Decoder()
        val messages = decoder.decode(readBuf)
        assertEquals(1, messages.size)

        val msgPayload = messages[0].payload
        val msgType = ((msgPayload[0].toInt() and 0xFF) shl 8) or (msgPayload[1].toInt() and 0xFF)
        engine.onMessage(msgType, msgPayload.copyOfRange(2, msgPayload.size))

        // Verify PING_RESPONSE was generated with same timestamp
        val pingResp = cb.sentFrames.last()
        val respPayload = pingResp.second
        val respType = ((respPayload[0].toInt() and 0xFF) shl 8) or (respPayload[1].toInt() and 0xFF)
        assertEquals(ControlMessageType.PING_RESPONSE, respType)

        // Timestamp should be echoed back
        val respTimestamp = respPayload.copyOfRange(2, respPayload.size)
        assertArrayEquals(timestamp, respTimestamp)
    }
}

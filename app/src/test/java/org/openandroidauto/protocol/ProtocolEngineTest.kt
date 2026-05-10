package org.openandroidauto.protocol

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ProtocolEngineTest {

    private lateinit var engine: ProtocolEngine
    private lateinit var cb: TestCallback

    class TestCallback : ProtocolCallback {
        val sentFrames = mutableListOf<Triple<UByte, ByteArray, Boolean>>()
        val tlsDataReceived = mutableListOf<ByteArray>()
        var tlsCompleted = false
        var activeCount = 0
        var shutdownCount = 0
        var discoveryRequests = mutableListOf<Pair<String, String>>()
        var channelOpenRequests = mutableListOf<Pair<Int, Int>>()

        override fun onSendFrame(channelId: UByte, payload: ByteArray, control: Boolean) {
            sentFrames.add(Triple(channelId, payload, control))
        }
        override fun onTlsData(data: ByteArray) { tlsDataReceived.add(data) }
        override fun onTlsComplete() { tlsCompleted = true }
        override fun onServiceDiscoveryRequest(deviceName: String, deviceBrand: String) {
            discoveryRequests.add(deviceName to deviceBrand)
        }
        override fun onChannelOpenRequest(channelId: Int, priority: Int) {
            channelOpenRequests.add(channelId to priority)
        }
        override fun onActive() { activeCount++ }
        override fun onShutdown() { shutdownCount++ }
    }

    @Before
    fun setUp() {
        cb = TestCallback()
        engine = ProtocolEngine(cb)
    }

    @Test
    fun `initial state is IDLE`() {
        assertEquals(ProtocolState.IDLE, engine.state)
    }

    @Test
    fun `start sends VERSION_REQUEST and moves to VERSION_NEGOTIATION`() {
        engine.start()
        assertEquals(ProtocolState.VERSION_NEGOTIATION, engine.state)
        assertEquals(1, cb.sentFrames.size)
        // Verify message type is VERSION_REQUEST (0x0001)
        val msg = cb.sentFrames[0].second
        val type = ByteBuffer.wrap(msg).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF
        assertEquals(ControlMessageType.VERSION_REQUEST, type)
    }

    @Test(expected = IllegalStateException::class)
    fun `start throws if not IDLE`() {
        engine.start()
        engine.start() // should throw
    }

    @Test
    fun `VERSION_RESPONSE transitions to TLS_HANDSHAKE`() {
        engine.start()
        // Send version response: major=1, minor=1, status=0
        val payload = ByteBuffer.allocate(6).order(ByteOrder.BIG_ENDIAN)
            .putShort(1).putShort(1).putShort(0).array()
        engine.onMessage(ControlMessageType.VERSION_RESPONSE, payload)
        assertEquals(ProtocolState.TLS_HANDSHAKE, engine.state)
    }

    @Test
    fun `SSL_HANDSHAKE data forwarded to callback`() {
        engine.start()
        engine.onMessage(ControlMessageType.VERSION_RESPONSE, ByteArray(6))
        val tlsData = byteArrayOf(0x16, 0x03, 0x01) // TLS ClientHello start
        engine.onMessage(ControlMessageType.SSL_HANDSHAKE, tlsData)
        assertEquals(1, cb.tlsDataReceived.size)
        assertArrayEquals(tlsData, cb.tlsDataReceived[0])
    }

    @Test
    fun `onTlsHandshakeComplete sends AUTH_COMPLETE and moves to SERVICE_DISCOVERY`() {
        engine.start()
        engine.onMessage(ControlMessageType.VERSION_RESPONSE, ByteArray(6))
        engine.onTlsHandshakeComplete()
        assertEquals(ProtocolState.SERVICE_DISCOVERY, engine.state)
        // Should have sent AUTH_COMPLETE
        val authMsg = cb.sentFrames.last().second
        val type = ByteBuffer.wrap(authMsg).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF
        assertEquals(ControlMessageType.AUTH_COMPLETE, type)
    }

    @Test(expected = IllegalStateException::class)
    fun `onTlsHandshakeComplete throws in wrong state`() {
        engine.start()
        engine.onTlsHandshakeComplete() // still in VERSION_NEGOTIATION
    }

    @Test
    fun `SERVICE_DISCOVERY_REQUEST triggers callback`() {
        advanceToServiceDiscovery()
        engine.onMessage(ControlMessageType.SERVICE_DISCOVERY_REQUEST, ByteArray(0))
        assertEquals(1, cb.discoveryRequests.size)
    }

    @Test
    fun `sendServiceDiscoveryResponse moves to ACTIVE`() {
        advanceToServiceDiscovery()
        engine.sendServiceDiscoveryResponse(ByteArray(0))
        assertEquals(ProtocolState.ACTIVE, engine.state)
        assertEquals(1, cb.activeCount)
    }

    @Test
    fun `PING_REQUEST echoed as PING_RESPONSE`() {
        advanceToActive()
        val timestamp = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(12345L).array()
        engine.onMessage(ControlMessageType.PING_REQUEST, timestamp)
        val lastMsg = cb.sentFrames.last().second
        val type = ByteBuffer.wrap(lastMsg).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF
        assertEquals(ControlMessageType.PING_RESPONSE, type)
    }

    @Test
    fun `SHUTDOWN_REQUEST sends response and disconnects`() {
        advanceToActive()
        engine.onMessage(ControlMessageType.SHUTDOWN_REQUEST, byteArrayOf(0x00, 0x01))
        assertEquals(ProtocolState.DISCONNECTED, engine.state)
        assertEquals(1, cb.shutdownCount)
        val lastMsg = cb.sentFrames.last().second
        val type = ByteBuffer.wrap(lastMsg).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF
        assertEquals(ControlMessageType.SHUTDOWN_RESPONSE, type)
    }

    @Test
    fun `CHANNEL_OPEN_REQUEST triggers callback in ACTIVE state`() {
        advanceToActive()
        val payload = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
            .putInt(1).putInt(5).array()
        engine.onMessage(ControlMessageType.CHANNEL_OPEN_REQUEST, payload)
        assertEquals(1, cb.channelOpenRequests.size)
    }

    @Test(expected = IllegalStateException::class)
    fun `VERSION_RESPONSE rejected in wrong state`() {
        // Don't call start(), state is IDLE
        engine.onMessage(ControlMessageType.VERSION_RESPONSE, ByteArray(6))
    }

    @Test
    fun `shutdown sends request and moves to DISCONNECTED`() {
        advanceToActive()
        engine.shutdown()
        assertEquals(ProtocolState.DISCONNECTED, engine.state)
        assertEquals(1, cb.shutdownCount)
    }

    // Helpers
    private fun advanceToServiceDiscovery() {
        engine.start()
        engine.onMessage(ControlMessageType.VERSION_RESPONSE, ByteArray(6))
        engine.onTlsHandshakeComplete()
    }

    private fun advanceToActive() {
        advanceToServiceDiscovery()
        engine.sendServiceDiscoveryResponse(ByteArray(0))
    }
}

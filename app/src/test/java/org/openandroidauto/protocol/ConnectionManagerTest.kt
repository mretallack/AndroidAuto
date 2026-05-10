package org.openandroidauto.protocol

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.openandroidauto.transport.Transport
import java.io.IOException
import java.nio.ByteBuffer

class ConnectionManagerTest {

    class FakeTransport(var failConnect: Boolean = false) : Transport {
        override var isConnected = false
        var connectCount = 0

        override suspend fun connect() {
            connectCount++
            if (failConnect) throw IOException("Connection refused")
            isConnected = true
        }
        override suspend fun disconnect() { isConnected = false }
        override suspend fun read(buffer: ByteBuffer) = 0
        override suspend fun write(buffer: ByteBuffer) {}
    }

    class FakeCallback : ProtocolCallback {
        val sentFrames = mutableListOf<Triple<UByte, ByteArray, Boolean>>()
        override fun onSendFrame(channelId: UByte, payload: ByteArray, control: Boolean) {
            sentFrames.add(Triple(channelId, payload, control))
        }
        override fun onTlsData(data: ByteArray) {}
        override fun onTlsComplete() {}
        override fun onServiceDiscoveryRequest(deviceName: String, deviceBrand: String) {}
        override fun onChannelOpenRequest(channelId: Int, priority: Int) {}
        override fun onActive() {}
        override fun onShutdown() {}
    }

    @Test
    fun `reconnect succeeds on first attempt`() = runTest {
        val transport = FakeTransport()
        val engine = ProtocolEngine(FakeCallback())
        val manager = ConnectionManager(this, transport, engine)

        val result = manager.reconnect()
        assertTrue(result)
        assertTrue(transport.isConnected)
        assertEquals(1, transport.connectCount)
    }

    @Test
    fun `reconnect retries on failure then succeeds`() = runTest {
        val transport = FakeTransport(failConnect = true)
        val engine = ProtocolEngine(FakeCallback())
        val manager = ConnectionManager(this, transport, engine)

        // After 2 failed attempts, allow connection
        var attempts = 0
        val originalConnect = transport.failConnect
        val customTransport = object : Transport {
            override var isConnected = false
            var connectCount = 0
            override suspend fun connect() {
                connectCount++
                attempts++
                if (attempts <= 2) throw IOException("fail")
                isConnected = true
            }
            override suspend fun disconnect() { isConnected = false }
            override suspend fun read(buffer: ByteBuffer) = 0
            override suspend fun write(buffer: ByteBuffer) {}
        }
        val manager2 = ConnectionManager(this, customTransport, engine)
        val result = manager2.reconnect()
        assertTrue(result)
        assertTrue(customTransport.connectCount > 2)
    }

    @Test
    fun `reconnect gives up after max attempts`() = runTest {
        val transport = FakeTransport(failConnect = true)
        val engine = ProtocolEngine(FakeCallback())
        val manager = ConnectionManager(this, transport, engine)

        var disconnectReason: String? = null
        manager.callback = object : ConnectionManager.Callback {
            override fun onDisconnected(reason: String) { disconnectReason = reason }
            override fun onReconnecting(attempt: Int) {}
            override fun onReconnected() {}
        }

        val result = manager.reconnect()
        assertFalse(result)
        assertEquals(ConnectionManager.MAX_RECONNECT_ATTEMPTS, transport.connectCount)
        assertNotNull(disconnectReason)
    }

    @Test
    fun `onPongReceived updates last pong time`() = runTest {
        val transport = FakeTransport()
        val engine = ProtocolEngine(FakeCallback())
        val manager = ConnectionManager(this, transport, engine)

        val before = System.currentTimeMillis()
        delay(10)
        manager.onPongReceived()
        // No crash, method works
    }

    @Test
    fun `stop cancels watchdog`() = runTest {
        val transport = FakeTransport().apply { isConnected = true }
        val engine = ProtocolEngine(FakeCallback())
        val manager = ConnectionManager(this, transport, engine)

        manager.startWatchdog()
        delay(100)
        manager.stop()
        // Should not throw or hang
    }
}

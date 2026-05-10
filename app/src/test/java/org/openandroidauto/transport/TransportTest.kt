package org.openandroidauto.transport

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.nio.ByteBuffer

class MockTransport : Transport {
    private var buffer = ByteArray(0)
    override var isConnected: Boolean = false
        private set

    override suspend fun connect() { isConnected = true }
    override suspend fun disconnect() { isConnected = false }

    override suspend fun read(buffer: ByteBuffer): Int {
        if (!isConnected) throw IOException("Not connected")
        if (this.buffer.isEmpty()) return 0
        val len = minOf(buffer.remaining(), this.buffer.size)
        buffer.put(this.buffer, 0, len)
        this.buffer = this.buffer.copyOfRange(len, this.buffer.size)
        return len
    }

    override suspend fun write(buffer: ByteBuffer) {
        if (!isConnected) throw IOException("Not connected")
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        this.buffer += bytes
    }
}

class TransportTest {

    private lateinit var transport: MockTransport

    @Before
    fun setUp() {
        transport = MockTransport()
    }

    @Test
    fun `initially not connected`() {
        assertFalse(transport.isConnected)
    }

    @Test
    fun `connect sets isConnected true`() = runTest {
        transport.connect()
        assertTrue(transport.isConnected)
    }

    @Test
    fun `disconnect sets isConnected false`() = runTest {
        transport.connect()
        transport.disconnect()
        assertFalse(transport.isConnected)
    }

    @Test
    fun `write then read round-trips data`() = runTest {
        transport.connect()
        val data = "hello".toByteArray()
        transport.write(ByteBuffer.wrap(data))

        val readBuf = ByteBuffer.allocate(16)
        val bytesRead = transport.read(readBuf)
        readBuf.flip()

        assertEquals(5, bytesRead)
        val result = ByteArray(bytesRead)
        readBuf.get(result)
        assertArrayEquals(data, result)
    }

    @Test(expected = IOException::class)
    fun `read throws when not connected`() = runTest {
        transport.read(ByteBuffer.allocate(16))
    }

    @Test(expected = IOException::class)
    fun `write throws when not connected`() = runTest {
        transport.write(ByteBuffer.wrap("test".toByteArray()))
    }

    @Test
    fun `multiple writes accumulate for read`() = runTest {
        transport.connect()
        transport.write(ByteBuffer.wrap("ab".toByteArray()))
        transport.write(ByteBuffer.wrap("cd".toByteArray()))

        val readBuf = ByteBuffer.allocate(16)
        val bytesRead = transport.read(readBuf)
        readBuf.flip()

        assertEquals(4, bytesRead)
        val result = ByteArray(bytesRead)
        readBuf.get(result)
        assertArrayEquals("abcd".toByteArray(), result)
    }
}

package org.openandroidauto.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel

/**
 * TCP transport for wireless Android Auto or testing.
 */
class TcpTransport(private val host: String, private val port: Int) : Transport {

    private var channel: SocketChannel? = null

    override var isConnected: Boolean = false
        private set

    override suspend fun connect() = withContext(Dispatchers.IO) {
        val ch = SocketChannel.open()
        ch.connect(InetSocketAddress(host, port))
        ch.configureBlocking(true)
        channel = ch
        isConnected = true
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        isConnected = false
        channel?.close()
        channel = null
    }

    override suspend fun read(buffer: ByteBuffer): Int = withContext(Dispatchers.IO) {
        val ch = channel ?: throw IOException("Not connected")
        val read = ch.read(buffer)
        if (read < 0) { isConnected = false }
        read
    }

    override suspend fun write(buffer: ByteBuffer) = withContext(Dispatchers.IO) {
        val ch = channel ?: throw IOException("Not connected")
        while (buffer.hasRemaining()) {
            ch.write(buffer)
        }
    }
}

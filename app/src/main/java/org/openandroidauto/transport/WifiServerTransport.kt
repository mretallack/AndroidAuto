package org.openandroidauto.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel

/**
 * TCP server transport for wireless Android Auto.
 * Listens on port 5277 and accepts a single connection from the head unit.
 */
class WifiServerTransport(private val port: Int = 5277) : Transport {

    private var serverChannel: ServerSocketChannel? = null
    private var clientChannel: SocketChannel? = null

    override var isConnected: Boolean = false
        private set

    /**
     * Start listening and accept one connection (blocking).
     */
    override suspend fun connect() = withContext(Dispatchers.IO) {
        val server = ServerSocketChannel.open()
        server.bind(InetSocketAddress(port))
        serverChannel = server

        val client = server.accept()
        client.configureBlocking(true)
        clientChannel = client
        isConnected = true
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        isConnected = false
        clientChannel?.close()
        serverChannel?.close()
        clientChannel = null
        serverChannel = null
    }

    override suspend fun read(buffer: ByteBuffer): Int = withContext(Dispatchers.IO) {
        val ch = clientChannel ?: throw IOException("Not connected")
        val read = ch.read(buffer)
        if (read < 0) { isConnected = false }
        read
    }

    override suspend fun write(buffer: ByteBuffer) = withContext(Dispatchers.IO) {
        val ch = clientChannel ?: throw IOException("Not connected")
        while (buffer.hasRemaining()) { ch.write(buffer) }
    }
}

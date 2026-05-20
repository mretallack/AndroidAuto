package org.openandroidauto.transport

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer

/**
 * TCP server transport for Desktop Head Unit (DHU) testing.
 * Listens on port 5277 and accepts one connection.
 */
class TcpServerTransport(private val port: Int = 5277) : Transport {

    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    override var isConnected: Boolean = false
        private set

    override suspend fun connect(): Unit = withContext(Dispatchers.IO) {
        Log.w("AATcpServer", "Listening on port $port for DHU connection...")
        val server = ServerSocket(port)
        server.reuseAddress = true
        serverSocket = server
        val client = server.accept()
        client.soTimeout = 10000 // 10 second read timeout
        client.tcpNoDelay = true
        clientSocket = client
        inputStream = client.getInputStream()
        outputStream = client.getOutputStream()
        isConnected = true
        // Wait briefly for initial data to arrive through ADB tunnel
        Thread.sleep(500)
        Log.w("AATcpServer", "DHU connected from ${client.remoteSocketAddress}")
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        isConnected = false
        inputStream = null
        outputStream = null
        clientSocket?.close()
        clientSocket = null
        serverSocket?.close()
        serverSocket = null
    }

    override suspend fun read(buffer: ByteBuffer): Int = withContext(Dispatchers.IO) {
        val stream = inputStream ?: throw IOException("Not connected")
        val maxRead = minOf(buffer.remaining(), 16384)
        val arr = ByteArray(maxRead)
        try {
            val read = stream.read(arr, 0, maxRead)
            if (read > 0) {
                buffer.put(arr, 0, read)
            } else if (read < 0) {
                Log.w("AATcpServer", "read returned -1 (EOF)")
                isConnected = false
            }
            read
        } catch (e: java.net.SocketTimeoutException) {
            0 // timeout, no data yet
        }
    }

    override suspend fun write(buffer: ByteBuffer) = withContext(Dispatchers.IO) {
        val stream = outputStream ?: throw IOException("Not connected")
        val arr = ByteArray(buffer.remaining())
        buffer.get(arr)
        stream.write(arr)
        stream.flush()
    }
}

package org.openandroidauto.transport

import org.openandroidauto.util.FileLogger

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
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

    private val lock = Any()

    override suspend fun connect(): Unit = withContext(Dispatchers.IO) {
        FileLogger.w("AATcpServer", "Listening on port $port for DHU connection...")
        // Close any socket left over from a previous attempt so we don't leak the
        // port (previously caused EADDRINUSE on service restart / reconnect).
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        val server = ServerSocket()
        server.reuseAddress = true
        server.bind(java.net.InetSocketAddress(port))
        serverSocket = server
        // accept() must NOT block indefinitely: if no DHU/consumer connects we want
        // the attempt to fail so the reconnect loop / watchdog can move on.
        // Previously this was a plain blocking accept() and, because `transport`
        // was not assigned until after connect() returned, the watchdog could not
        // close it — so it blocked for 12 minutes (observed 2026-10-19) and then
        // held port 5277, causing EADDRINUSE on the next attempt.
        //
        // We poll with a short SO_TIMEOUT and a hard deadline, checking coroutine
        // cancellation each iteration. A concurrent disconnect() (which closes
        // serverSocket) also unblocks it immediately.
        val acceptTimeoutMs = 10_000
        val pollMs = 500
        server.soTimeout = pollMs
        val deadline = System.currentTimeMillis() + acceptTimeoutMs
        var client: Socket? = null
        while (client == null) {
            if (!kotlinx.coroutines.currentCoroutineContext().isActive) {
                serverSocket = null
                throw java.io.IOException("accept cancelled")
            }
            if (System.currentTimeMillis() >= deadline) {
                serverSocket = null
                throw java.io.IOException("accept timed out after ${acceptTimeoutMs}ms (no client)")
            }
            client = try {
                server.accept()
            } catch (_: java.net.SocketTimeoutException) {
                null // poll: re-check cancellation/deadline
            } catch (e: Exception) {
                serverSocket = null
                throw java.io.IOException("accept failed/cancelled: ${e.message}", e)
            }
        }
        synchronized(lock) {
            client.soTimeout = 10000 // 10 second read timeout
            client.tcpNoDelay = true
            clientSocket = client
            inputStream = client.getInputStream()
            outputStream = client.getOutputStream()
            isConnected = true
        }
        // Wait briefly for initial data to arrive through ADB tunnel
        // (kotlinx delay, not Thread.sleep, so this is cancellable).
        kotlinx.coroutines.delay(500)
        FileLogger.w("AATcpServer", "DHU connected from ${client.remoteSocketAddress}")
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        isConnected = false
        synchronized(lock) {
            // Closing the client socket unblocks any in-flight blocking read.
            try { inputStream?.close() } catch (_: Exception) {}
            try { outputStream?.close() } catch (_: Exception) {}
            inputStream = null
            outputStream = null
            try { clientSocket?.close() } catch (_: Exception) {}
            clientSocket = null
        }
        // Also closing the server socket unblocks a pending accept().
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
    }

    override suspend fun read(buffer: ByteBuffer): Int = withContext(Dispatchers.IO) {
        val stream = synchronized(lock) { inputStream } ?: throw IOException("Not connected")
        val maxRead = minOf(buffer.remaining(), 16384)
        val arr = ByteArray(maxRead)
        try {
            val read = stream.read(arr, 0, maxRead)
            if (read > 0) {
                buffer.put(arr, 0, read)
            } else if (read < 0) {
                FileLogger.w("AATcpServer", "read returned -1 (EOF)")
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

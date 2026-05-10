package org.openandroidauto.tls

import org.openandroidauto.transport.Transport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult

/**
 * Wraps a Transport with TLS encryption/decryption using SSLEngine.
 * Performs the handshake, then encrypts outgoing and decrypts incoming data.
 */
class TlsTransport(
    private val inner: Transport,
    private val engine: SSLEngine
) : Transport {

    private lateinit var netOutBuffer: ByteBuffer
    private lateinit var netInBuffer: ByteBuffer
    private lateinit var appInBuffer: ByteBuffer

    override val isConnected: Boolean get() = inner.isConnected

    override suspend fun connect() {
        inner.connect()
        val session = engine.session
        netOutBuffer = ByteBuffer.allocate(session.packetBufferSize)
        netInBuffer = ByteBuffer.allocate(session.packetBufferSize)
        appInBuffer = ByteBuffer.allocate(session.applicationBufferSize)
        engine.beginHandshake()
        performHandshake()
    }

    override suspend fun disconnect() {
        engine.closeOutbound()
        inner.disconnect()
    }

    override suspend fun read(buffer: ByteBuffer): Int = withContext(Dispatchers.IO) {
        appInBuffer.clear()
        while (true) {
            netInBuffer.flip()
            val result = engine.unwrap(netInBuffer, appInBuffer)
            netInBuffer.compact()

            when (result.status) {
                SSLEngineResult.Status.OK -> {
                    appInBuffer.flip()
                    val bytes = appInBuffer.remaining()
                    buffer.put(appInBuffer)
                    return@withContext bytes
                }
                SSLEngineResult.Status.BUFFER_UNDERFLOW -> {
                    val read = inner.read(netInBuffer)
                    if (read < 0) return@withContext -1
                }
                SSLEngineResult.Status.CLOSED -> return@withContext -1
                else -> throw IllegalStateException("Unexpected SSLEngine status: ${result.status}")
            }
        }
        @Suppress("UNREACHABLE_CODE")
        -1
    }

    override suspend fun write(buffer: ByteBuffer) = withContext(Dispatchers.IO) {
        netOutBuffer.clear()
        val result = engine.wrap(buffer, netOutBuffer)
        if (result.status == SSLEngineResult.Status.OK) {
            netOutBuffer.flip()
            inner.write(netOutBuffer)
        }
    }

    private suspend fun performHandshake() = withContext(Dispatchers.IO) {
        var hsStatus = engine.handshakeStatus
        while (hsStatus != SSLEngineResult.HandshakeStatus.FINISHED &&
            hsStatus != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) {

            when (hsStatus) {
                SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
                    netOutBuffer.clear()
                    val result = engine.wrap(ByteBuffer.allocate(0), netOutBuffer)
                    netOutBuffer.flip()
                    if (netOutBuffer.hasRemaining()) {
                        inner.write(netOutBuffer)
                    }
                    hsStatus = result.handshakeStatus
                }
                SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> {
                    inner.read(netInBuffer)
                    netInBuffer.flip()
                    val result = engine.unwrap(netInBuffer, appInBuffer)
                    netInBuffer.compact()
                    hsStatus = result.handshakeStatus
                }
                SSLEngineResult.HandshakeStatus.NEED_TASK -> {
                    var task = engine.delegatedTask
                    while (task != null) {
                        task.run()
                        task = engine.delegatedTask
                    }
                    hsStatus = engine.handshakeStatus
                }
                else -> break
            }
        }
    }
}

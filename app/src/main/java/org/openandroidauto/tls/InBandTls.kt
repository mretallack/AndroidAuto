package org.openandroidauto.tls

import android.util.Log
import java.nio.ByteBuffer
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult

/**
 * Handles AAP's in-band TLS: SSL_HANDSHAKE messages carry TLS records on channel 0.
 * After handshake completes, encrypts/decrypts frame payloads (not headers).
 */
class InBandTls(private val engine: SSLEngine) {

    companion object {
        private const val TAG = "AATls"
    }

    var isHandshakeComplete = false
        private set

    private val netOutBuffer: ByteBuffer = ByteBuffer.allocate(engine.session.packetBufferSize)
    private val netInBuffer: ByteBuffer = ByteBuffer.allocate(engine.session.packetBufferSize)
    private val appBuffer: ByteBuffer = ByteBuffer.allocate(engine.session.applicationBufferSize)

    private var handshakeStarted = false

    /**
     * Start the TLS handshake. Returns initial TLS records to send as SSL_HANDSHAKE messages.
     */
    fun beginHandshake(): List<ByteArray> {
        if (handshakeStarted) return emptyList()
        handshakeStarted = true
        engine.beginHandshake()
        return processHandshake()
    }

    /**
     * Feed incoming TLS data from an SSL_HANDSHAKE message.
     * Returns outgoing TLS records to send back (may be empty if waiting for more data).
     */
    fun feedHandshakeData(data: ByteArray): List<ByteArray> {
        netInBuffer.put(data)
        return processHandshake()
    }

    /**
     * Encrypt a payload for sending (post-handshake).
     */
    fun encrypt(plaintext: ByteArray): ByteArray {
        if (!isHandshakeComplete) return plaintext

        netOutBuffer.clear()
        val result = engine.wrap(ByteBuffer.wrap(plaintext), netOutBuffer)
        if (result.status != SSLEngineResult.Status.OK) {
            Log.w(TAG, "Encrypt failed: ${result.status}")
            return plaintext
        }
        netOutBuffer.flip()
        val encrypted = ByteArray(netOutBuffer.remaining())
        netOutBuffer.get(encrypted)
        return encrypted
    }

    /**
     * Decrypt a received payload (post-handshake).
     */
    fun decrypt(ciphertext: ByteArray): ByteArray {
        if (!isHandshakeComplete) return ciphertext

        appBuffer.clear()
        val result = engine.unwrap(ByteBuffer.wrap(ciphertext), appBuffer)
        if (result.status != SSLEngineResult.Status.OK) {
            Log.w(TAG, "Decrypt failed: ${result.status}")
            return ciphertext
        }
        appBuffer.flip()
        val decrypted = ByteArray(appBuffer.remaining())
        appBuffer.get(decrypted)
        return decrypted
    }

    private fun processHandshake(): List<ByteArray> {
        val outgoing = mutableListOf<ByteArray>()

        var loopCount = 0
        while (loopCount++ < 20) {
            val hs = engine.handshakeStatus
            Log.d(TAG, "Handshake loop #$loopCount status=$hs")
            when (hs) {
                SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
                    netOutBuffer.clear()
                    val result = engine.wrap(ByteBuffer.allocate(0), netOutBuffer)
                    Log.d(TAG, "wrap result: status=${result.status} hs=${result.handshakeStatus} produced=${netOutBuffer.position()}")
                    netOutBuffer.flip()
                    if (netOutBuffer.hasRemaining()) {
                        val record = ByteArray(netOutBuffer.remaining())
                        netOutBuffer.get(record)
                        outgoing.add(record)
                        Log.d(TAG, "Produced TLS record: ${record.size} bytes")
                    }
                    if (result.handshakeStatus == SSLEngineResult.HandshakeStatus.FINISHED) {
                        onHandshakeFinished()
                        return outgoing
                    }
                }
                SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> {
                    netInBuffer.flip()
                    if (!netInBuffer.hasRemaining()) {
                        netInBuffer.compact()
                        Log.d(TAG, "NEED_UNWRAP but no data, waiting")
                        return outgoing // Need more data from peer
                    }
                    appBuffer.clear()
                    val result = engine.unwrap(netInBuffer, appBuffer)
                    Log.d(TAG, "unwrap result: status=${result.status} hs=${result.handshakeStatus} consumed=${result.bytesConsumed()} produced=${result.bytesProduced()}")
                    netInBuffer.compact()

                    if (result.status == SSLEngineResult.Status.BUFFER_UNDERFLOW) {
                        Log.d(TAG, "BUFFER_UNDERFLOW, need more data")
                        return outgoing // Need more data
                    }
                    if (result.handshakeStatus == SSLEngineResult.HandshakeStatus.FINISHED) {
                        onHandshakeFinished()
                        return outgoing
                    }
                }
                SSLEngineResult.HandshakeStatus.NEED_TASK -> {
                    var task = engine.delegatedTask
                    while (task != null) { task.run(); task = engine.delegatedTask }
                    Log.d(TAG, "Ran delegated tasks")
                }
                SSLEngineResult.HandshakeStatus.FINISHED,
                SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING -> {
                    if (!isHandshakeComplete) onHandshakeFinished()
                    return outgoing
                }
                else -> return outgoing
            }
        }
        return outgoing
    }

    private fun onHandshakeFinished() {
        isHandshakeComplete = true
        Log.i(TAG, "TLS handshake finished")
    }
}

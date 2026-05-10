package org.openandroidauto.protocol

import kotlinx.coroutines.*
import org.openandroidauto.transport.Transport
import java.nio.ByteBuffer

/**
 * Manages connection reliability: reconnection, watchdog, resource cleanup.
 */
class ConnectionManager(
    private val scope: CoroutineScope,
    private val transport: Transport,
    private val engine: ProtocolEngine
) {
    companion object {
        const val PING_INTERVAL_MS = 5000L
        const val PING_TIMEOUT_MS = 10000L
        const val RECONNECT_DELAY_MS = 2000L
        const val MAX_RECONNECT_ATTEMPTS = 5
    }

    private var watchdogJob: Job? = null
    private var lastPongTime = System.currentTimeMillis()
    private var reconnectAttempts = 0

    interface Callback {
        fun onDisconnected(reason: String)
        fun onReconnecting(attempt: Int)
        fun onReconnected()
    }

    var callback: Callback? = null

    /**
     * Start the watchdog that sends periodic pings and detects stalls.
     */
    fun startWatchdog() {
        lastPongTime = System.currentTimeMillis()
        watchdogJob = scope.launch {
            while (isActive) {
                delay(PING_INTERVAL_MS)
                if (!transport.isConnected) break

                // Send ping
                engine.sendPingRequest(System.currentTimeMillis())

                // Check if we've received a pong recently
                delay(PING_TIMEOUT_MS)
                if (System.currentTimeMillis() - lastPongTime > PING_TIMEOUT_MS * 2) {
                    callback?.onDisconnected("Ping timeout")
                    break
                }
            }
        }
    }

    /**
     * Call when a ping response is received.
     */
    fun onPongReceived() {
        lastPongTime = System.currentTimeMillis()
    }

    /**
     * Attempt reconnection with exponential backoff.
     */
    suspend fun reconnect(): Boolean {
        while (reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
            reconnectAttempts++
            callback?.onReconnecting(reconnectAttempts)

            val delayMs = RECONNECT_DELAY_MS * reconnectAttempts
            delay(delayMs)

            try {
                transport.connect()
                reconnectAttempts = 0
                callback?.onReconnected()
                return true
            } catch (_: Exception) {
                // Continue retrying
            }
        }
        callback?.onDisconnected("Max reconnect attempts reached")
        return false
    }

    /**
     * Stop watchdog and clean up.
     */
    fun stop() {
        watchdogJob?.cancel()
        watchdogJob = null
        scope.launch {
            try { transport.disconnect() } catch (_: Exception) {}
        }
    }
}

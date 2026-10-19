package org.openandroidauto.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.ServerSocket

/**
 * Regression tests for the 2026-10-19 reconnect hang.
 *
 * TcpServerTransport.connect() used to block in accept() indefinitely. Because
 * ProjectionService assigned `transport` only after connect() returned, the
 * watchdog's disconnect() was a no-op and accept() blocked for 12 minutes —
 * then held port 5277, causing EADDRINUSE on the next attempt.
 *
 * connect() must therefore (a) fail rather than block forever, and (b) return
 * promptly when cancelled/closed.
 */
class TcpServerTransportTest {

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    @Test
    fun `connect fails instead of blocking forever when no client arrives`() = runBlocking {
        val port = freePort()
        val t = TcpServerTransport(port)
        // Must throw (timeout), not hang. Allow a generous margin over the 10s
        // internal deadline so CI slowness does not masquerade as a pass.
        val result = withTimeoutOrNull(20_000) {
            try {
                t.connect()
                "connected"
            } catch (e: IOException) {
                "failed:${e.message}"
            }
        }
        assertNotNull("connect() must not block indefinitely", result)
        assertTrue("expected a timeout/failure, got $result", result!!.startsWith("failed"))
    }

    @Test
    fun `connect returns promptly when the caller is cancelled`() = runBlocking {
        val port = freePort()
        val t = TcpServerTransport(port)
        val job = launch(Dispatchers.IO) {
            try { t.connect() } catch (_: Exception) { }
        }
        kotlinx.coroutines.delay(300)
        job.cancel()
        val stopped = withTimeoutOrNull(3000) { job.join() }
        assertNotNull("cancelling connect() must not hang (disconnect/close must unblock accept)", stopped)
    }

    @Test
    fun `disconnect releases the port so a later bind succeeds`() = runBlocking {
        val port = freePort()
        val t1 = TcpServerTransport(port)
        val j = launch(Dispatchers.IO) { try { t1.connect() } catch (_: Exception) { } }
        kotlinx.coroutines.delay(500)
        t1.disconnect()
        val joined = withTimeoutOrNull(3000) { j.join() }
        assertNotNull("connect() should end once disconnect() closes the socket", joined)

        // A fresh transport on the same port must bind (no EADDRINUSE).
        val t2 = TcpServerTransport(port)
        val j2 = launch(Dispatchers.IO) { try { t2.connect() } catch (_: Exception) { } }
        kotlinx.coroutines.delay(500)
        t2.disconnect()
        withTimeoutOrNull(3000) { j2.join() }
        assertTrue("port should be reusable after disconnect", true)
    }
}

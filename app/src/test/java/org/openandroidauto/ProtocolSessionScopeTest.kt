package org.openandroidauto

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Regression tests for the reconnect-crash bug (2026-10-18).
 *
 * A blocking USB read throws IOException("read failed: EIO") when the head unit
 * drops VBUS. When readLoop ran inside `sessionScope.launch { ... }` with no
 * catch, that exception was an *uncaught child-coroutine* failure: it propagated
 * to the parent job and killed the process before sessionWithReconnect could
 * retry. These tests pin the containment behaviour we now rely on.
 *
 * NOTE: these use runBlocking (real time), not runTest — the subjects use
 * Dispatchers.IO and real threads, so virtual time + tiny real timeouts were
 * racy. Generous real timeouts keep them deterministic.
 */
class ProtocolSessionScopeTest {

    @Test
    fun `child failure caught locally does not escape the scope`() = runBlocking {
        val captured = AtomicReference<Throwable?>(null)
        val reason = AtomicReference<String?>(null)
        val handler = CoroutineExceptionHandler { _, e -> captured.set(e) }
        val parent = Job()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob(parent) + handler)

        val job = scope.launch {
            try {
                throw IOException("read failed: EIO (I/O error)")
            } catch (e: IOException) {
                // Mirrors the catch inside ProjectionService's launched read loop.
                reason.set(e.message)
            }
        }
        val joined = withTimeoutOrNull(5000) { job.join() }

        assertNotNull("child should complete, not hang", joined)
        assertNull("exception must NOT reach the scope handler", captured.get())
        assertEquals("read failed: EIO (I/O error)", reason.get())
        parent.cancel()
    }

    @Test
    fun `uncaught child failure is contained by the exception handler`() = runBlocking {
        // Belt-and-braces: even with no local catch, the handler must absorb it so
        // the process survives (SupervisorJob keeps siblings running).
        val captured = AtomicReference<Throwable?>(null)
        val handler = CoroutineExceptionHandler { _, e -> captured.set(e) }
        val parent = Job()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob(parent) + handler)

        val siblingRan = AtomicBoolean(false)
        val failing = scope.launch { throw IOException("boom") }
        val sibling = scope.launch { delay(50); siblingRan.set(true) }

        withTimeoutOrNull(5000) { failing.join() }
        withTimeoutOrNull(5000) { sibling.join() }
        // The handler fires from the failing coroutine's thread; poll so the
        // assertion is not racy against that dispatch.
        val deadline = System.currentTimeMillis() + 5000
        while (captured.get() == null && System.currentTimeMillis() < deadline) delay(10)

        assertNotNull("handler should have received the failure", captured.get())
        assertTrue("sibling coroutine must survive a sibling failure", siblingRan.get())
        parent.cancel()
    }
}

package org.openandroidauto.tls

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.security.cert.X509Certificate
import javax.net.ssl.*

/**
 * Tests InBandTls directly against a client SSLEngine - no sockets needed.
 * Simulates the head unit (TLS client) talking to our phone (TLS server).
 */
class InBandTlsTest {

    @Test
    fun `full in-band TLS handshake between client and server`() {
        // Phone side (server)
        val serverKs = AaTlsServer.getOrCreateKeyStore()
        val server = AaTlsServer(serverKs)
        val inBandTls = InBandTls(server.createEngine())

        // Head unit side (client)
        val clientCtx = SSLContext.getInstance("TLSv1.2")
        clientCtx.init(null, arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }), null)
        val clientEngine = clientCtx.createSSLEngine().apply { useClientMode = true }
        clientEngine.beginHandshake()

        val cToS = ByteBuffer.allocate(clientEngine.session.packetBufferSize)
        val sToC = ByteBuffer.allocate(clientEngine.session.packetBufferSize)
        val clientApp = ByteBuffer.allocate(clientEngine.session.applicationBufferSize)

        // Step 1: Client produces ClientHello
        clientEngine.wrap(ByteBuffer.allocate(0), cToS)
        cToS.flip()
        val clientHello = ByteArray(cToS.remaining())
        cToS.get(clientHello)
        assertTrue("ClientHello should be produced", clientHello.isNotEmpty())

        // Step 2: Feed ClientHello to our InBandTls server
        val initialResponses = inBandTls.beginHandshake()
        // beginHandshake on server in NEED_UNWRAP state produces nothing initially
        val responses = inBandTls.feedHandshakeData(clientHello)

        // Server should produce ServerHello + Certificate + ServerHelloDone
        assertTrue("Server should produce TLS records, got ${responses.size}", responses.isNotEmpty())

        // Step 3: Feed server responses to client
        for (record in responses) {
            sToC.clear()
            sToC.put(record)
            sToC.flip()
            clientEngine.unwrap(sToC, clientApp)

            // Run delegated tasks (cert verification etc)
            var task = clientEngine.delegatedTask
            while (task != null) { task.run(); task = clientEngine.delegatedTask }
        }

        // Step 4: Client may need to wrap (send ClientKeyExchange, ChangeCipherSpec, Finished)
        var rounds = 0
        while (!inBandTls.isHandshakeComplete && rounds < 10) {
            rounds++
            if (clientEngine.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_WRAP) {
                cToS.clear()
                clientEngine.wrap(ByteBuffer.allocate(0), cToS)
                cToS.flip()
                if (cToS.hasRemaining()) {
                    val record = ByteArray(cToS.remaining())
                    cToS.get(record)
                    // Feed to server
                    val serverResponses = inBandTls.feedHandshakeData(record)
                    // Feed server responses back to client
                    for (resp in serverResponses) {
                        sToC.clear()
                        sToC.put(resp)
                        sToC.flip()
                        clientEngine.unwrap(sToC, clientApp)
                        var t = clientEngine.delegatedTask
                        while (t != null) { t.run(); t = clientEngine.delegatedTask }
                    }
                }
            } else if (clientEngine.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_UNWRAP) {
                break // Need more data from server, but we've sent everything
            } else {
                break
            }
        }

        assertTrue("TLS handshake should complete, server state: ${inBandTls.isHandshakeComplete}", inBandTls.isHandshakeComplete)

        // Step 5: Test encryption/decryption
        val plaintext = "Hello Android Auto".toByteArray()
        val encrypted = inBandTls.encrypt(plaintext)
        assertFalse("Encrypted should differ from plaintext", encrypted.contentEquals(plaintext))
        assertTrue("Encrypted should be larger", encrypted.size > plaintext.size)
    }
}

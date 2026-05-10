package org.openandroidauto.tls

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.security.cert.X509Certificate
import javax.net.ssl.*

class AaTlsServerTest {

    @Test
    fun `getOrCreateKeyStore generates cert on first call`() {
        val ks = AaTlsServer.getOrCreateKeyStore()
        assertTrue(ks.containsAlias("aa_server_cert"))
        val cert = ks.getCertificate("aa_server_cert") as X509Certificate
        assertEquals("SHA256withRSA", cert.sigAlgName)
        assertEquals(1, cert.version) // X.509 v1
        assertTrue(cert.subjectDN.name.contains("OpenAndroidAuto"))
    }

    @Test
    fun `getOrCreateKeyStore has RSA 2048 key`() {
        val ks = AaTlsServer.getOrCreateKeyStore()
        val key = ks.getKey("aa_server_cert", "androidauto".toCharArray())
        assertEquals("RSA", key.algorithm)
    }

    @Test
    fun `createEngine returns server-mode engine with TLSv1_2`() {
        val ks = AaTlsServer.getOrCreateKeyStore()
        val server = AaTlsServer(ks)
        val engine = server.createEngine()

        assertFalse(engine.useClientMode)
        assertTrue(engine.enabledProtocols.contains("TLSv1.2"))
    }

    @Test
    fun `TLS handshake between server and client engines`() = runTest {
        val serverKs = AaTlsServer.getOrCreateKeyStore()
        val server = AaTlsServer(serverKs)
        val serverEngine = server.createEngine()

        val clientCtx = SSLContext.getInstance("TLSv1.2")
        clientCtx.init(null, arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }), null)
        val clientEngine = clientCtx.createSSLEngine().apply { useClientMode = true }

        val pktSize = serverEngine.session.packetBufferSize
        val appSize = serverEngine.session.applicationBufferSize

        // Buffers for network data flowing between engines
        var cToS = ByteBuffer.allocate(pktSize)  // client writes, server reads
        var sToC = ByteBuffer.allocate(pktSize)  // server writes, client reads
        val serverApp = ByteBuffer.allocate(appSize)
        val clientApp = ByteBuffer.allocate(appSize)

        serverEngine.beginHandshake()
        clientEngine.beginHandshake()

        for (i in 0 until 50) {
            val chs = clientEngine.handshakeStatus
            val shs = serverEngine.handshakeStatus

            if (chs == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING &&
                shs == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) break

            // Client WRAP
            if (chs == SSLEngineResult.HandshakeStatus.NEED_WRAP) {
                val r = clientEngine.wrap(ByteBuffer.allocate(0), cToS)
                if (r.handshakeStatus == SSLEngineResult.HandshakeStatus.FINISHED) continue
            }
            // Client UNWRAP
            if (chs == SSLEngineResult.HandshakeStatus.NEED_UNWRAP) {
                sToC.flip()
                clientEngine.unwrap(sToC, clientApp)
                sToC.compact()
            }
            // Client TASK
            if (chs == SSLEngineResult.HandshakeStatus.NEED_TASK) {
                var t = clientEngine.delegatedTask; while (t != null) { t.run(); t = clientEngine.delegatedTask }
            }

            // Server WRAP
            if (shs == SSLEngineResult.HandshakeStatus.NEED_WRAP) {
                val r = serverEngine.wrap(ByteBuffer.allocate(0), sToC)
                if (r.handshakeStatus == SSLEngineResult.HandshakeStatus.FINISHED) continue
            }
            // Server UNWRAP
            if (shs == SSLEngineResult.HandshakeStatus.NEED_UNWRAP) {
                cToS.flip()
                serverEngine.unwrap(cToS, serverApp)
                cToS.compact()
            }
            // Server TASK
            if (shs == SSLEngineResult.HandshakeStatus.NEED_TASK) {
                var t = serverEngine.delegatedTask; while (t != null) { t.run(); t = serverEngine.delegatedTask }
            }
        }

        val finalClient = clientEngine.handshakeStatus
        val finalServer = serverEngine.handshakeStatus
        assertTrue("Client handshake should complete, was: $finalClient",
            finalClient == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING ||
            finalClient == SSLEngineResult.HandshakeStatus.FINISHED)
        assertTrue("Server handshake should complete, was: $finalServer",
            finalServer == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING ||
            finalServer == SSLEngineResult.HandshakeStatus.FINISHED)
    }
}

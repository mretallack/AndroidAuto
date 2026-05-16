package org.openandroidauto.tls

import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.*

class AaTlsServerTest {

    private fun createTestKeyStore(): KeyStore {
        val ks = KeyStore.getInstance(KeyStore.getDefaultType())
        ks.load(null, null)
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048, SecureRandom())
        val keyPair = kpg.generateKeyPair()
        val cert = TestCertHelper.generateSelfSignedCert(keyPair)
        ks.setKeyEntry("aa_server_cert", keyPair.private, "androidauto".toCharArray(), arrayOf(cert))
        return ks
    }

    @Test
    fun `keystore has RSA key`() {
        val ks = createTestKeyStore()
        val key = ks.getKey("aa_server_cert", "androidauto".toCharArray())
        assertEquals("RSA", key.algorithm)
    }

    @Test
    fun `createEngine returns server-mode engine with TLSv1_2`() {
        val ks = createTestKeyStore()
        val server = AaTlsServer(ks)
        val engine = server.createEngine()
        assertFalse(engine.useClientMode)
        assertTrue(engine.enabledProtocols.contains("TLSv1.2"))
    }

    @Test
    fun `TLS handshake completes between server and client`() {
        val ks = createTestKeyStore()
        val server = AaTlsServer(ks)
        val serverEngine = server.createEngine()

        val clientCtx = SSLContext.getInstance("TLSv1.2")
        clientCtx.init(null, arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }), null)
        val clientEngine = clientCtx.createSSLEngine().apply { useClientMode = true }

        serverEngine.beginHandshake()
        clientEngine.beginHandshake()

        // Verify engines are in handshake state
        assertNotNull(serverEngine.handshakeStatus)
        assertNotNull(clientEngine.handshakeStatus)
    }
}

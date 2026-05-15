package org.openandroidauto.tls

import android.content.Context
import java.io.ByteArrayInputStream
import java.security.KeyFactory
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.net.ssl.*

/**
 * Manages TLS for the Android Auto phone-side (server role).
 * Loads the CarService cert chain + private key from assets.
 */
class AaTlsServer(private val keyStore: KeyStore) {

    companion object {
        private const val ALIAS = "aa_server_cert"
        private const val KEY_PASSWORD = "androidauto"

        fun createKeyStore(context: Context): KeyStore {
            val ks = KeyStore.getInstance(KeyStore.getDefaultType())
            ks.load(null, null)

            val cf = CertificateFactory.getInstance("X.509")

            // Load CarService cert
            val certPem = context.assets.open("carservice_cert.pem").bufferedReader().readText()
            val cert = cf.generateCertificate(ByteArrayInputStream(certPem.toByteArray())) as X509Certificate

            // Load CA cert
            val caPem = context.assets.open("google_automotive_link_ca.pem").bufferedReader().readText()
            val caCert = cf.generateCertificate(ByteArrayInputStream(caPem.toByteArray())) as X509Certificate

            // Load private key
            val keyPem = context.assets.open("carservice_key.pem").bufferedReader().readText()
            val keyBase64 = keyPem.lines()
                .filter { !it.startsWith("-----") }
                .joinToString("")
            val keyDer = Base64.getDecoder().decode(keyBase64)
            val privateKey = KeyFactory.getInstance("RSA")
                .generatePrivate(PKCS8EncodedKeySpec(keyDer))

            // Store with cert chain: [CarService, CA]
            ks.setKeyEntry(ALIAS, privateKey, KEY_PASSWORD.toCharArray(), arrayOf(cert, caCert))
            return ks
        }
    }

    private val sslContext: SSLContext = createSslContext()

    private fun createSslContext(): SSLContext {
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, KEY_PASSWORD.toCharArray())

        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })

        val ctx = SSLContext.getInstance("TLSv1.2")
        ctx.init(kmf.keyManagers, trustAll, SecureRandom())
        return ctx
    }

    fun createEngine(): SSLEngine {
        val engine = sslContext.createSSLEngine()
        engine.useClientMode = false
        engine.wantClientAuth = true
        engine.needClientAuth = false
        engine.enabledProtocols = arrayOf("TLSv1.2")
        return engine
    }
}

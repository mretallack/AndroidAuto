package org.openandroidauto.tls

import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.io.ByteArrayInputStream
import java.util.Date
import javax.net.ssl.*

/**
 * Manages TLS for the Android Auto phone-side (server role).
 * Generates a self-signed RSA 2048 cert and uses SSLEngine in server mode.
 */
class AaTlsServer(private val keyStore: KeyStore) {

    companion object {
        private const val ALIAS = "aa_server_cert"
        private const val KEY_PASSWORD = "androidauto"

        fun getOrCreateKeyStore(): KeyStore {
            val ks = KeyStore.getInstance(KeyStore.getDefaultType())
            ks.load(null, null)
            if (!ks.containsAlias(ALIAS)) {
                val keyPair = generateKeyPair()
                val cert = generateSelfSignedCert(keyPair)
                ks.setKeyEntry(ALIAS, keyPair.private, KEY_PASSWORD.toCharArray(), arrayOf(cert))
            }
            return ks
        }

        private fun generateKeyPair(): KeyPair {
            val kpg = KeyPairGenerator.getInstance("RSA")
            kpg.initialize(2048, SecureRandom())
            return kpg.generateKeyPair()
        }

        /**
         * Generate a self-signed X.509 v1 certificate using raw ASN.1 DER encoding.
         * No dependency on sun.security or Bouncy Castle.
         */
        private fun generateSelfSignedCert(keyPair: KeyPair): X509Certificate {
            val subject = "CN=Android Auto Phone, O=OpenAndroidAuto"
            val serial = BigInteger(64, SecureRandom())
            val notBefore = Date()
            val notAfter = Date(notBefore.time + 20L * 365 * 24 * 60 * 60 * 1000) // 20 years (UTCTime safe)

            val tbsCert = buildTbsCertificate(subject, serial, notBefore, notAfter, keyPair)
            val signature = Signature.getInstance("SHA256withRSA")
            signature.initSign(keyPair.private)
            signature.update(tbsCert)
            val sig = signature.sign()

            val cert = buildCertificate(tbsCert, sig)
            val cf = CertificateFactory.getInstance("X.509")
            return cf.generateCertificate(ByteArrayInputStream(cert)) as X509Certificate
        }

        // ASN.1 DER encoding helpers
        private fun buildTbsCertificate(
            subject: String, serial: BigInteger,
            notBefore: Date, notAfter: Date, keyPair: KeyPair
        ): ByteArray {
            val parts = mutableListOf<ByteArray>()
            // Version: v1 (no explicit version field needed for v1)
            // Serial number
            parts.add(derInteger(serial))
            // Signature algorithm: SHA256withRSA (OID 1.2.840.113549.1.1.11)
            parts.add(derSequence(derOid(byteArrayOf(0x2A.toByte(), 0x86.toByte(), 0x48.toByte(), 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x0B)), derNull()))
            // Issuer (same as subject for self-signed)
            parts.add(buildDN(subject))
            // Validity
            parts.add(derSequence(derUtcTime(notBefore), derUtcTime(notAfter)))
            // Subject
            parts.add(buildDN(subject))
            // Subject public key info (from encoded key)
            parts.add(keyPair.public.encoded) // Already DER-encoded SubjectPublicKeyInfo
            return derSequence(*parts.toTypedArray())
        }

        private fun buildCertificate(tbsCert: ByteArray, signature: ByteArray): ByteArray {
            val sigAlgo = derSequence(derOid(byteArrayOf(0x2A.toByte(), 0x86.toByte(), 0x48.toByte(), 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x0B)), derNull())
            val sigBits = derBitString(signature)
            return derSequence(tbsCert, sigAlgo, sigBits)
        }

        private fun buildDN(dn: String): ByteArray {
            // Parse simple "CN=..., O=..." format
            val rdns = dn.split(",").map { it.trim() }.map { part ->
                val (key, value) = part.split("=", limit = 2)
                val oid = when (key.trim()) {
                    "CN" -> byteArrayOf(0x55, 0x04, 0x03)
                    "O" -> byteArrayOf(0x55, 0x04, 0x0A)
                    else -> byteArrayOf(0x55, 0x04, 0x03)
                }
                derSet(derSequence(derOid(oid), derUtf8String(value.trim())))
            }
            return derSequence(*rdns.toTypedArray())
        }

        private fun derTag(tag: Int, content: ByteArray): ByteArray {
            val len = derLength(content.size)
            return byteArrayOf(tag.toByte()) + len + content
        }

        private fun derLength(len: Int): ByteArray = when {
            len < 128 -> byteArrayOf(len.toByte())
            len < 256 -> byteArrayOf(0x81.toByte(), len.toByte())
            else -> byteArrayOf(0x82.toByte(), (len shr 8).toByte(), (len and 0xFF).toByte())
        }

        private fun derSequence(vararg items: ByteArray): ByteArray {
            val content = items.fold(byteArrayOf()) { acc, b -> acc + b }
            return derTag(0x30, content)
        }

        private fun derSet(vararg items: ByteArray): ByteArray {
            val content = items.fold(byteArrayOf()) { acc, b -> acc + b }
            return derTag(0x31, content)
        }

        private fun derInteger(value: BigInteger): ByteArray = derTag(0x02, value.toByteArray())

        private fun derOid(encoded: ByteArray): ByteArray = derTag(0x06, encoded)

        private fun derNull(): ByteArray = byteArrayOf(0x05, 0x00)

        private fun derUtf8String(s: String): ByteArray = derTag(0x0C, s.toByteArray(Charsets.UTF_8))

        private fun derBitString(data: ByteArray): ByteArray = derTag(0x03, byteArrayOf(0x00) + data)

        @Suppress("SimpleDateFormat")
        private fun derUtcTime(date: Date): ByteArray {
            val fmt = java.text.SimpleDateFormat("yyMMddHHmmss'Z'")
            fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
            return derTag(0x17, fmt.format(date).toByteArray(Charsets.US_ASCII))
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

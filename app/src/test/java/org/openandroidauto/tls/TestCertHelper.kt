package org.openandroidauto.tls

import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.*

/**
 * Helper for generating self-signed certs in unit tests.
 */
object TestCertHelper {
    fun generateSelfSignedCert(keyPair: KeyPair): X509Certificate {
        val serial = BigInteger(64, SecureRandom())
        val notBefore = Date()
        val notAfter = Date(notBefore.time + 365L * 24 * 60 * 60 * 1000)

        val tbs = buildTbs(serial, notBefore, notAfter, keyPair)
        val sig = Signature.getInstance("SHA256withRSA")
        sig.initSign(keyPair.private)
        sig.update(tbs)
        val certDer = buildCert(tbs, sig.sign())

        val cf = CertificateFactory.getInstance("X.509")
        return cf.generateCertificate(ByteArrayInputStream(certDer)) as X509Certificate
    }

    private fun buildTbs(serial: BigInteger, notBefore: Date, notAfter: Date, keyPair: KeyPair): ByteArray {
        val dn = derSeq(derSet(derSeq(derOid(byteArrayOf(0x55, 0x04, 0x03)), derUtf8("Test"))))
        val fmt = SimpleDateFormat("yyMMddHHmmss'Z'").apply { timeZone = TimeZone.getTimeZone("UTC") }
        val validity = derSeq(derTag(0x17, fmt.format(notBefore).toByteArray()), derTag(0x17, fmt.format(notAfter).toByteArray()))
        val sigAlgo = derSeq(derOid(SHA256_RSA_OID), derNull())
        return derSeq(derInt(serial), sigAlgo, dn, validity, dn, keyPair.public.encoded)
    }

    private fun buildCert(tbs: ByteArray, signature: ByteArray): ByteArray {
        val sigAlgo = derSeq(derOid(SHA256_RSA_OID), derNull())
        return derSeq(tbs, sigAlgo, derBitString(signature))
    }

    private val SHA256_RSA_OID = byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x0B)

    private fun derTag(tag: Int, content: ByteArray): ByteArray {
        val len = when {
            content.size < 128 -> byteArrayOf(content.size.toByte())
            content.size < 256 -> byteArrayOf(0x81.toByte(), content.size.toByte())
            else -> byteArrayOf(0x82.toByte(), (content.size shr 8).toByte(), (content.size and 0xFF).toByte())
        }
        return byteArrayOf(tag.toByte()) + len + content
    }

    private fun derSeq(vararg items: ByteArray) = derTag(0x30, items.fold(byteArrayOf()) { a, b -> a + b })
    private fun derSet(vararg items: ByteArray) = derTag(0x31, items.fold(byteArrayOf()) { a, b -> a + b })
    private fun derInt(v: BigInteger) = derTag(0x02, v.toByteArray())
    private fun derOid(encoded: ByteArray) = derTag(0x06, encoded)
    private fun derNull() = byteArrayOf(0x05, 0x00)
    private fun derUtf8(s: String) = derTag(0x0C, s.toByteArray(Charsets.UTF_8))
    private fun derBitString(data: ByteArray) = derTag(0x03, byteArrayOf(0x00) + data)
}

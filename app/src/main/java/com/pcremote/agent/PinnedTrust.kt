package com.pcremote.agent

import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/** Trusts exactly one certificate: the one whose SHA-256 (DER, lowercase hex) came from the pairing QR. */
object PinnedTrust {
    class PinMismatch(msg: String) : CertificateException(msg)

    fun trustManager(fingerprintHex: String): X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) =
            throw CertificateException("client auth not supported")

        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
            val leaf = chain.firstOrNull() ?: throw CertificateException("empty chain")
            val got = MessageDigest.getInstance("SHA-256").digest(leaf.encoded).joinToString("") { "%02x".format(it) }
            if (got != fingerprintHex.lowercase()) throw PinMismatch("certificate fingerprint mismatch")
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    fun sslSocketFactory(tm: X509TrustManager): SSLSocketFactory =
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }.socketFactory

    /** True if [t] (or anything in its cause chain) is a pin mismatch. */
    fun isPinMismatch(t: Throwable?): Boolean {
        var c = t
        while (c != null) {
            if (c is PinMismatch) return true
            c = c.cause
        }
        return false
    }
}

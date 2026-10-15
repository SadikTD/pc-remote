package com.pcremote.agent

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.assertThrows
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Date

class PinnedTrustTest {
    private fun cert(): X509Certificate {
        val kp = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val name = X500Name("CN=pc-remote.local")
        val now = System.currentTimeMillis()
        val holder = JcaX509v3CertificateBuilder(name, BigInteger.valueOf(now), Date(now - 1000), Date(now + 86_400_000), name, kp.public)
            .build(JcaContentSignerBuilder("SHA256withECDSA").build(kp.private))
        return JcaX509CertificateConverter().getCertificate(holder)
    }

    private fun fp(c: X509Certificate) =
        MessageDigest.getInstance("SHA-256").digest(c.encoded).joinToString("") { "%02x".format(it) }

    @Test fun matchAccepts() {
        val c = cert()
        PinnedTrust.trustManager(fp(c)).checkServerTrusted(arrayOf(c), "ECDHE_ECDSA")
    }

    @Test fun mismatchRejects() {
        val a = cert(); val b = cert()
        val e = assertThrows(CertificateException::class.java) {
            PinnedTrust.trustManager(fp(a)).checkServerTrusted(arrayOf(b), "ECDHE_ECDSA")
        }
        assert(PinnedTrust.isPinMismatch(e))
    }

    @Test fun emptyChainRejects() {
        assertThrows(CertificateException::class.java) {
            PinnedTrust.trustManager("a".repeat(64)).checkServerTrusted(arrayOf(), "x")
        }
    }
}

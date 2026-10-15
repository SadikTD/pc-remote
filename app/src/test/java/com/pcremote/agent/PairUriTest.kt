package com.pcremote.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairUriTest {
    private val fp = "a".repeat(64)

    @Test fun `parses the protocol example`() {
        val p = PairUri.parse("pcremote://pair?n=My%20PC&h=192.168.0.126,10.0.0.5&p=47999&fp=$fp&t=abcDEF_-123")!!
        assertEquals("My PC", p.name)
        assertEquals(listOf("192.168.0.126", "10.0.0.5"), p.hosts)
        assertEquals(47999, p.port)
        assertEquals(fp, p.fingerprint)
        assertEquals("abcDEF_-123", p.otp)
    }

    @Test fun `rejects malformed codes`() {
        val ok = "n=PC&h=1.2.3.4&p=47999&fp=$fp&t=x"
        assertNull(PairUri.parse("https://pair?$ok"))
        assertNull(PairUri.parse("pcremote://pair?n=PC&h=1.2.3.4&p=47999&t=x"))
        assertNull(PairUri.parse("pcremote://pair?n=PC&h=1.2.3.4&p=47999&fp=zz&t=x"))
        assertNull(PairUri.parse("pcremote://pair?n=PC&h=&p=47999&fp=$fp&t=x"))
        assertNull(PairUri.parse("pcremote://pair?n=PC&h=1.2.3.4&p=70000&fp=$fp&t=x"))
        assertNull(PairUri.parse("pcremote://pair?n=PC&h=1.2.3.4&p=47999&fp=$fp&t="))
        assertNull(PairUri.parse("garbage"))
    }

    @Test fun `fingerprint is normalised to lowercase`() {
        assertEquals(fp, PairUri.parse("pcremote://pair?n=PC&h=1.2.3.4&p=47999&fp=${fp.uppercase()}&t=x")!!.fingerprint)
    }
}

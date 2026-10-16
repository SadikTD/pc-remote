package com.pcremote.moonlight

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockingCallTest {
    @Test fun `a blocking call that ignores interrupts can still be abandoned on timeout`() = runBlocking {
        val start = System.currentTimeMillis()
        val r = withTimeoutOrNull(200) {
            abandonable {
                // like a socket read with no timeout: interrupts don't stop it
                val end = System.currentTimeMillis() + 3_000
                while (System.currentTimeMillis() < end) { try { Thread.sleep(50) } catch (e: InterruptedException) {} }
                "late"
            }
        }
        assertNull(r)
        assertTrue("returned promptly", System.currentTimeMillis() - start < 1_500)
    }

    @Test fun `results and errors come through`() = runBlocking {
        assertEquals(42, abandonable { 42 })
        assertTrue(runCatching { abandonable { error("boom") } }.isFailure)
    }
}

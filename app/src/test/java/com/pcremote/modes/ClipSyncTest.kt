package com.pcremote.modes

import com.pcremote.agent.Msg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClipSyncTest {
    @Test fun `local copy is sent to the PC`() {
        val s = ClipSync()
        assertEquals(Msg.Clip("text/plain", "hello"), s.onLocalChange("hello"))
    }

    @Test fun noEcho() {
        val s = ClipSync()
        assertEquals("from pc", s.onRemote(Msg.Clip("text/plain", "from pc")))
        // setting the phone clipboard fires the local listener: must not bounce back
        assertNull(s.onLocalChange("from pc"))
        // a genuinely new copy still goes out
        assertEquals(Msg.Clip("text/plain", "new"), s.onLocalChange("new"))
    }

    @Test fun `same local text is not resent`() {
        val s = ClipSync()
        s.onLocalChange("x")
        assertNull(s.onLocalChange("x"))
    }

    @Test fun `oversized text is not sent`() {
        assertNull(ClipSync().onLocalChange("a".repeat(1_000_001)))
    }

    @Test fun `images from the PC are ignored on the phone`() {
        assertNull(ClipSync().onRemote(Msg.Clip("image/png", "iVBOR")))
    }
}

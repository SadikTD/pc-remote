package com.pcremote.stream

import com.limelight.nvstream.input.KeyboardPacket.KEY_DOWN
import com.limelight.nvstream.input.KeyboardPacket.KEY_UP
import com.limelight.nvstream.input.KeyboardPacket.MODIFIER_CTRL
import org.junit.Assert.assertEquals
import org.junit.Test

class ShortcutsTest {
    private fun km(vk: Int) = ((0x80 shl 8) or vk).toShort()

    @Test fun `single key is down then up`() {
        assertEquals(listOf(KeyEvt(km(0xB3), KEY_DOWN, 0), KeyEvt(km(0xB3), KEY_UP, 0)),
            Shortcut.PLAY_PAUSE.events())
    }

    @Test fun `ctrl c presses ctrl first, releases it last, carries modifier`() {
        assertEquals(listOf(
            KeyEvt(km(0x11), KEY_DOWN, MODIFIER_CTRL),
            KeyEvt(km(0x43), KEY_DOWN, MODIFIER_CTRL),
            KeyEvt(km(0x43), KEY_UP, MODIFIER_CTRL),
            KeyEvt(km(0x11), KEY_UP, 0),
        ), Shortcut.COPY.events())
    }

    @Test fun `every shortcut releases everything it pressed`() {
        for (s in Shortcut.entries) {
            val ev = s.events()
            assertEquals(s.name, ev.count { it.direction == KEY_DOWN }, ev.count { it.direction == KEY_UP })
            assertEquals(s.name, 0.toByte(), ev.last().modifiers)
        }
    }
}

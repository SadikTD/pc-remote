package com.pcremote.stream

import com.limelight.nvstream.input.KeyboardPacket.KEY_DOWN
import com.limelight.nvstream.input.KeyboardPacket.KEY_UP
import com.limelight.nvstream.input.KeyboardPacket.MODIFIER_CTRL
import com.limelight.nvstream.input.KeyboardPacket.MODIFIER_SHIFT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardBarModelTest {
    private fun km(vk: Int) = ((0x80 shl 8) or vk).toShort()

    @Test fun `one-shot ctrl plus C equals the copy shortcut and then releases`() {
        val m = KeyboardBarModel()
        m.tap(Mod.CTRL, nowMs = 0)
        assertEquals(ModState.ONE_SHOT, m.state(Mod.CTRL))
        assertEquals(Shortcut.COPY.events(), m.key(0x43))
        assertEquals(ModState.OFF, m.state(Mod.CTRL))
    }

    @Test fun `double tap locks until tapped again`() {
        val m = KeyboardBarModel()
        m.tap(Mod.SHIFT, 0); m.tap(Mod.SHIFT, 200)
        assertEquals(ModState.LOCKED, m.state(Mod.SHIFT))
        m.key(0x41); m.key(0x42)
        assertEquals(ModState.LOCKED, m.state(Mod.SHIFT))
        m.tap(Mod.SHIFT, 5_000)
        assertEquals(ModState.OFF, m.state(Mod.SHIFT))
    }

    @Test fun `slow second tap turns one-shot off instead of locking`() {
        val m = KeyboardBarModel()
        m.tap(Mod.ALT, 0); m.tap(Mod.ALT, 1_000)
        assertEquals(ModState.OFF, m.state(Mod.ALT))
    }

    @Test fun `plain key with no modifiers is down then up`() {
        assertEquals(listOf(KeyEvt(km(0x1B), KEY_DOWN, 0), KeyEvt(km(0x1B), KEY_UP, 0)), KeyboardBarModel().key(0x1B))
    }

    @Test fun `keyboard typing is wrapped by active modifiers and consumes one-shots`() {
        val m = KeyboardBarModel()
        m.tap(Mod.CTRL, 0); m.tap(Mod.SHIFT, 0)
        val pre = m.downPrefix()
        assertEquals(listOf(KeyEvt(km(0x11), KEY_DOWN, MODIFIER_CTRL), KeyEvt(km(0x10), KEY_DOWN, (MODIFIER_CTRL.toInt() or MODIFIER_SHIFT.toInt()).toByte())), pre)
        assertEquals((MODIFIER_CTRL.toInt() or MODIFIER_SHIFT.toInt()).toByte(), m.activeMask())
        val post = m.upSuffix()
        assertEquals(listOf(KeyEvt(km(0x10), KEY_UP, MODIFIER_CTRL), KeyEvt(km(0x11), KEY_UP, 0)), post)
        assertTrue(Mod.entries.all { m.state(it) == ModState.OFF })
    }

    @Test fun `release matches what was pressed even if the bar changed in between`() {
        val m = KeyboardBarModel()
        m.tap(Mod.CTRL, 0)
        m.downPrefix()
        m.tap(Mod.CTRL, 5_000) // user turned Ctrl off while the key was held
        assertEquals(listOf(KeyEvt(km(0x11), KEY_UP, 0)), m.upSuffix())
    }

    @Test fun `releaseHeld lets go of anything still down`() {
        val m = KeyboardBarModel()
        m.tap(Mod.WIN, 0)
        m.downPrefix()
        assertEquals(listOf(KeyEvt(km(0x5B), KEY_UP, 0)), m.releaseHeld())
        assertTrue(m.releaseHeld().isEmpty())
    }
}

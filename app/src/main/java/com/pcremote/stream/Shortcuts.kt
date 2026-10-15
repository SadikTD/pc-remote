package com.pcremote.stream

import com.limelight.nvstream.input.KeyboardPacket

data class KeyEvt(val keyMap: Short, val direction: Byte, val modifiers: Byte)

private const val VK_CONTROL = 0x11
private const val VK_MENU = 0x12 // Alt
private const val VK_LWIN = 0x5B

private fun modifierBit(vk: Int): Int = when (vk) {
    VK_CONTROL -> KeyboardPacket.MODIFIER_CTRL.toInt()
    VK_MENU -> KeyboardPacket.MODIFIER_ALT.toInt()
    VK_LWIN -> KeyboardPacket.MODIFIER_META.toInt()
    else -> 0
}

/** Keys are pressed in order and released in reverse order. Windows virtual-key codes. */
enum class Shortcut(private vararg val vks: Int) {
    WIN(VK_LWIN), ALT_TAB(VK_MENU, 0x09), COPY(VK_CONTROL, 0x43), PASTE(VK_CONTROL, 0x56), ESC(0x1B),
    PLAY_PAUSE(0xB3), PREV(0xB1), NEXT(0xB0), VOL_DOWN(0xAE), VOL_UP(0xAF), MUTE(0xAD);

    fun events(): List<KeyEvt> {
        val out = ArrayList<KeyEvt>()
        var mods = 0
        for (vk in vks) {
            mods = mods or modifierBit(vk)
            out += KeyEvt(keyMap(vk), KeyboardPacket.KEY_DOWN, mods.toByte())
        }
        // Release in reverse. A modifier key's own release already reports it as not held.
        for (vk in vks.reversed()) {
            mods = mods and modifierBit(vk).inv()
            out += KeyEvt(keyMap(vk), KeyboardPacket.KEY_UP, mods.toByte())
        }
        return out
    }

    // Same encoding as Moonlight's KeyboardTranslator: 0x80 prefix in the high byte.
    private fun keyMap(vk: Int) = ((0x80 shl 8) or vk).toShort()
}

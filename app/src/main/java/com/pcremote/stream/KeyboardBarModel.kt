package com.pcremote.stream

enum class Mod(val vk: Int) { CTRL(0x11), ALT(0x12), SHIFT(0x10), WIN(0x5B) }

enum class ModState { OFF, ONE_SHOT, LOCKED }

/**
 * Sticky modifiers for the keyboard bar: tap = one-shot (applies to the next key),
 * double-tap = locked until tapped again.
 */
class KeyboardBarModel {
    private val states = mutableMapOf<Mod, ModState>().withDefault { ModState.OFF }
    private val lastTap = mutableMapOf<Mod, Long>()

    fun state(m: Mod): ModState = states.getValue(m)

    fun tap(m: Mod, nowMs: Long) {
        val prev = lastTap[m]
        states[m] = when (state(m)) {
            ModState.OFF -> ModState.ONE_SHOT
            ModState.ONE_SHOT -> if (prev != null && nowMs - prev <= DOUBLE_TAP_MS) ModState.LOCKED else ModState.OFF
            ModState.LOCKED -> ModState.OFF
        }
        lastTap[m] = nowMs
    }

    private fun active() = Mod.entries.filter { state(it) != ModState.OFF }.map { it.vk }

    private fun consumeOneShots() = Mod.entries.forEach { if (state(it) == ModState.ONE_SHOT) states[it] = ModState.OFF }

    /** A full key press with the active modifiers around it. */
    fun key(vk: Int): List<KeyEvt> = chord(active() + vk).also { consumeOneShots() }

    /** Modifier downs to send before a key typed elsewhere (soft or hardware keyboard). */
    fun downPrefix(): List<KeyEvt> = pressAll(active())

    /** Modifier mask to put on that key's own events. */
    fun activeMask(): Byte = maskOf(active())

    /** Modifier ups to send after that key is released; consumes one-shots. */
    fun upSuffix(): List<KeyEvt> = releaseAll(active()).also { consumeOneShots() }

    fun anyActive() = active().isNotEmpty()

    private companion object { const val DOUBLE_TAP_MS = 400L }
}

package com.pcremote.settings

import com.pcremote.agent.KeyValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsModelTest {
    private class MemKv : KeyValue {
        val map = mutableMapOf<String, String>()
        override fun get(key: String) = map[key]
        override fun put(key: String, value: String?) { if (value == null) map.remove(key) else map[key] = value }
    }

    @Test fun `pointer speed is clamped to half to triple`() {
        val s = SettingsModel(MemKv())
        s.pointerSpeed = 10f; assertEquals(3f, s.pointerSpeed, 0.001f)
        s.pointerSpeed = 0.1f; assertEquals(0.5f, s.pointerSpeed, 0.001f)
        s.pointerSpeed = 1.7f; assertEquals(1.7f, s.pointerSpeed, 0.001f)
    }

    @Test fun `natural scrolling inverts dy`() {
        assertEquals(-120, ScrollMapper.map(120, natural = true))
        assertEquals(120, ScrollMapper.map(120, natural = false))
    }

    @Test fun `theme round-trips and defaults to dark`() {
        val kv = MemKv()
        assertEquals(ThemeChoice.DARK, SettingsModel(kv).theme)
        SettingsModel(kv).theme = ThemeChoice.LIGHT
        assertEquals(ThemeChoice.LIGHT, SettingsModel(kv).theme)
        kv.map["pcremote_theme"] = "garbage"
        assertEquals(ThemeChoice.DARK, SettingsModel(kv).theme)
    }

    @Test fun `haptics default on and persist`() {
        val kv = MemKv()
        assertTrue(SettingsModel(kv).haptics)
        SettingsModel(kv).haptics = false
        assertFalse(SettingsModel(kv).haptics)
    }

    @Test fun `gyro sensitivity clamped`() {
        val s = SettingsModel(MemKv())
        s.gyroSensitivity = 9f; assertEquals(3f, s.gyroSensitivity, 0.001f)
        s.gyroSensitivity = 0f; assertEquals(0.25f, s.gyroSensitivity, 0.001f)
    }
}

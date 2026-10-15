package com.pcremote.settings

import android.content.Context
import android.preference.PreferenceManager
import com.pcremote.agent.KeyValue

enum class ThemeChoice { SYSTEM, DARK, LIGHT }

/** App preferences, all stored as strings so the model is testable over a plain [KeyValue]. */
class SettingsModel(private val kv: KeyValue) {
    private fun float(key: String, def: Float) = kv.get(key)?.toFloatOrNull() ?: def
    private fun bool(key: String, def: Boolean) = kv.get(key)?.toBooleanStrictOrNull() ?: def

    var theme: ThemeChoice
        get() = kv.get(K_THEME)?.let { v -> ThemeChoice.entries.firstOrNull { it.name.equals(v, true) } } ?: ThemeChoice.DARK
        set(v) = kv.put(K_THEME, v.name.lowercase())

    var dynamicColor: Boolean
        get() = bool(K_DYNAMIC, false)
        set(v) = kv.put(K_DYNAMIC, v.toString())

    var haptics: Boolean
        get() = bool(K_HAPTICS, true)
        set(v) = kv.put(K_HAPTICS, v.toString())

    var naturalScroll: Boolean
        get() = bool(K_NATURAL, false)
        set(v) = kv.put(K_NATURAL, v.toString())

    var pointerSpeed: Float
        get() = float(K_POINTER, 1f)
        set(v) = kv.put(K_POINTER, v.coerceIn(0.5f, 3f).toString())

    var gyroSensitivity: Float
        get() = float(K_GYRO, 1f)
        set(v) = kv.put(K_GYRO, v.coerceIn(0.25f, 3f).toString())

    companion object {
        const val K_THEME = "pcremote_theme"
        const val K_DYNAMIC = "pcremote_dynamic_s"
        const val K_HAPTICS = "pcremote_haptics_s"
        const val K_NATURAL = "pcremote_natural_scroll"
        const val K_POINTER = "pcremote_pointer_speed"
        const val K_GYRO = "pcremote_gyro_sensitivity"

        fun get(ctx: Context) = SettingsModel(prefsKv(ctx))
    }
}

object ScrollMapper {
    fun map(dy: Int, natural: Boolean) = if (natural) -dy else dy
}

/** Default SharedPreferences as a [KeyValue] (string values only). */
fun prefsKv(ctx: Context): KeyValue {
    @Suppress("DEPRECATION")
    val prefs = PreferenceManager.getDefaultSharedPreferences(ctx.applicationContext)
    return object : KeyValue {
        override fun get(key: String) = runCatching { prefs.getString(key, null) }.getOrNull()
        override fun put(key: String, value: String?) { prefs.edit().putString(key, value).apply() }
    }
}

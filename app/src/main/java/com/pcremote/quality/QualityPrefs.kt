@file:Suppress("DEPRECATION") // default SharedPreferences, shared with the rest of the app's settings

package com.pcremote.quality

import android.content.Context
import android.preference.PreferenceManager

object QualityPrefs {
    private const val KEY_PRESET = "pcremote_preset"
    private const val KEY_AUTO = "pcremote_auto_quality"

    private fun prefs(ctx: Context) = PreferenceManager.getDefaultSharedPreferences(ctx)

    fun apply(ctx: Context, preset: QualityPreset) = prefs(ctx).edit().putString(KEY_PRESET, preset.name).apply()

    fun current(ctx: Context): QualityPreset =
        prefs(ctx).getString(KEY_PRESET, null)?.let { runCatching { QualityPreset.valueOf(it) }.getOrNull() }
            ?: QualityPreset.SMOOTH

    fun isAuto(ctx: Context) = prefs(ctx).getBoolean(KEY_AUTO, true)
    fun setAuto(ctx: Context, auto: Boolean) = prefs(ctx).edit().putBoolean(KEY_AUTO, auto).apply()
}

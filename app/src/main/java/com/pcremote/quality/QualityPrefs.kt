@file:Suppress("DEPRECATION") // upstream Moonlight reads the default SharedPreferences via the same API

package com.pcremote.quality

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.preference.PreferenceManager

object QualityPrefs {
    private const val KEY_PRESET = "pcremote_preset"
    private const val KEY_AUTO = "pcremote_auto_quality"
    private const val KEY_HINT_SHOWN = "pcremote_hint_shown_"

    private fun prefs(ctx: Context) = PreferenceManager.getDefaultSharedPreferences(ctx)

    fun apply(ctx: Context, preset: QualityPreset, bitrateKbps: Int = preset.bitrateKbps) {
        prefs(ctx).edit()
            .putString(KEY_PRESET, preset.name)
            .putString("list_resolution", "${preset.width}x${preset.height}")
            .putString("list_fps", preset.fps.toString())
            .putInt("seekbar_bitrate_kbps", bitrateKbps)
            .putBoolean("checkbox_enable_perf_overlay", true) // stats must be on for the toolbar readout; toolbar toggles visibility
            .apply()
    }

    fun current(ctx: Context): QualityPreset =
        prefs(ctx).getString(KEY_PRESET, null)?.let { runCatching { QualityPreset.valueOf(it) }.getOrNull() }
            ?: QualityPreset.SMOOTH

    fun isAuto(ctx: Context) = prefs(ctx).getBoolean(KEY_AUTO, true)
    fun setAuto(ctx: Context, auto: Boolean) = prefs(ctx).edit().putBoolean(KEY_AUTO, auto).apply()

    fun readLink(ctx: Context): LinkInfo? {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
        val info = ctx.applicationContext.getSystemService(WifiManager::class.java).connectionInfo ?: return null
        if (info.linkSpeed <= 0) return null
        return LinkInfo(info.linkSpeed, info.frequency, info.rssi)
    }

    /** Each hint is shown once per install. */
    fun shouldShowHint(ctx: Context, hint: WifiHint): Boolean {
        val key = KEY_HINT_SHOWN + hint.name
        if (prefs(ctx).getBoolean(key, false)) return false
        prefs(ctx).edit().putBoolean(key, true).apply()
        return true
    }
}

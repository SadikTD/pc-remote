package com.pcremote.ui.theme

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** Subtle haptics for controls; all of them respect the Settings toggle. */
class Haptics(private val ctx: Context) {
    private val vib: Vibrator? = if (Build.VERSION.SDK_INT >= 31)
        ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
    else @Suppress("DEPRECATION") ctx.getSystemService(Vibrator::class.java)

    private fun enabled() = com.pcremote.settings.SettingsModel.get(ctx).haptics

    private fun play(predefined: Int, fallbackMs: Long, amp: Int) {
        val v = vib ?: return
        if (!enabled() || !v.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= 29) v.vibrate(VibrationEffect.createPredefined(predefined))
        else v.vibrate(VibrationEffect.createOneShot(fallbackMs, amp))
    }

    fun tick() = play(VibrationEffect.EFFECT_TICK, 8, 60)
    fun click() = play(VibrationEffect.EFFECT_CLICK, 14, 140)
    fun heavy() = play(VibrationEffect.EFFECT_HEAVY_CLICK, 24, 255)
}

@Composable
fun rememberHaptics(): Haptics {
    val ctx = LocalContext.current
    return remember(ctx) { Haptics(ctx.applicationContext) }
}

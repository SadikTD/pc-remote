package com.pcremote.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Palette as Compose colours, read via [LocalMidnight]. */
@Immutable
data class MidnightColors(
    val bg: Color, val surface: Color, val surfaceHigh: Color, val border: Color,
    val accent: Color, val accent2: Color, val onAccent: Color,
    val ok: Color, val warn: Color, val bad: Color, val text: Color, val textDim: Color,
    val isDark: Boolean,
)

private fun Palette.toColors(dark: Boolean) = MidnightColors(
    Color(bg), Color(surface), Color(surfaceHigh), Color(border),
    Color(accent), Color(accent2), Color(onAccent),
    Color(ok), Color(warn), Color(bad), Color(text), Color(textDim), dark,
)

val LocalMidnight = staticCompositionLocalOf { Midnight.dark.toColors(true) }

object MidnightShapes {
    val card = RoundedCornerShape(24.dp)
    val control = RoundedCornerShape(16.dp)
    val pill = RoundedCornerShape(50)
}

private val type = Typography().run {
    copy(
        displayLarge = displayLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-1.5).sp),
        displayMedium = displayMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp),
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp),
    )
}

/** Big tabular numbers for latency, PIN, timers. */
val NumberStyle = TextStyle(fontSize = 44.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp,
    fontFeatureSettings = "tnum")

@Composable
fun MidnightTheme(
    dark: Boolean = isSystemInDarkTheme(),
    dynamic: Boolean = false,
    content: @Composable () -> Unit,
) {
    val base = (if (dark) Midnight.dark else Midnight.light).toColors(dark)
    val ctx = LocalContext.current
    val dyn = if (dynamic && Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    } else null
    val c = if (dyn != null) base.copy(accent = dyn.primary, onAccent = dyn.onPrimary, accent2 = dyn.tertiary) else base
    val scheme = (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = c.accent, onPrimary = c.onAccent, secondary = c.accent2, tertiary = c.accent2,
        background = c.bg, onBackground = c.text, surface = c.surface, onSurface = c.text,
        surfaceVariant = c.surfaceHigh, onSurfaceVariant = c.textDim, surfaceContainer = c.surface,
        surfaceContainerHigh = c.surfaceHigh, surfaceContainerHighest = c.surfaceHigh,
        surfaceContainerLow = c.surface, surfaceContainerLowest = c.bg,
        outline = c.textDim, outlineVariant = c.border, error = c.bad,
    )
    CompositionLocalProvider(LocalMidnight provides c) {
        MaterialTheme(colorScheme = scheme, typography = type, content = content)
    }
}

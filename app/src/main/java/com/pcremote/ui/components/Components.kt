package com.pcremote.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pcremote.ui.theme.LocalMidnight
import com.pcremote.ui.theme.MidnightShapes
import com.pcremote.ui.theme.Springs
import com.pcremote.ui.theme.rememberHaptics

/** Slowly drifting accent glows behind hero content. */
@Composable
fun Aurora(modifier: Modifier = Modifier, intensity: Float = 1f) {
    val c = LocalMidnight.current
    val t by rememberInfiniteTransition(label = "aurora").animateFloat(
        0f, 1f, infiniteRepeatable(tween(14_000, easing = LinearEasing), RepeatMode.Reverse), label = "t",
    )
    Canvas(modifier.fillMaxSize()) {
        val w = size.width; val h = size.height
        fun glow(color: Color, x: Float, y: Float, r: Float) = drawCircle(
            Brush.radialGradient(listOf(color.copy(alpha = 0.35f * intensity), Color.Transparent), Offset(x, y), r),
            r, Offset(x, y),
        )
        glow(c.accent, w * (0.2f + 0.3f * t), h * (0.25f - 0.08f * t), w * 0.9f)
        glow(c.accent2, w * (0.9f - 0.35f * t), h * (0.15f + 0.12f * t), w * 0.7f)
    }
}

/** Primary pill button with a gradient fill, press scale and haptic tick. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null) {
    val c = LocalMidnight.current
    val haptics = rememberHaptics()
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, Springs.bouncy(), label = "s")
    Row(
        modifier
            .scale(scale)
            .clip(MidnightShapes.pill)
            .background(if (enabled) Brush.horizontalGradient(listOf(c.accent, c.accent2)) else Brush.linearGradient(listOf(c.surfaceHigh, c.surfaceHigh)))
            .clickable(src, null, enabled, role = Role.Button) { haptics.click(); onClick() }
            .padding(horizontal = 28.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, null, tint = c.onAccent, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(10.dp)) }
        Text(text, color = if (enabled) c.onAccent else c.textDim, style = MaterialTheme.typography.labelLarge)
    }
}

/** Quiet secondary pill. */
@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val c = LocalMidnight.current
    val haptics = rememberHaptics()
    Row(
        modifier.clip(MidnightShapes.pill).border(1.dp, c.border, MidnightShapes.pill)
            .clickable(role = Role.Button) { haptics.tick(); onClick() }.padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, null, tint = c.text, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, color = c.text, style = MaterialTheme.typography.labelLarge)
    }
}

/** Raised glass card used across the app. */
@Composable
fun GlassCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalMidnight.current
    val haptics = rememberHaptics()
    Column(
        modifier.clip(MidnightShapes.card).background(c.surface.copy(alpha = 0.92f)).border(1.dp, c.border, MidnightShapes.card)
            .then(if (onClick != null) Modifier.clickable { haptics.tick(); onClick() } else Modifier)
            .padding(20.dp),
        content = content,
    )
}

/** A status dot that breathes while [live]. */
@Composable
fun StatusDot(color: Color, live: Boolean, size: Dp = 10.dp) {
    val pulse by rememberInfiniteTransition(label = "dot").animateFloat(
        1f, 2.2f, infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing)), label = "p",
    )
    val col by animateColorAsState(color, label = "c")
    Box(Modifier.size(size * 2.2f), contentAlignment = Alignment.Center) {
        if (live) Box(Modifier.size(size).scale(pulse).clip(CircleShape).background(col.copy(alpha = (2.2f - pulse) / 3f)))
        Box(Modifier.size(size).clip(CircleShape).background(col))
    }
}

/** Animated page indicator: the active dot stretches into a pill. */
@Composable
fun PagerDots(count: Int, current: Int) {
    val c = LocalMidnight.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(count) { i ->
            val w by animateDpAsState(if (i == current) 24.dp else 8.dp, Springs.snappy(), label = "w")
            Box(Modifier.height(8.dp).width(w).clip(CircleShape).background(if (i == current) c.accent else c.surfaceHigh))
        }
    }
}

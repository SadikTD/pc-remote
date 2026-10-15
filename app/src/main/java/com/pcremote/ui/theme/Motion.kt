package com.pcremote.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring

/** Spring physics everywhere; no linear easing. */
object Springs {
    fun <T> snappy() = spring<T>(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)
    fun <T> gentle() = spring<T>(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow)
    fun <T> bouncy() = spring<T>(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)
}

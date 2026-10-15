package com.pcremote.stream

import com.limelight.R
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

enum class LinkState { GOOD, POOR, RECONNECTING }

class ToolbarState {
    var link by mutableStateOf(LinkState.GOOD)
    var perfText by mutableStateOf("")
    var showPerf by mutableStateOf(false)
    var trackpad by mutableStateOf(true)
    var offerLowerQuality by mutableStateOf(false)
    var keyboardOpen by mutableStateOf(false)
    var coachShown by mutableStateOf(true)
    var connecting by mutableStateOf<String?>(null)
    var connected by mutableStateOf(false)
    var pcName by mutableStateOf<String?>(null)
    val bar = KeyboardBarModel()
    var barTick by mutableIntStateOf(0)
}

interface ToolbarActions {
    fun shortcut(s: Shortcut)
    fun keyboard()
    fun setTrackpad(on: Boolean)
    fun lowerQuality()
    fun disconnect()
    fun keys(events: List<KeyEvt>)
    fun coachDone()
}

/** Game is a plain Activity (no lifecycle owner), so the overlay brings its own, alive while attached. */
private class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry

    init {
        saved.performRestore(null)
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() { registry.currentState = Lifecycle.State.DESTROYED }
}

object StreamToolbarHost {
    @JvmStatic
    fun attach(parent: FrameLayout, state: ToolbarState, actions: ToolbarActions): android.view.View {
        val owner = OverlayOwner()
        val view = ComposeView(parent.context).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: android.view.View) {}
                override fun onViewDetachedFromWindow(v: android.view.View) = owner.destroy()
            })
            setContent { com.pcremote.ui.theme.MidnightTheme(dark = true) { StreamOverlay(state, actions) } }
        }
        parent.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        return view
    }
}


package com.pcremote.stream

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
    var showPerf by mutableStateOf(true)
    var trackpad by mutableStateOf(true)
    var offerLowerQuality by mutableStateOf(false)
}

interface ToolbarActions {
    fun shortcut(s: Shortcut)
    fun keyboard()
    fun setTrackpad(on: Boolean)
    fun lowerQuality()
    fun disconnect()
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
            setContent { MaterialTheme(colorScheme = darkColorScheme()) { StreamToolbar(state, actions) } }
        }
        parent.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        return view
    }
}

@Composable
private fun StreamToolbar(state: ToolbarState, a: ToolbarActions) {
    var open by remember { mutableStateOf(false) }
    var media by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().padding(12.dp)) {
        // Top-left: connection dot + live stats
        Row(Modifier.align(Alignment.TopStart).background(Color(0x99000000), RoundedCornerShape(8.dp)).padding(6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            val c = when (state.link) {
                LinkState.GOOD -> Color(0xFF34C759)
                LinkState.POOR -> Color(0xFFFFCC00)
                LinkState.RECONNECTING -> Color(0xFFFF3B30)
            }
            Box(Modifier.size(10.dp).background(c, CircleShape))
            if (state.showPerf && state.perfText.isNotEmpty()) {
                Spacer(Modifier.width(6.dp))
                Text(state.perfText, color = Color.White, fontSize = 11.sp, lineHeight = 13.sp)
            }
        }
        if (state.offerLowerQuality) {
            AssistChip(onClick = a::lowerQuality, label = { Text("Lower quality for smoother stream") },
                modifier = Modifier.align(Alignment.TopCenter))
        }
        // Bottom-right: collapsible toolbar
        Column(Modifier.align(Alignment.BottomEnd), horizontalAlignment = Alignment.End) {
            AnimatedVisibility(open && media) {
                Bar {
                    Btn(Icons.Default.SkipPrevious, "Previous") { a.shortcut(Shortcut.PREV) }
                    Btn(Icons.Default.PlayArrow, "Play/Pause") { a.shortcut(Shortcut.PLAY_PAUSE) }
                    Btn(Icons.Default.SkipNext, "Next") { a.shortcut(Shortcut.NEXT) }
                    Btn(Icons.Default.VolumeDown, "Volume down") { a.shortcut(Shortcut.VOL_DOWN) }
                    Btn(Icons.Default.VolumeUp, "Volume up") { a.shortcut(Shortcut.VOL_UP) }
                    Btn(Icons.Default.VolumeOff, "Mute") { a.shortcut(Shortcut.MUTE) }
                }
            }
            AnimatedVisibility(open) {
                Bar {
                    Btn(Icons.Default.Keyboard, "Keyboard") { a.keyboard() }
                    Btn(Icons.Default.MusicNote, "Media keys") { media = !media }
                    Btn(Icons.Default.Window, "Windows key") { a.shortcut(Shortcut.WIN) }
                    Btn(Icons.Default.SwapHoriz, "Alt+Tab") { a.shortcut(Shortcut.ALT_TAB) }
                    Btn(Icons.Default.ContentCopy, "Copy") { a.shortcut(Shortcut.COPY) }
                    Btn(Icons.Default.ContentPaste, "Paste") { a.shortcut(Shortcut.PASTE) }
                    Btn(Icons.Default.Close, "Escape") { a.shortcut(Shortcut.ESC) }
                    Btn(if (state.trackpad) Icons.Default.Mouse else Icons.Default.TouchApp,
                        if (state.trackpad) "Trackpad mode (tap to switch to direct touch)" else "Direct touch (tap to switch to trackpad)") {
                        state.trackpad = !state.trackpad; a.setTrackpad(state.trackpad)
                    }
                    Btn(Icons.Default.Speed, "Show stats") { state.showPerf = !state.showPerf }
                    Btn(Icons.Default.PowerSettingsNew, "Disconnect") { a.disconnect() }
                }
            }
            SmallFloatingActionButton(onClick = { open = !open }, containerColor = Color(0xCC222222)) {
                Icon(if (open) Icons.Default.ExpandMore else Icons.Default.Menu, contentDescription = "Toolbar", tint = Color.White)
            }
        }
    }
}

@Composable
private fun Bar(content: @Composable RowScope.() -> Unit) =
    Row(Modifier.padding(bottom = 8.dp).background(Color(0xCC222222), RoundedCornerShape(24.dp)).padding(4.dp), content = content)

@Composable
private fun Btn(icon: ImageVector, label: String, onClick: () -> Unit) =
    IconButton(onClick = onClick) { Icon(icon, contentDescription = label, tint = Color.White) }

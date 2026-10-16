package com.pcremote.stream

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class LinkState { GOOD, POOR, RECONNECTING }

/** What the stream overlay shows; the Screen mode updates it. */
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
    /** The PC's displays (only offered when there are several) and the one being streamed. */
    var displays by mutableStateOf<List<com.pcremote.agent.DisplayInfo>>(emptyList())
    var display by mutableStateOf("")
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
    fun selectDisplay(id: String)
}

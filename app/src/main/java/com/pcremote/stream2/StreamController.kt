package com.pcremote.stream2

import com.pcremote.agent.Msg
import com.pcremote.prs.CodecPicker
import com.pcremote.prs.Stats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** A running receive → decode → render chain for one `stream.ready`. */
interface Pipeline {
    val stats: StateFlow<Stats>
    fun sendInput(m: Msg)
    /** Same session, new size (the host stepped resolution). */
    fun reconfigure(ready: Msg.StreamReady)
    fun close()
}

sealed interface StreamUi {
    data object Connecting : StreamUi
    data class Streaming(val codec: String, val w: Int, val h: Int) : StreamUi
    data class Failed(val code: String, val msg: String) : StreamUi
}

data class StreamRequest(val w: Int, val h: Int, val fps: Int, val maxKbps: Int, val audio: Boolean = true)

/**
 * Drives one Screen session over the agent: `stream.start` → `stream.ready` → pipeline; follows
 * resolution changes; falls back to the next codec if the decoder can't take the stream.
 */
class StreamController(
    private val scope: CoroutineScope,
    private val send: (Msg) -> Boolean,
    private val incoming: Flow<Msg>,
    /** Builds the pipeline; `onDecoderFailed` must be called if the chosen codec can't be decoded. */
    private val pipelines: (ready: Msg.StreamReady, onDecoderFailed: () -> Unit) -> Pipeline,
    private var codecs: List<String>,
    private val timeoutMs: Long = 8_000,
    /** Called before every `stream.start`: opens a fresh socket and returns its candidates. */
    private val prepare: suspend () -> List<String> = { emptyList() },
) {
    private val _ui = MutableStateFlow<StreamUi>(StreamUi.Connecting)
    val ui: StateFlow<StreamUi> = _ui
    @Volatile var pipeline: Pipeline? = null; private set
    private var session: Long? = null
    private var request: StreamRequest? = null
    private var listen: Job? = null
    private var timeout: Job? = null

    fun start(r: StreamRequest) {
        request = r
        if (listen == null) listen = scope.launch { incoming.collect(::onMsg) }
        ask()
    }

    private fun ask() {
        val r = request ?: return
        if (codecs.isEmpty()) { _ui.value = StreamUi.Failed("no_decoder", ""); return }
        _ui.value = StreamUi.Connecting
        // the agent may still be (re)connecting: keep asking until it answers or we time out
        timeout?.cancel()
        timeout = scope.launch {
            val cands = prepare()
            repeat(maxOf(1, (timeoutMs / ASK_EVERY_MS).toInt())) {
                send(Msg.StreamStart(r.w, r.h, r.fps, codecs, r.maxKbps, r.audio, cands))
                delay(ASK_EVERY_MS)
                if (session != null) return@launch
            }
            if (session == null) _ui.value = StreamUi.Failed("timeout", "")
        }
    }

    private fun onMsg(m: Msg) {
        when (m) {
            is Msg.StreamReady -> {
                timeout?.cancel()
                val p = pipeline
                if (p != null && m.session == session) p.reconfigure(m)
                else {
                    p?.close()
                    session = m.session
                    val codec = m.codec
                    pipeline = pipelines(m) { scope.launch { decoderFailed(codec) } }
                }
                _ui.value = StreamUi.Streaming(m.codec, m.w, m.h)
            }
            is Msg.StreamError -> if (_ui.value !is StreamUi.Streaming || m.code != "busy") {
                timeout?.cancel(); teardown(); _ui.value = StreamUi.Failed(m.code, m.msg)
            }
            else -> Unit
        }
    }

    private fun decoderFailed(codec: String) {
        teardown()
        send(Msg.StreamStop)
        codecs = CodecPicker.fallback(codecs, codec)
        ask()
    }

    fun sendInput(m: Msg) { pipeline?.sendInput(m) }

    fun stop() {
        timeout?.cancel(); listen?.cancel(); listen = null
        if (pipeline != null || session != null) send(Msg.StreamStop)
        teardown()
    }

    private companion object { const val ASK_EVERY_MS = 1_500L }

    private fun teardown() { pipeline?.close(); pipeline = null; session = null }
}

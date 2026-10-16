package com.pcremote.stream2

import com.pcremote.agent.Msg
import com.pcremote.prs.Stats
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StreamControllerTest {
    private class FakePipeline(val ready: Msg.StreamReady, val fail: () -> Unit) : Pipeline {
        override val stats = MutableStateFlow(Stats())
        val inputs = ArrayList<Msg>(); val resized = ArrayList<Msg.StreamReady>(); var closed = false
        override fun sendInput(m: Msg) { inputs += m }
        override fun reconfigure(ready: Msg.StreamReady) { resized += ready }
        override fun close() { closed = true }
    }

    private val scope = TestScope()
    private val sent = ArrayList<Msg>()
    private val incoming = MutableSharedFlow<Msg>(extraBufferCapacity = 8)
    private val made = ArrayList<FakePipeline>()
    private val c = StreamController(scope, { sent += it; true }, incoming, { r, f -> FakePipeline(r, f).also { made += it } }, listOf("hevc", "h264"))
    private fun ready(codec: String = "hevc", w: Int = 1920, session: Long = 7) = Msg.StreamReady(codec, w, w * 9 / 16, 60, 5000, session, "a2V5")

    @Test fun startReadyReceivingThenStopOnLeave() = scope.run {
        c.start(StreamRequest(2400, 1080, 120, 40_000)); runCurrent()
        assertEquals(Msg.StreamStart(2400, 1080, 120, listOf("hevc", "h264"), 40_000), sent.single())
        assertEquals(StreamUi.Connecting, c.ui.value)
        incoming.tryEmit(ready()); runCurrent()
        assertEquals(StreamUi.Streaming("hevc", 1920, 1080), c.ui.value)
        c.sendInput(Msg.MouseMove(1, 2))
        assertEquals(listOf<Msg>(Msg.MouseMove(1, 2)), made.single().inputs)
        advanceTimeBy(10_000); runCurrent()
        assertEquals("no re-asking once streaming", 1, sent.count { it is Msg.StreamStart })
        c.stop()
        assertTrue(made.single().closed)
        assertEquals(Msg.StreamStop, sent.last())
    }

    @Test fun resolutionChangeReconfiguresSamePipeline() = scope.run {
        c.start(StreamRequest(1920, 1080, 60, 20_000)); runCurrent()
        incoming.tryEmit(ready()); runCurrent()
        incoming.tryEmit(ready(w = 1280)); runCurrent()
        assertEquals(1, made.size)
        assertEquals(1280, made.single().resized.single().w)
        assertEquals(StreamUi.Streaming("hevc", 1280, 720), c.ui.value)
    }

    @Test fun decoderFailureFallsBackToNextCodec() = scope.run {
        c.start(StreamRequest(1920, 1080, 60, 20_000)); runCurrent()
        incoming.tryEmit(ready("hevc")); runCurrent()
        made.single().fail(); runCurrent()
        assertTrue(made.single().closed)
        assertEquals(Msg.StreamStop, sent[1])
        assertEquals(listOf("h264"), (sent[2] as Msg.StreamStart).codecs)
        incoming.tryEmit(ready("h264", session = 8)); runCurrent()
        assertEquals(2, made.size)
        made.last().fail(); runCurrent()
        assertEquals(StreamUi.Failed("no_decoder", ""), c.ui.value)
    }

    @Test fun retriesWhileAgentConnectsThenTimesOut() = scope.run {
        c.start(StreamRequest(1920, 1080, 60, 20_000)); runCurrent()
        advanceTimeBy(4_000); runCurrent()
        assertTrue(sent.count { it is Msg.StreamStart } >= 3)
        advanceTimeBy(6_000); runCurrent()
        assertEquals(StreamUi.Failed("timeout", ""), c.ui.value)
    }

    @Test fun hostErrorShowsFailure() = scope.run {
        c.start(StreamRequest(1920, 1080, 60, 20_000)); runCurrent()
        incoming.tryEmit(Msg.StreamError("no_encoder", "no GPU encoder")); runCurrent()
        assertEquals(StreamUi.Failed("no_encoder", "no GPU encoder"), c.ui.value)
    }
}

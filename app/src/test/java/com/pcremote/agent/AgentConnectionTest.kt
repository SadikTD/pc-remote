package com.pcremote.agent

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A scripted fake server: each host either refuses, mismatches the cert, or answers via [reply]. */
private class FakeWs(
    private val behaviour: Map<String, String>, // host -> "refuse" | "mismatch" | "ok"
    private val reply: (Msg) -> List<Msg>,
) : WsFactory {
    val opened = mutableListOf<String>()
    var live: FakeSocket? = null
    var slow: FakeSocket? = null

    inner class FakeSocket(val l: WsListener) : Ws {
        val sent = mutableListOf<Msg>()
        override fun send(text: String): Boolean {
            val m = Wire.decode(text)!!
            sent += m
            reply(m).forEach { l.onText(Wire.encode(it)) }
            return true
        }
        override fun send(bytes: ByteArray) = true
        /** Like OkHttp, close callbacks arrive later; tests deliver them with [deliverClose]. */
        override fun close() {}
        fun deliverClose() = l.onClosed()
        fun drop() = l.onFailure(java.io.EOFException("drop"), false)
    }

    override fun open(host: String, port: Int, fingerprint: String, listener: WsListener): Ws {
        opened += host
        val s = FakeSocket(listener)
        when (behaviour[host]) {
            "ok" -> { live = s; listener.onOpen() }
            "slow" -> slow = s // opens only when the test says so
            "mismatch" -> listener.onFailure(javax.net.ssl.SSLHandshakeException("pin"), true)
            else -> listener.onFailure(java.net.ConnectException("refused"), false)
        }
        return s
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AgentConnectionTest {
    private val pc = PcInfo("id", "PC")
    private val fp = "a".repeat(64)

    private fun TestScope.conn(f: FakeWs, hosts: List<String> = listOf("h1", "h2")) =
        AgentConnection(hosts, 47999, fp, backgroundScope, f)

    private val server: (Msg) -> List<Msg> = { m ->
        when (m) {
            is Msg.Hello -> when (m.token) {
                "good" -> listOf(Msg.Welcome(1, pc, listOf("input")))
                "old" -> listOf(Msg.Error("revoked"))
                else -> listOf(Msg.Error("unauthorized"))
            }
            is Msg.Pair -> if (m.otp == "otp") listOf(Msg.Paired("newtok", pc)) else listOf(Msg.Error("bad_otp"))
            else -> emptyList()
        }
    }

    @Test fun allHostsUnreachableFailsWithReason() = runTest {
        val f = FakeWs(emptyMap(), server)
        val c = conn(f)
        c.hello("good", "Pixel")
        assertEquals(listOf("h1", "h2"), f.opened)
        assertEquals(AgentState.Failed(FailReason.UNREACHABLE), c.state.value)
    }

    @Test fun certMismatchStopsImmediately() = runTest {
        val f = FakeWs(mapOf("h1" to "mismatch", "h2" to "ok"), server)
        val c = conn(f)
        c.hello("good", "Pixel")
        assertEquals(listOf("h1"), f.opened)
        assertEquals(AgentState.Failed(FailReason.CERT_MISMATCH), c.state.value)
    }

    @Test fun helloSuccessGoesOnlineUsingSecondHost() = runTest {
        val f = FakeWs(mapOf("h2" to "ok"), server)
        val c = conn(f)
        c.hello("good", "Pixel")
        assertEquals(AgentState.Online(pc, setOf("input")), c.state.value)
    }

    @Test fun revokedErrorGoesFailedRevoked() = runTest {
        val c = conn(FakeWs(mapOf("h1" to "ok"), server))
        c.hello("old", "Pixel")
        assertEquals(AgentState.Failed(FailReason.REVOKED), c.state.value)
    }

    @Test fun pairReturnsToken() = runTest {
        val c = conn(FakeWs(mapOf("h1" to "ok"), server))
        assertEquals("newtok", c.pair("otp", "Pixel", "1234").getOrThrow())
        assertTrue(c.state.value is AgentState.Online)
    }

    @Test fun badOtp() = runTest {
        val c = conn(FakeWs(mapOf("h1" to "ok"), server))
        assertTrue(c.pair("nope", "Pixel", "1234").isFailure)
        assertEquals(AgentState.Failed(FailReason.BAD_OTP), c.state.value)
    }

    @Test fun reconnectsAfterDropUntilClosed() = runTest {
        val f = FakeWs(mapOf("h1" to "ok"), server)
        val c = conn(f, listOf("h1"))
        c.hello("good", "Pixel")
        f.live!!.drop()
        runCurrent()
        assertEquals(AgentState.Connecting, c.state.value)
        advanceTimeBy(1_100)
        runCurrent()
        assertTrue(c.state.value is AgentState.Online)
        assertEquals(2, f.opened.size)
        c.close()
        f.live!!.drop()
        advanceTimeBy(20_000)
        assertEquals(2, f.opened.size)
        assertEquals(AgentState.Closed, c.state.value)
    }

    @Test fun pingsWhileOnline() = runTest {
        val f = FakeWs(mapOf("h1" to "ok"), server)
        val c = conn(f, listOf("h1"))
        c.hello("good", "Pixel")
        advanceTimeBy(10_100)
        runCurrent()
        assertTrue(f.live!!.sent.contains(Msg.Ping))
        c.close()
    }

    @Test fun incomingMessagesAreForwarded() = runTest {
        val f = FakeWs(mapOf("h1" to "ok"), server)
        val c = conn(f, listOf("h1"))
        c.hello("good", "Pixel")
        val got = async { c.incoming.first { it is Msg.Status } }
        runCurrent()
        f.live!!.l.onText(Wire.encode(Msg.Status(true, "x")))
        assertEquals(Msg.Status(true, "x"), got.await())
        c.close()
    }

    @Test fun lateCloseAfterErrorKeepsFailure() = runTest {
        val f = FakeWs(mapOf("h1" to "ok"), server)
        val c = conn(f, listOf("h1"))
        c.hello("old", "Pixel")
        f.live!!.deliverClose()
        assertEquals(AgentState.Failed(FailReason.REVOKED), c.state.value)
    }

    @Test fun lateOpenAfterTimeoutKeepsFailure() = runTest {
        val f = FakeWs(mapOf("h1" to "slow"), server)
        val c = conn(f, listOf("h1"))
        c.hello("good", "Pixel")
        assertEquals(AgentState.Failed(FailReason.UNREACHABLE), c.state.value)
        f.slow!!.l.onOpen()
        f.slow!!.deliverClose()
        assertEquals(AgentState.Failed(FailReason.UNREACHABLE), c.state.value)
    }
}

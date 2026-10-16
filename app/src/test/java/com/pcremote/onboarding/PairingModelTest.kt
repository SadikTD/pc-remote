package com.pcremote.onboarding

import com.limelight.R
import com.pcremote.agent.FailReason
import com.pcremote.agent.PairUri
import com.pcremote.agent.PairedPc
import com.pcremote.agent.PcInfo
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingModelTest {
    private val fp = "b".repeat(64)
    private val qr = "pcremote://pair?n=Desk&h=192.168.0.9&p=47999&fp=$fp&t=otp"
    private val pc = PcInfo("pc-1", "DESK", macs = listOf("AA:BB"))

    private class Fakes(
        val agent: Result<AgentPaired>,
        val moonlightUuid: String? = "ML-UUID",
        val moonlightHangs: Boolean = false,
    ) {
        val stored = mutableListOf<PairedPc>()
        var pinSeenByAgent: String? = null
        var pinSeenByMoonlight: String? = null
        var hostSeenByMoonlight: String? = null
        val deps = PairingDeps(
            agentPair = { _: PairUri, _: String, pin: String -> pinSeenByAgent = pin; agent },
            moonlightPair = { host: String, pin: String ->
                hostSeenByMoonlight = host; pinSeenByMoonlight = pin
                if (moonlightHangs) kotlinx.coroutines.awaitCancellation()
                moonlightUuid
            },
            save = { stored += it },
            device = "Pixel 8",
        )
    }

    private fun ok(host: String = "192.168.0.9") = Result.success(AgentPaired("tok", pc, host))
    private fun fail(r: FailReason) = Result.failure<AgentPaired>(AgentPairException(r))

    @Test fun `not a pc remote code`() = runTest {
        val f = Fakes(ok())
        val m = PairingModel(f.deps)
        m.onScanned("https://example.com")
        assertEquals(PairingState.Error(R.string.pcr_qr_invalid), m.state.value)
        assertTrue(f.stored.isEmpty())
    }

    @Test fun `expired code`() = runTest {
        val m = PairingModel(Fakes(fail(FailReason.BAD_OTP)).deps)
        m.onScanned(qr)
        assertEquals(PairingState.Error(R.string.pcr_qr_expired), m.state.value)
    }

    @Test fun `unreachable gives same wifi message`() = runTest {
        val m = PairingModel(Fakes(fail(FailReason.UNREACHABLE)).deps)
        m.onScanned(qr)
        assertEquals(PairingState.Error(R.string.pcr_same_wifi), m.state.value)
    }

    @Test fun `cert mismatch is a security error and stores nothing`() = runTest {
        val f = Fakes(fail(FailReason.CERT_MISMATCH))
        val m = PairingModel(f.deps)
        m.onScanned(qr)
        assertEquals(PairingState.Error(R.string.pcr_cert_mismatch), m.state.value)
        assertTrue(f.stored.isEmpty())
    }

    @Test fun `success stores the pc with token and moonlight uuid, same pin both sides`() = runTest {
        val f = Fakes(ok())
        val m = PairingModel(f.deps)
        m.onScanned(qr)
        assertEquals(PairingState.Done("pc-1"), m.state.value)
        val s = f.stored.single()
        assertEquals(PairedPc("pc-1", "DESK", listOf("192.168.0.9"), 47999, fp, "tok", "ML-UUID", listOf("AA:BB")), s)
        assertEquals(f.pinSeenByAgent, f.pinSeenByMoonlight)
        assertTrue(f.pinSeenByAgent!!.matches(Regex("\\d{4}")))
    }

    @Test fun `moonlight pairing failure still saves agent pairing`() = runTest {
        val f = Fakes(ok(), moonlightUuid = null)
        val m = PairingModel(f.deps)
        m.onScanned(qr)
        assertEquals(PairingState.Done("pc-1"), m.state.value)
        assertEquals(null, f.stored.single().moonlightUuid)
    }

    @Test fun `moonlight pairing that never finishes times out and still saves`() = runTest {
        val f = Fakes(ok(), moonlightHangs = true)
        val m = PairingModel(f.deps)
        m.onScanned(qr)
        assertEquals(PairingState.Done("pc-1"), m.state.value)
        assertEquals(null, f.stored.single().moonlightUuid)
    }

    @Test fun `moonlight pairs with the host the agent actually reached`() = runTest {
        val f = Fakes(ok(host = "10.0.0.5"))
        PairingModel(f.deps).onScanned("pcremote://pair?n=Desk&h=192.168.0.9,10.0.0.5&p=47999&fp=$fp&t=otp")
        assertEquals("10.0.0.5", f.hostSeenByMoonlight)
    }

    @Test fun `moonlight pairing is retried while the pc is briefly unavailable`() = runTest {
        var calls = 0
        val stored = mutableListOf<PairedPc>()
        val deps = PairingDeps(
            agentPair = { _: PairUri, _: String, _: String -> ok() },
            moonlightPair = { _: String, _: String -> if (++calls < 3) null else "ML-UUID" },
            save = { stored += it },
            device = "Pixel",
        )
        PairingModel(deps).onScanned(qr)
        assertEquals(3, calls)
        assertEquals("ML-UUID", stored.single().moonlightUuid)
    }
}

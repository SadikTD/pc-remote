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
        val agent: Result<Pair<String, PcInfo>>,
        val moonlightUuid: String? = "ML-UUID",
    ) {
        val stored = mutableListOf<PairedPc>()
        var pinSeenByAgent: String? = null
        var pinSeenByMoonlight: String? = null
        val deps = PairingDeps(
            agentPair = { _: PairUri, _: String, pin: String -> pinSeenByAgent = pin; agent },
            moonlightPair = { _: String, pin: String -> pinSeenByMoonlight = pin; moonlightUuid },
            save = { stored += it },
            device = "Pixel 8",
        )
    }

    private fun fail(r: FailReason) = Result.failure<Pair<String, PcInfo>>(AgentPairException(r))

    @Test fun `not a pc remote code`() = runTest {
        val f = Fakes(Result.success("tok" to pc))
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
        val f = Fakes(Result.success("tok" to pc))
        val m = PairingModel(f.deps)
        m.onScanned(qr)
        assertEquals(PairingState.Done("pc-1"), m.state.value)
        val s = f.stored.single()
        assertEquals(PairedPc("pc-1", "DESK", listOf("192.168.0.9"), 47999, fp, "tok", "ML-UUID", listOf("AA:BB")), s)
        assertEquals(f.pinSeenByAgent, f.pinSeenByMoonlight)
        assertTrue(f.pinSeenByAgent!!.matches(Regex("\\d{4}")))
    }

    @Test fun `moonlight pairing failure still saves agent pairing`() = runTest {
        val f = Fakes(Result.success("tok" to pc), moonlightUuid = null)
        val m = PairingModel(f.deps)
        m.onScanned(qr)
        assertEquals(PairingState.Done("pc-1"), m.state.value)
        assertEquals(null, f.stored.single().moonlightUuid)
    }
}

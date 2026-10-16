package com.pcremote.onboarding

import androidx.annotation.StringRes
import com.limelight.R
import com.pcremote.agent.FailReason
import com.pcremote.agent.PairUri
import com.pcremote.agent.PairedPc
import com.pcremote.agent.PcInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.SecureRandom

sealed interface PairingState {
    data object Idle : PairingState
    data class Pairing(val pcName: String) : PairingState
    data class Done(val pcId: String) : PairingState
    data class Error(@StringRes val message: Int) : PairingState
}

class AgentPairException(val reason: FailReason) : Exception(reason.name)

/** A successful agent pairing: device token, PC info, and the LAN address that answered. */
data class AgentPaired(val token: String, val pc: PcInfo, val host: String)

/** Everything pairing touches, injected so the flow is testable. */
class PairingDeps(
    /** Pairs with the host agent. */
    val agentPair: suspend (PairUri, device: String, pin: String) -> Result<AgentPaired>,
    /** Moonlight/Sunshine pairing with the same PIN; returns the Moonlight computer uuid or null. */
    val moonlightPair: suspend (host: String, pin: String) -> String?,
    val save: (PairedPc) -> Unit,
    val device: String,
)

/** One scan → agent pairing, then Moonlight pairing with the same PIN (bounded) → stored PC. */
class PairingModel(private val deps: PairingDeps) {
    private val _state = MutableStateFlow<PairingState>(PairingState.Idle)
    val state: StateFlow<PairingState> = _state

    fun reset() { _state.value = PairingState.Idle }

    suspend fun onScanned(raw: String) {
        val uri = PairUri.parse(raw.trim()) ?: run { _state.value = PairingState.Error(R.string.pcr_qr_invalid); return }
        _state.value = PairingState.Pairing(uri.name)
        val pin = "%04d".format(SecureRandom().nextInt(10_000))
        // The host keeps submitting the PIN to Sunshine for ~30 s after `paired`, so Moonlight
        // pairing starts right after, against the address the agent actually reached.
        deps.agentPair(uri, deps.device, pin).fold(
            onSuccess = { r ->
                // Retry while the PC is briefly unavailable (Sunshine restarting, Wi-Fi blip)
                val uuid = withTimeoutOrNull(MOONLIGHT_TIMEOUT_MS) {
                    var id: String? = null
                    while (id == null) {
                        id = runCatching { deps.moonlightPair(r.host, pin) }.getOrNull()
                        if (id == null) delay(2_000)
                    }
                    id
                }
                deps.save(PairedPc(r.pc.id, r.pc.name, uri.hosts, uri.port, uri.fingerprint, r.token, uuid, r.pc.macs))
                _state.value = PairingState.Done(r.pc.id)
            },
            onFailure = { e -> _state.value = PairingState.Error(messageFor((e as? AgentPairException)?.reason)) },
        )
    }

    private companion object { const val MOONLIGHT_TIMEOUT_MS = 35_000L }

    private fun messageFor(r: FailReason?) = when (r) {
        FailReason.BAD_OTP -> R.string.pcr_qr_expired
        FailReason.UNREACHABLE -> R.string.pcr_same_wifi
        FailReason.CERT_MISMATCH -> R.string.pcr_cert_mismatch
        else -> R.string.pcr_pair_failed
    }
}

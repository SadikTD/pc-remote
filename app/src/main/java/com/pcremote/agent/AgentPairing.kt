package com.pcremote.agent

import com.pcremote.onboarding.AgentPairException
import com.pcremote.onboarding.AgentPaired
import kotlinx.coroutines.CoroutineScope

/** Adapts [AgentConnection.pair] to the onboarding model. The connection is closed afterwards. */
suspend fun agentPair(scope: CoroutineScope, uri: PairUri, device: String, pin: String): Result<AgentPaired> {
    val c = AgentConnection(uri.hosts, uri.port, uri.fingerprint, scope)
    try {
        val token = c.pair(uri.otp, device, pin).getOrElse {
            val reason = (c.state.value as? AgentState.Failed)?.reason ?: FailReason.UNREACHABLE
            return Result.failure(AgentPairException(reason))
        }
        val pc = (c.state.value as? AgentState.Online)?.pc ?: PcInfo(uri.name, uri.name)
        return Result.success(AgentPaired(token, pc, c.connectedHost ?: uri.hosts.first()))
    } finally {
        c.close()
    }
}

package com.pcremote.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit

enum class FailReason { UNREACHABLE, UNAUTHORIZED, REVOKED, BAD_OTP, PIN_TIMEOUT, CERT_MISMATCH }

sealed interface AgentState {
    data object Connecting : AgentState
    data class Online(val pc: PcInfo, val caps: Set<String>) : AgentState
    data class Failed(val reason: FailReason) : AgentState
    data object Closed : AgentState
}

/** Minimal socket seam so the connection logic is testable without a network. */
interface Ws {
    fun send(text: String): Boolean
    fun send(bytes: ByteArray): Boolean
    fun close()
    /** Bytes queued but not yet written to the network. */
    fun queueSize(): Long = 0
}

interface WsListener {
    fun onOpen()
    fun onText(s: String)
    fun onBinary(b: ByteArray) {}
    fun onFailure(t: Throwable, certMismatch: Boolean)
    fun onClosed()
}

fun interface WsFactory {
    fun open(host: String, port: Int, fingerprint: String, listener: WsListener): Ws
}

/** `wss://host:port/v1`, with IPv6 literals bracketed. */
fun wsUrl(host: String, port: Int): String {
    val h = host.trim('[', ']')
    return if (':' in h) "wss://[$h]:$port/v1" else "wss://$h:$port/v1"
}

/** Real WSS sockets with the certificate pinned to the pairing fingerprint. */
object OkHttpWs : WsFactory {
    private val base = OkHttpClient()

    override fun open(host: String, port: Int, fingerprint: String, listener: WsListener): Ws {
        val tm = PinnedTrust.trustManager(fingerprint)
        val client = base.newBuilder()
            .sslSocketFactory(PinnedTrust.sslSocketFactory(tm), tm)
            .hostnameVerifier { _, _ -> true } // identity is the pinned certificate, not the name
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
        val ws = client.newWebSocket(Request.Builder().url(wsUrl(host, port)).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = listener.onOpen()
            override fun onMessage(webSocket: WebSocket, text: String) = listener.onText(text)
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = listener.onBinary(bytes.toByteArray())
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) } // echo the close
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = listener.onClosed()
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                listener.onFailure(t, PinnedTrust.isPinMismatch(t))
        })
        return object : Ws {
            override fun send(text: String) = ws.send(text)
            override fun send(bytes: ByteArray) = ws.send(bytes.toByteString())
            override fun close() { ws.close(1000, null) }
            override fun queueSize() = ws.queueSize()
        }
    }
}

/**
 * One logical connection to a PC Remote Host. Tries [hosts] in order, authenticates with
 * `hello` or `pair`, pings while online, and reconnects with backoff after a drop until [close].
 */
class AgentConnection(
    private val hosts: List<String>,
    private val port: Int,
    private val fingerprint: String,
    private val scope: CoroutineScope,
    private val factory: WsFactory = OkHttpWs,
) {
    private val _state = MutableStateFlow<AgentState>(AgentState.Closed)
    val state: StateFlow<AgentState> = _state
    private val _incoming = MutableSharedFlow<Msg>(extraBufferCapacity = 64)
    val incoming: SharedFlow<Msg> = _incoming
    private val _binary = MutableSharedFlow<ByteArray>(extraBufferCapacity = 8)
    val binary: SharedFlow<ByteArray> = _binary

    /**
     * If set, every binary frame goes here synchronously on the socket thread (no drops);
     * file transfers use this. Otherwise frames go to [binary], which may drop under load.
     */
    @Volatile var binaryListener: ((ByteArray) -> Unit)? = null

    @Volatile private var ws: Ws? = null
    /** The host from [hosts] that the current connection reached. */
    @Volatile var connectedHost: String? = null
        private set
    @Volatile private var session = 0
    @Volatile private var closed = false
    @Volatile private var handshake: CompletableDeferred<Msg>? = null
    @Volatile private var rxSincePing = true
    private var token: String? = null
    private var device = ""
    private var pingJob: Job? = null
    private var reconnectJob: Job? = null

    private enum class Open { OK, REFUSED, MISMATCH }

    private suspend fun connectAny(): Ws? {
        for (h in hosts) {
            if (closed) return null
            val id = ++session
            val opened = CompletableDeferred<Open>()
            val s = factory.open(h, port, fingerprint, listenerFor(id, opened))
            val result = withTimeoutOrNull(2_000) { opened.await() } ?: run {
                opened.complete(Open.REFUSED) // a late onOpen for this attempt is ignored
                Open.REFUSED
            }
            when (result) {
                Open.OK -> { ws = s; connectedHost = h; return s }
                Open.MISMATCH -> { session++; _state.value = AgentState.Failed(FailReason.CERT_MISMATCH); return null }
                Open.REFUSED -> s.close()
            }
        }
        session++ // late callbacks from the abandoned attempts must not revive this connection
        _state.value = AgentState.Failed(FailReason.UNREACHABLE)
        return null
    }

    private fun listenerFor(id: Int, opened: CompletableDeferred<Open>) = object : WsListener {
        override fun onOpen() { opened.complete(Open.OK) }

        override fun onText(s: String) {
            if (id != session) return
            rxSincePing = true
            val m = Wire.decode(s) ?: return
            val hs = handshake
            if (hs != null && !hs.isCompleted) hs.complete(m) else _incoming.tryEmit(m)
        }

        override fun onBinary(b: ByteArray) {
            if (id != session) return
            rxSincePing = true
            binaryListener?.invoke(b) ?: _binary.tryEmit(b)
        }

        override fun onFailure(t: Throwable, certMismatch: Boolean) {
            if (opened.complete(if (certMismatch) Open.MISMATCH else Open.REFUSED)) return
            dropped(id)
        }

        override fun onClosed() {
            if (!opened.complete(Open.REFUSED)) dropped(id)
        }
    }

    @Synchronized
    private fun dropped(id: Int) {
        if (id != session || closed) return
        pingJob?.cancel()
        ws = null
        val wasOnline = _state.value is AgentState.Online
        _state.value = AgentState.Connecting
        if (wasOnline && token != null) startReconnect()
    }

    @Synchronized
    private fun startReconnect() {
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            for (d in BACKOFF_MS) {
                delay(d)
                if (closed) return@launch
                doHello()
                when (val s = _state.value) {
                    is AgentState.Online -> return@launch
                    is AgentState.Failed -> if (s.reason != FailReason.UNREACHABLE) return@launch
                    else -> {}
                }
            }
            while (!closed && isActive) { // keep trying every 10 s
                delay(BACKOFF_MS.last())
                doHello()
                if (_state.value !is AgentState.Failed || (_state.value as AgentState.Failed).reason != FailReason.UNREACHABLE) return@launch
            }
        }
    }

    private suspend fun exchange(s: Ws, m: Msg): Msg? {
        val hs = CompletableDeferred<Msg>()
        handshake = hs
        s.send(Wire.encode(m))
        return withTimeoutOrNull(HANDSHAKE_TIMEOUT_MS) { hs.await() }.also { handshake = null }
    }

    private fun fail(code: String?, s: Ws) {
        session++ // the close echo from this socket arrives later and must be ignored
        s.close()
        ws = null
        _state.value = AgentState.Failed(
            when (code) {
                "revoked" -> FailReason.REVOKED
                "bad_otp" -> FailReason.BAD_OTP
                "pin_timeout" -> FailReason.PIN_TIMEOUT
                null -> FailReason.UNREACHABLE
                else -> FailReason.UNAUTHORIZED
            }
        )
    }

    private suspend fun doHello() {
        _state.value = AgentState.Connecting
        val s = connectAny() ?: return
        when (val r = exchange(s, Msg.Hello(1, token!!, device))) {
            is Msg.Welcome -> online(r.pc, r.caps.toSet())
            is Msg.Error -> fail(r.code, s)
            else -> fail(null, s)
        }
    }

    @Synchronized
    private fun online(pc: PcInfo, caps: Set<String>) {
        _state.value = AgentState.Online(pc, caps)
        pingJob?.cancel()
        val id = session
        pingJob = scope.launch {
            var missed = 0
            while (isActive && id == session) {
                delay(PING_MS)
                missed = if (rxSincePing) 0 else missed + 1
                rxSincePing = false
                if (missed >= 3) { ws?.close(); dropped(id); return@launch }
                ws?.send(Wire.encode(Msg.Ping))
            }
        }
    }

    /** Connects with a stored device token. Watch [state] for the result. */
    suspend fun hello(token: String, device: String) {
        closed = false
        this.token = token
        this.device = device
        doHello()
    }

    /** First-time pairing with the QR one-time token; returns the long-lived device token. */
    suspend fun pair(otp: String, device: String, pin: String): Result<String> {
        closed = false
        this.device = device
        _state.value = AgentState.Connecting
        val s = connectAny() ?: return Result.failure(IllegalStateException((_state.value as? AgentState.Failed)?.reason?.name))
        return when (val r = exchange(s, Msg.Pair(otp, device, pin))) {
            is Msg.Paired -> { token = r.token; online(r.pc, emptySet()); Result.success(r.token) }
            is Msg.Error -> { fail(r.code, s); Result.failure(IllegalStateException(r.code)) }
            else -> { fail(null, s); Result.failure(IllegalStateException("timeout")) }
        }
    }

    fun send(m: Msg): Boolean = ws?.send(Wire.encode(m)) ?: false

    /** Sends only while authenticated; reads the live state, so it's safe to capture in long-lived callbacks. */
    fun sendIfOnline(m: Msg): Boolean = _state.value is AgentState.Online && send(m)

    fun backlog(): Long = ws?.queueSize() ?: 0
    fun sendBinary(b: ByteArray): Boolean = ws?.send(b) ?: false

    @Synchronized
    fun close() {
        closed = true
        reconnectJob?.cancel()
        pingJob?.cancel()
        val s = ws
        ws = null
        session++
        _state.value = AgentState.Closed
        s?.close()
    }

    private companion object {
        val BACKOFF_MS = longArrayOf(1_000, 2_000, 5_000, 10_000)
        const val PING_MS = 10_000L
        const val HANDSHAKE_TIMEOUT_MS = 5_000L
    }
}

package com.pcremote.agent

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** What the host's `remote` message gave this phone: the rendezvous Worker and its ticket (docs/remote-protocol.md). */
data class RelayInfo(val url: String, val pc: String, val dev: String, val ticket: ByteArray) {
    /** `wss://…/v1/phone?…` signed for `tsS`. */
    fun phoneUrl(tsS: Long, path: String = "phone"): String {
        val sig = hex(hmac(ticket, "phone:$pc:$dev:$tsS"))
        return "${url.trimEnd('/')}/v1/$path?pc=${enc(pc)}&dev=${enc(dev)}&ts=$tsS&sig=$sig"
    }

    companion object {
        fun hmac(key: ByteArray, msg: String): ByteArray =
            Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(msg.toByteArray())
        fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
        private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
        fun b64url(s: String): ByteArray? = runCatching { java.util.Base64.getUrlDecoder().decode(s) }.getOrNull()
        fun b64url(b: ByteArray): String = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    }
}

/** The pseudo-host that means "through the rendezvous Worker"; always tried last. */
const val RELAY_HOST = "@relay"

/** Direct hosts go to the pinned WSS factory; [RELAY_HOST] opens a Worker socket instead. */
class RoutingWs(private val relay: RelayInfo?, private val direct: WsFactory = OkHttpWs) : WsFactory {
    override fun open(host: String, port: Int, fingerprint: String, listener: WsListener): Ws =
        if (host == RELAY_HOST && relay != null) RelayWs.open(relay, listener) else direct.open(host, port, fingerprint, listener)
}

object RelayWs {
    private val client = OkHttpClient.Builder().connectTimeout(6, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build()

    fun open(r: RelayInfo, listener: WsListener): Ws {
        val ws = client.newWebSocket(Request.Builder().url(r.phoneUrl(System.currentTimeMillis() / 1000)).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = listener.onOpen()
            override fun onMessage(webSocket: WebSocket, text: String) = listener.onText(text)
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = listener.onBinary(bytes.toByteArray())
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = listener.onClosed()
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = listener.onFailure(t, false)
        })
        return object : Ws {
            override fun send(text: String) = ws.send(text)
            override fun send(bytes: ByteArray) = ws.send(bytes.toByteString())
            override fun close() { ws.close(1000, null) }
            override fun queueSize() = ws.queueSize()
        }
    }
}

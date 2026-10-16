package com.pcremote.remote

import com.pcremote.agent.RelayInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** A TURN server from the Worker's `/v1/turn` (Cloudflare Realtime ICE servers). */
data class TurnServer(val host: String, val port: Int, val user: String, val pass: String)

object TurnCreds {
    private val http = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()

    /** Blocking. Null when the relay isn't configured or unreachable. */
    fun fetch(r: RelayInfo): TurnServer? = runCatching {
        val url = r.phoneUrl(System.currentTimeMillis() / 1000, "turn").replaceFirst("wss://", "https://").replaceFirst("ws://", "http://")
        http.newCall(Request.Builder().url(url).post(ByteArray(0).toRequestBody()).build()).execute().use { resp ->
            if (!resp.isSuccessful) return null
            parse(resp.body.string())
        }
    }.getOrNull()

    /** The first `turn:` URL over UDP. Accepts `iceServers` as an array or a single object. */
    fun parse(json: String): TurnServer? {
        val ice: JsonElement = Json.parseToJsonElement(json).jsonObject["iceServers"] ?: return null
        val servers = (ice as? JsonArray)?.map { it.jsonObject } ?: listOf(ice as? JsonObject ?: return null)
        for (s in servers) {
            val user = s["username"]?.jsonPrimitive?.content ?: continue
            val pass = s["credential"]?.jsonPrimitive?.content ?: continue
            val urls = when (val u = s["urls"]) { is JsonArray -> u.map { it.jsonPrimitive.content }; null -> emptyList(); else -> listOf(u.jsonPrimitive.content) }
            for (u in urls) {
                if (!u.startsWith("turn:") || ("transport=" in u && "transport=udp" !in u)) continue
                val hp = u.removePrefix("turn:").substringBefore('?')
                val host = hp.substringBeforeLast(':'); val port = hp.substringAfterLast(':', "3478").toIntOrNull() ?: 3478
                return TurnServer(host, port, user, pass)
            }
        }
        return null
    }
}

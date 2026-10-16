package com.pcremote.home

import android.content.Context
import com.pcremote.agent.KeyValue
import com.pcremote.agent.KeystoreBox
import com.pcremote.agent.SecretBox
import com.pcremote.settings.prefsKv
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Wake from anywhere: Home Assistant (always on, at home) sends the magic packet on the PC's LAN.
 * Either a webhook link to an automation that presses a Wake on LAN button (preferred: it can do
 * nothing else), or the HA address plus a long-lived token for `wake_on_lan.send_magic_packet`.
 */
class HomeAssistant(private val kv: KeyValue, private val box: SecretBox = KeystoreBox) {
    var url: String
        get() = kv.get(K_URL).orEmpty()
        set(v) = kv.put(K_URL, normalize(v).ifEmpty { null })

    var token: String
        get() = kv.get(K_TOKEN)?.let { runCatching { box.open(it) }.getOrNull() }.orEmpty()
        set(v) = kv.put(K_TOKEN, v.trim().takeIf { it.isNotEmpty() }?.let(box::seal))

    /** A Home Assistant webhook link (…/api/webhook/<id>) needs no token: it can only run its automation. */
    val isWebhook get() = "/api/webhook/" in url

    val configured get() = url.isNotEmpty() && (isWebhook || token.isNotEmpty())

    /** Blocking. Asks HA to wake every MAC of the PC; true if HA accepted. */
    fun wake(macs: List<String>): Result<Unit> = runCatching {
        check(configured) { "Home Assistant is not set up" }
        if (isWebhook) {
            client.newCall(webhook(url)).execute().use { r -> check(r.isSuccessful) { "Home Assistant answered ${r.code}" } }
            return@runCatching
        }
        for (mac in macs) {
            client.newCall(request(url, token, mac)).execute().use { r ->
                check(r.isSuccessful) { "Home Assistant answered ${r.code}" }
            }
        }
    }

    companion object {
        private const val K_URL = "pcremote_ha_url"
        private const val K_TOKEN = "pcremote_ha_token"
        private val client by lazy { OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build() }

        fun get(ctx: Context) = HomeAssistant(prefsKv(ctx))

        /** "ha.example.com/" → "https://ha.example.com". */
        fun normalize(u: String): String {
            val t = u.trim().trimEnd('/')
            if (t.isEmpty()) return ""
            return if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t"
        }

        fun webhook(url: String): Request = Request.Builder().url(url).post("{}".toRequestBody("application/json".toMediaType())).build()

        fun request(url: String, token: String, mac: String): Request = Request.Builder()
            .url("$url/api/services/wake_on_lan/send_magic_packet")
            .header("Authorization", "Bearer $token")
            .post(buildJsonObject { put("mac", mac) }.toString().toRequestBody("application/json".toMediaType()))
            .build()
    }
}

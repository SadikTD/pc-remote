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
 * Needs `wake_on_lan:` in HA's configuration.yaml and a long-lived access token (HA → Profile → Security).
 */
class HomeAssistant(private val kv: KeyValue, private val box: SecretBox = KeystoreBox) {
    var url: String
        get() = kv.get(K_URL).orEmpty()
        set(v) = kv.put(K_URL, normalize(v).ifEmpty { null })

    var token: String
        get() = kv.get(K_TOKEN)?.let { runCatching { box.open(it) }.getOrNull() }.orEmpty()
        set(v) = kv.put(K_TOKEN, v.trim().takeIf { it.isNotEmpty() }?.let(box::seal))

    val configured get() = url.isNotEmpty() && token.isNotEmpty()

    /** Blocking. Asks HA to wake every MAC of the PC; true if HA accepted. */
    fun wake(macs: List<String>): Result<Unit> = runCatching {
        check(configured) { "Home Assistant is not set up" }
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

        fun request(url: String, token: String, mac: String): Request = Request.Builder()
            .url("$url/api/services/wake_on_lan/send_magic_packet")
            .header("Authorization", "Bearer $token")
            .post(buildJsonObject { put("mac", mac) }.toString().toRequestBody("application/json".toMediaType()))
            .build()
    }
}

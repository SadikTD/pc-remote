package com.pcremote.agent

import android.content.Context
import android.preference.PreferenceManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

interface KeyValue {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}

/** A PC paired through the host agent. [token] is the long-lived device token. */
data class PairedPc(
    val id: String, val name: String, val hosts: List<String>, val port: Int, val fingerprint: String,
    val token: String, val macs: List<String>,
    /** Public address from "access from anywhere", tried after the LAN hosts. */
    val publicHost: String? = null,
    /** The rendezvous Worker, tried when nothing direct answers. */
    val relay: RelayInfo? = null,
) {
    /** Addresses to try, best first; the relay last. */
    val allHosts get() = HostOrder.order(hosts, publicHost) + listOfNotNull(relay?.let { RELAY_HOST })
}

object HostOrder {
    fun order(lan: List<String>, public: String?) = (lan + listOfNotNull(public)).distinct()
}

@Serializable
private data class StoredPc(
    val id: String, val name: String, val hosts: List<String>, val port: Int, val fp: String,
    val sealedToken: String, val macs: List<String> = emptyList(),
    val publicHost: String? = null,
    val relayUrl: String? = null, val relayDev: String? = null, val sealedTicket: String? = null,
)

class PcStore(private val kv: KeyValue, private val box: SecretBox) {
    private val json = Json { ignoreUnknownKeys = true }
    private val ser = ListSerializer(StoredPc.serializer())

    private fun load(): List<StoredPc> =
        kv.get(KEY)?.let { runCatching { json.decodeFromString(ser, it) }.getOrNull() } ?: emptyList()

    private fun save(l: List<StoredPc>) = kv.put(KEY, json.encodeToString(ser, l))

    fun all(): List<PairedPc> = load().mapNotNull { s ->
        val token = runCatching { box.open(s.sealedToken) }.getOrNull() ?: return@mapNotNull null
        val relay = if (s.relayUrl != null && s.relayDev != null && s.sealedTicket != null)
            runCatching { RelayInfo(s.relayUrl, s.id, s.relayDev, RelayInfo.b64url(box.open(s.sealedTicket))!!) }.getOrNull() else null
        PairedPc(s.id, s.name, s.hosts, s.port, s.fp, token, s.macs, s.publicHost, relay)
    }

    fun upsert(pc: PairedPc) {
        val stored = StoredPc(pc.id, pc.name, pc.hosts, pc.port, pc.fingerprint, box.seal(pc.token), pc.macs, pc.publicHost,
            pc.relay?.url, pc.relay?.dev, pc.relay?.let { box.seal(RelayInfo.b64url(it.ticket)) })
        val l = load().toMutableList()
        val i = l.indexOfFirst { it.id == pc.id }
        if (i >= 0) l[i] = stored else l += stored
        save(l)
    }

    fun remove(id: String) = save(load().filterNot { it.id == id })


    companion object {
        private const val KEY = "pcremote_pcs"

        fun get(ctx: Context): PcStore {
            @Suppress("DEPRECATION")
            val prefs = PreferenceManager.getDefaultSharedPreferences(ctx.applicationContext)
            val kv = object : KeyValue {
                override fun get(key: String) = prefs.getString(key, null)
                override fun put(key: String, value: String?) { prefs.edit().putString(key, value).apply() }
            }
            return PcStore(kv, KeystoreBox)
        }
    }
}

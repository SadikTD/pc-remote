package com.pcremote.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.util.ArrayDeque

/** A PC Remote Host seen on this network: its stable id and current address. */
data class LanPc(val id: String, val name: String, val host: String, val v: Int)

/**
 * Browses `_pcremote._udp` with Android's NsdManager while [start]ed. Each host found is resolved
 * (one at a time, as older NsdManager requires) and reported through [onFound].
 */
class Lan(ctx: Context, private val onFound: (LanPc) -> Unit) {
    private val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val queue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var listener: NsdManager.DiscoveryListener? = null

    fun start() {
        if (listener != null) return
        val l = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(s: NsdServiceInfo) = enqueue(s)
            override fun onServiceLost(s: NsdServiceInfo) {}
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, code: Int) { Log.w(TAG, "discovery failed $code"); listener = null }
            override fun onStopDiscoveryFailed(t: String, code: Int) {}
        }
        listener = l
        runCatching { nsd.discoverServices(SERVICE, NsdManager.PROTOCOL_DNS_SD, l) }.onFailure { listener = null }
    }

    fun stop() {
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        listener = null
        synchronized(queue) { queue.clear() }
    }

    private fun enqueue(s: NsdServiceInfo) {
        synchronized(queue) { queue.addLast(s) }
        next()
    }

    private fun next() {
        val s = synchronized(queue) {
            if (resolving || queue.isEmpty()) return
            resolving = true
            queue.removeFirst()
        }
        @Suppress("DEPRECATION")
        nsd.resolveService(s, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, code: Int) = done()
            override fun onServiceResolved(info: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                val host = info.host?.hostAddress
                parse(info.attributes.mapValues { it.value?.let(::String) }, host)?.let { Log.i(TAG, "found ${it.name} at ${it.host}"); onFound(it) }
                done()
            }
        })
    }

    private fun done() { synchronized(queue) { resolving = false }; next() }

    companion object {
        private const val TAG = "PcrLan"
        const val SERVICE = "_pcremote._udp."

        /** TXT `id`, `v`, `name` (docs/protocol.md) plus the resolved address; null if not a usable host. */
        fun parse(txt: Map<String, String?>, host: String?): LanPc? {
            val id = txt["id"]?.takeIf { it.isNotBlank() } ?: return null
            if (host.isNullOrBlank() || host.contains('%')) return null // link-local scoped v6 isn't dialable by URL
            return LanPc(id, txt["name"].orEmpty(), host, txt["v"]?.toIntOrNull() ?: 1)
        }

        /** Stored addresses with the freshly discovered one first; null if nothing changes. */
        fun prefer(found: String, hosts: List<String>): List<String>? =
            if (hosts.firstOrNull() == found) null else listOf(found) + hosts.filter { it != found }
    }
}

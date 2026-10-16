package com.pcremote.home

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/** Wake-on-LAN magic packets: 6 × 0xFF then the MAC 16 times, to the broadcast address and each known host. */
object WakeOnLan {
    fun packet(mac: String): ByteArray? {
        val b = mac.split(':', '-').takeIf { it.size == 6 }?.map { it.toIntOrNull(16)?.toByte() ?: return null } ?: return null
        return ByteArray(6) { -1 } + (1..16).flatMap { b }.toByteArray()
    }

    /** Blocking; true if at least one packet went out. */
    fun send(macs: List<String>, hosts: List<String>): Boolean {
        val targets = listOf("255.255.255.255") + hosts.filter { it.matches(Regex("""\d{1,3}(\.\d{1,3}){3}""")) }
        var sent = false
        DatagramSocket().use { s ->
            s.broadcast = true
            for (p in macs.mapNotNull(::packet)) for (t in targets) for (port in intArrayOf(9, 7)) {
                if (runCatching { s.send(DatagramPacket(p, p.size, InetAddress.getByName(t), port)) }.isSuccess) sent = true
            }
        }
        return sent
    }
}

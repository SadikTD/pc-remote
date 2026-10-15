package com.pcremote.agent

import java.net.URLDecoder

/** The pairing QR payload: `pcremote://pair?n=&h=&p=&fp=&t=` (docs/protocol.md). */
data class PairUri(val name: String, val hosts: List<String>, val port: Int, val fingerprint: String, val otp: String) {
    companion object {
        private const val PREFIX = "pcremote://pair?"
        private val HEX64 = Regex("^[0-9a-f]{64}$")

        fun parse(s: String): PairUri? {
            if (!s.startsWith(PREFIX)) return null
            val q = s.removePrefix(PREFIX).split('&').mapNotNull {
                val i = it.indexOf('=')
                if (i <= 0) null else it.substring(0, i) to URLDecoder.decode(it.substring(i + 1), "UTF-8")
            }.toMap()
            val hosts = q["h"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val port = q["p"]?.toIntOrNull() ?: return null
            val fp = q["fp"]?.lowercase() ?: return null
            val otp = q["t"].orEmpty()
            if (hosts.isEmpty() || port !in 1..65535 || !HEX64.matches(fp) || otp.isEmpty()) return null
            return PairUri(q["n"].orEmpty().ifEmpty { "PC" }, hosts, port, fp, otp)
        }
    }
}

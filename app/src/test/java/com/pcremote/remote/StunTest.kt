package com.pcremote.remote

import com.pcremote.agent.RelayInfo
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress

class StunTest {
    private fun hex(s: String) = s.replace(Regex("\\s"), "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** RFC 5769 §2.2: IPv4 Binding success response. */
    @Test fun parsesRfc5769Response() {
        val b = hex("""
            0101003c2112a442b7e7a701bc34d686fa87dfae
            8022000b7465737420766563746f7220
            002000080001a147e112a643
            000800142b91f599fd9e90c38c7489f92af9ba53f06be7d7
            80280004c07d4c96""")
        val m = StunMsg.decode(b)!!
        assertTrue(m.isSuccess)
        assertEquals(StunMsg.BINDING, m.method)
        assertEquals(InetSocketAddress(InetAddress.getByName("192.0.2.1"), 32853), StunMsg.mapped(m))
    }

    /** RFC 5769 §2.1 request: MESSAGE-INTEGRITY over the bytes before it, with the length counting MI. */
    @Test fun messageIntegrityMatchesRfc5769() {
        val before = hex("""
            000100582112a442b7e7a701bc34d686fa87dfae
            802200105354554e207465737420636c69656e74
            002400046e0001ff
            80290008932ff9b151263b36
            000600096576746a3a68367659202020""")
        assertArrayEquals(hex("9aeaa70cbfd8cb56781ef2b5b2d3f249c1b571a2"), StunMsg.integrity(before, "VOkJxbRl1RmTxUk/WvJxBt".toByteArray()))
        // and encode() appends exactly that over its own bytes
        val m = StunMsg(StunMsg.ALLOCATE).add(StunMsg.USERNAME, "u".toByteArray())
        val enc = m.encode("k".toByteArray())
        assertArrayEquals(StunMsg.integrity(enc.copyOfRange(0, enc.size - 24), "k".toByteArray()), StunMsg.decode(enc)!!.get(StunMsg.MESSAGE_INTEGRITY))
    }

    @Test fun xorAddressRoundTripAndTurnKey() {
        val a = InetSocketAddress(InetAddress.getByName("203.0.113.9"), 47998)
        assertEquals(a, StunMsg.readXorAddr(StunMsg.xorAddr(a)))
        // RFC 5389 long-term key = MD5("user:realm:pass")
        assertEquals("8493fbc53ba582fb4c044c456bdc40eb", StunMsg.longTermKey("user", "realm", "pass").joinToString("") { "%02x".format(it) })
    }

    @Test fun channelDataPadsToFour() {
        val cd = TurnClient.channelData(0x4000, byteArrayOf(1, 2, 3, 4, 5))
        assertEquals(12, cd.size)
        assertArrayEquals(byteArrayOf(0x40, 0, 0, 5, 1, 2, 3, 4, 5, 0, 0, 0), cd)
    }

    @Test fun hostPortParsingIsLiteralOnly() {
        assertEquals(InetSocketAddress(InetAddress.getByName("192.168.0.126"), 47998), StunMsg.parseHostPort("192.168.0.126:47998"))
        assertNull(StunMsg.parseHostPort("example.com:47998"))
        assertNull(StunMsg.parseHostPort("1.2.3.4:0"))
        assertNull(StunMsg.parseHostPort("garbage"))
        assertNull(StunMsg.decode(byteArrayOf(0x11, 0, 0, 0)))
    }

    @Test fun turnCredsFromCloudflareShapes() {
        val arr = """{"iceServers":[{"urls":["stun:stun.cloudflare.com:3478"]},{"urls":["turn:turn.cloudflare.com:3478?transport=udp","turns:turn.cloudflare.com:443?transport=tcp"],"username":"u","credential":"p"}]}"""
        assertEquals(TurnServer("turn.cloudflare.com", 3478, "u", "p"), TurnCreds.parse(arr))
        val obj = """{"iceServers":{"urls":["turn:turn.cloudflare.com:3478?transport=tcp","turn:turn.cloudflare.com:53"],"username":"u","credential":"p"}}"""
        assertEquals(TurnServer("turn.cloudflare.com", 53, "u", "p"), TurnCreds.parse(obj))
        assertNull(TurnCreds.parse("""{"iceServers":[]}"""))
    }

    @Test fun relaySignatureMatchesWorker() {
        // Same construction as cloud/rendezvous/test/auth.test.ts: ticket = HMAC(relayKey, "ticket:pc:dev")
        val key = ByteArray(32) { it.toByte() }
        val ticket = RelayInfo.hmac(key, "ticket:PC1:dev9")
        val url = RelayInfo("wss://w.example", "PC1", "dev9", ticket).phoneUrl(1700000000)
        val sig = RelayInfo.hex(RelayInfo.hmac(ticket, "phone:PC1:dev9:1700000000"))
        assertEquals("wss://w.example/v1/phone?pc=PC1&dev=dev9&ts=1700000000&sig=$sig", url)
        assertNotNull(RelayInfo.b64url(RelayInfo.b64url(ticket)))
    }
}

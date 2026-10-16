package com.pcremote.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {
    private val pc = PcInfo("id1", "DESKTOP", "Sadik", listOf("AA:BB:CC:DD:EE:FF"), "Windows 11")

    @Test fun `every message round-trips`() {
        val all: List<Msg> = listOf(
            Msg.Pair("otp", "Pixel", "4821"), Msg.Hello(1, "tok", "Pixel"), Msg.Ping, Msg.Pong,
            Msg.Paired("tok", pc), Msg.Welcome(1, pc, listOf("input", "media")),
            Msg.MouseMove(-3, 12), Msg.MouseButton("left", true), Msg.MouseScroll(120, 0),
            Msg.Key(91, true), Msg.Text("héllo"),
            Msg.Pad(0, 4096, 0, 255, 0, -32768, 1200, 0), Msg.PadOff(0), Msg.Rumble(0, 10, 20),
            Msg.MediaSub(true), Msg.Media("t", "a", "Spotify", true, 45, false), Msg.MediaCmd("vol_set", 30),
            Msg.ClipSub(true), Msg.Clip("text/plain", "x"),
            Msg.FileBegin("u", "a.jpg", 10), Msg.FileReady("u", 0), Msg.FileEnd("u", "ab"),
            Msg.FileDone("u", "C:\\a.jpg"), Msg.FileError("u", "denied"), Msg.FileOffer("u", "b.txt", 3),
            Msg.FileAccept("u", 0), Msg.FileReject("u"),
            Msg.Power("lock"), Msg.Ok("power"), Msg.ThumbSub(true),
            Msg.Status(true, "Pixel"), Msg.NetReq, Msg.Net(listOf("192.168.0.2"), null, false, false),
            Msg.Error("revoked", "gone", null),
        )
        for (m in all) assertEquals(m, Wire.decode(Wire.encode(m)))
    }

    @Test fun `type discriminator is t with dotted names`() {
        val s = Wire.encode(Msg.MouseMove(1, 2))
        assertTrue(s, s.contains("\"t\":\"mouse.move\""))
        assertTrue(Wire.encode(Msg.NetReq).contains("\"t\":\"net\""))
    }

    @Test fun `display messages`() {
        assertEquals(
            Msg.StreamDisplays(listOf(DisplayInfo("a", "Display 1", true), DisplayInfo("b", "Display 2")), "b"),
            Wire.decode("""{"t":"stream.displays","list":[{"id":"a","name":"Display 1","primary":true},{"id":"b","name":"Display 2","primary":false}],"current":"b"}"""),
        )
        assertEquals("""{"t":"stream.display","id":"b"}""", Wire.encode(Msg.StreamDisplay("b")))
    }

    @Test fun `unknown fields are ignored`() {
        assertEquals(Msg.MouseMove(1, 2), Wire.decode("""{"t":"mouse.move","dx":1,"dy":2,"extra":1}"""))
    }

    @Test fun `unknown type or garbage decodes to null`() {
        assertNull(Wire.decode("""{"t":"nope"}"""))
        assertNull(Wire.decode("not json"))
    }

    @Test fun `net reply with lan decodes as Net not NetReq`() {
        val m = Wire.decode("""{"t":"net","lan":["1.2.3.4"],"public":null,"anywhere":true,"upnp":true}""")
        assertEquals(Msg.Net(listOf("1.2.3.4"), null, true, true), m)
    }
}

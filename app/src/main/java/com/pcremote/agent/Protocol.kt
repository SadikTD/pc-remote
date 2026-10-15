package com.pcremote.agent

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Messages from docs/protocol.md. The JSON field `t` carries the type. */
@Serializable
data class PcInfo(
    val id: String, val name: String, val user: String = "", val macs: List<String> = emptyList(),
    val os: String = "", val sunshine: Boolean = true,
)

@Serializable
sealed interface Msg {
    @Serializable @SerialName("pair") data class Pair(val otp: String, val device: String, val pin: String) : Msg
    @Serializable @SerialName("hello") data class Hello(val v: Int, val token: String, val device: String) : Msg
    @Serializable @SerialName("ping") data object Ping : Msg
    @Serializable @SerialName("pong") data object Pong : Msg
    @Serializable @SerialName("paired") data class Paired(val token: String, val pc: PcInfo) : Msg
    @Serializable @SerialName("welcome") data class Welcome(val v: Int, val pc: PcInfo, val caps: List<String>) : Msg
    @Serializable @SerialName("error") data class Error(val code: String, val msg: String = "", val of: String? = null) : Msg
    @Serializable @SerialName("ok") data class Ok(val of: String? = null) : Msg

    @Serializable @SerialName("mouse.move") data class MouseMove(val dx: Int, val dy: Int) : Msg
    @Serializable @SerialName("mouse.button") data class MouseButton(val b: String, val down: Boolean) : Msg
    @Serializable @SerialName("mouse.scroll") data class MouseScroll(val dy: Int, val dx: Int = 0) : Msg
    @Serializable @SerialName("key") data class Key(val vk: Int, val down: Boolean) : Msg
    @Serializable @SerialName("text") data class Text(val s: String) : Msg

    @Serializable @SerialName("pad") data class Pad(
        val id: Int, val btn: Int, val lt: Int, val rt: Int, val lx: Int, val ly: Int, val rx: Int, val ry: Int,
    ) : Msg
    @Serializable @SerialName("pad.off") data class PadOff(val id: Int) : Msg
    @Serializable @SerialName("rumble") data class Rumble(val id: Int, val lo: Int, val hi: Int) : Msg

    @Serializable @SerialName("media.sub") data class MediaSub(val on: Boolean) : Msg
    @Serializable @SerialName("media") data class Media(
        val title: String = "", val artist: String = "", val app: String = "",
        val playing: Boolean = false, val vol: Int = 0, val muted: Boolean = false,
    ) : Msg
    @Serializable @SerialName("media.cmd") data class MediaCmd(val c: String, val v: Int? = null) : Msg

    @Serializable @SerialName("clip.sub") data class ClipSub(val on: Boolean) : Msg
    @Serializable @SerialName("clip") data class Clip(val mime: String, val data: String) : Msg

    @Serializable @SerialName("file.begin") data class FileBegin(val id: String, val name: String, val size: Long) : Msg
    @Serializable @SerialName("file.ready") data class FileReady(val id: String, val offset: Long) : Msg
    @Serializable @SerialName("file.end") data class FileEnd(val id: String, val sha256: String) : Msg
    @Serializable @SerialName("file.done") data class FileDone(val id: String, val path: String) : Msg
    @Serializable @SerialName("file.error") data class FileError(val id: String, val code: String) : Msg
    @Serializable @SerialName("file.offer") data class FileOffer(val id: String, val name: String, val size: Long) : Msg
    @Serializable @SerialName("file.accept") data class FileAccept(val id: String, val offset: Long) : Msg
    @Serializable @SerialName("file.reject") data class FileReject(val id: String) : Msg

    @Serializable @SerialName("power") data class Power(val a: String) : Msg
    @Serializable @SerialName("thumb.sub") data class ThumbSub(val on: Boolean) : Msg
    @Serializable @SerialName("status") data class Status(val streaming: Boolean, val to: String? = null) : Msg

    /** Client request `{"t":"net"}`; shares its type name with the [Net] reply, so [Wire] handles it. */
    data object NetReq : Msg
    @Serializable @SerialName("net") data class Net(
        val lan: List<String>, val public: String? = null, val anywhere: Boolean = false, val upnp: Boolean = false,
    ) : Msg
}

object Wire {
    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        classDiscriminator = "t"
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = true
    }

    fun encode(m: Msg): String =
        if (m is Msg.NetReq) """{"t":"net"}""" else json.encodeToString(Msg.serializer(), m)

    fun decode(s: String): Msg? = try {
        val obj: JsonObject = json.parseToJsonElement(s).jsonObject
        if (obj["t"]?.jsonPrimitive?.content == "net" && "lan" !in obj) Msg.NetReq
        else json.decodeFromJsonElement(Msg.serializer(), obj)
    } catch (e: Exception) {
        null
    }
}

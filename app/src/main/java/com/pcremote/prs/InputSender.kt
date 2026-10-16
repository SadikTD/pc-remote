package com.pcremote.prs

import com.pcremote.agent.Msg
import com.pcremote.agent.Wire
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** Builds INPUT plaintexts: the newest event plus the previous two, each tagged with an increasing `i`. */
class InputSender {
    private var next = 1L
    private val recent = ArrayDeque<JsonObject>(3)

    @Synchronized
    fun payload(m: Msg): ByteArray = payload(Json.parseToJsonElement(Wire.encode(m)).jsonObject)

    @Synchronized
    fun payload(event: JsonObject): ByteArray {
        val tagged = JsonObject(mapOf("i" to JsonPrimitive(next++)) + event)
        if (recent.size == 3) recent.removeFirst()
        recent.addLast(tagged)
        // redundancy goes first if the packet would be too big; a single oversized event is the caller's bug
        var from = 0
        while (true) {
            val b = JsonArray(recent.drop(from)).toString().toByteArray()
            if (b.size <= Prs.MAX_PLAIN) return b
            require(++from < recent.size) { "input event too large: split it" }
        }
    }
}

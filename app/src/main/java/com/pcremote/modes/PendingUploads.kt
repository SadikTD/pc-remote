package com.pcremote.modes

import com.pcremote.agent.KeyValue
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class PendingUpload(val id: String, val uri: String, val name: String, val size: Long)

/** Unfinished uploads per PC, kept so they resume with the same id (the host keeps `<name>.part`). */
class PendingUploads(private val kv: KeyValue, pcId: String) {
    private val key = "pcremote_uploads_$pcId"
    private val ser = ListSerializer(PendingUpload.serializer())
    private val json = Json { ignoreUnknownKeys = true }

    fun all(): List<PendingUpload> = kv.get(key)?.let { runCatching { json.decodeFromString(ser, it) }.getOrNull() } ?: emptyList()
    fun add(u: PendingUpload) = kv.put(key, json.encodeToString(ser, all().filterNot { it.id == u.id } + u))
    fun remove(id: String) = kv.put(key, json.encodeToString(ser, all().filterNot { it.id == id }))
}

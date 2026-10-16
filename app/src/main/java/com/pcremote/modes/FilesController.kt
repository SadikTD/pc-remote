package com.pcremote.modes

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.compose.runtime.mutableStateListOf
import com.pcremote.agent.AgentConnection
import com.pcremote.agent.AgentState
import com.pcremote.agent.Msg
import com.pcremote.settings.prefsKv
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class Transfer(val id: String, val name: String, val outgoing: Boolean, val progress: Float = 0f, val status: Status = Status.ACTIVE) {
    enum class Status { ACTIVE, DONE, FAILED }
}

/**
 * Uploads (one at a time, resumable with a persisted id) and downloads into Downloads/PC Remote.
 * Incoming chunks are written on the socket thread so none are dropped.
 */
class FilesController(private val ctx: Context, private val conn: AgentConnection, private val scope: CoroutineScope, pcId: String) {
    val transfers = mutableStateListOf<Transfer>()
    private val pending = PendingUploads(prefsKv(ctx), pcId)
    private val queue = Channel<PendingUpload>(Channel.UNLIMITED)
    private val queued = ConcurrentHashMap.newKeySet<String>()
    private val replies = ConcurrentHashMap<String, CompletableDeferred<Msg>>()

    private class Incoming(val receiver: FileReceiver, val out: OutputStream, val publish: () -> Unit, val discard: () -> Unit)
    private val receivers = ConcurrentHashMap<String, Incoming>()

    init {
        conn.binaryListener = { b ->
            FileFraming.parse(b)?.let { c ->
                receivers[c.id.toString()]?.let { inc ->
                    inc.receiver.onChunk(c)
                    val p = inc.receiver.progress
                    scope.launch { update(c.id.toString()) { it.copy(progress = p) } }
                }
            }
        }
        scope.launch { for (u in queue) { upload(u); queued.remove(u.id) } }
        scope.launch {
            conn.incoming.collect { m ->
                when (m) {
                    is Msg.FileReady -> replies.remove(m.id + ":ready")?.complete(m)
                    is Msg.FileDone -> replies.remove(m.id + ":done")?.complete(m)
                    is Msg.FileError -> {
                        replies.remove(m.id + ":ready")?.complete(m); replies.remove(m.id + ":done")?.complete(m)
                        receivers.remove(m.id)?.let { closeQuietly(it); it.discard() }
                        fail(m.id)
                    }
                    is Msg.FileOffer -> accept(m)
                    is Msg.FileEnd -> finishIncoming(m)
                    else -> {}
                }
            }
        }
        // Resume unfinished uploads whenever the connection (re)opens; drop half-received files when it closes
        scope.launch {
            conn.state.collect { st ->
                if (st is AgentState.Online) pending.all().forEach(::enqueue)
                else receivers.keys.toList().forEach { id -> receivers.remove(id)?.let { closeQuietly(it); it.discard() }; fail(id) }
            }
        }
    }

    fun send(uris: List<Uri>) {
        for (u in uris) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(u, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val (name, size) = meta(u)
            if (size < 0) { transfers.add(0, Transfer(UUID.randomUUID().toString(), name, true, status = Transfer.Status.FAILED)); continue }
            val p = PendingUpload(UUID.randomUUID().toString(), u.toString(), name, size)
            pending.add(p)
            enqueue(p)
        }
    }

    fun close() { conn.binaryListener = null }

    private fun enqueue(p: PendingUpload) { if (queued.add(p.id)) queue.trySend(p) }

    private fun update(id: String, f: (Transfer) -> Transfer) {
        val i = transfers.indexOfFirst { it.id == id }
        if (i >= 0) transfers[i] = f(transfers[i])
    }

    private fun fail(id: String) = update(id) { it.copy(status = Transfer.Status.FAILED) }

    /** Name and size; size is -1 if the provider can't tell us (then we don't start). */
    private fun meta(u: Uri): Pair<String, Long> {
        var name = u.lastPathSegment ?: "file"; var size = -1L
        runCatching {
            ctx.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it }
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        }
        if (size <= 0) size = runCatching { ctx.contentResolver.openAssetFileDescriptor(u, "r")?.use { it.length } }.getOrNull() ?: -1L
        return name to size
    }

    private suspend fun upload(p: PendingUpload) {
        conn.state.first { it is AgentState.Online }
        if (transfers.none { it.id == p.id }) transfers.add(0, Transfer(p.id, p.name, outgoing = true))
        else update(p.id) { it.copy(status = Transfer.Status.ACTIVE) }
        val ready = CompletableDeferred<Msg>().also { replies[p.id + ":ready"] = it }
        val done = CompletableDeferred<Msg>().also { replies[p.id + ":done"] = it }
        val uri = Uri.parse(p.uri)
        try {
            withContext(Dispatchers.IO) {
                FileSender(UUID.fromString(p.id), p.name, p.size, { ctx.contentResolver.openInputStream(uri)!! }, conn::send, conn::sendBinary,
                    onProgress = { pr -> scope.launch { update(p.id) { it.copy(progress = pr) } } },
                    backlog = conn::backlog,
                ).run {
                    when (val r = withTimeoutOrNull(15_000) { ready.await() }) {
                        is Msg.FileReady -> r.offset
                        else -> throw IllegalStateException("not ready")
                    }
                }
            }
            // The host verifies the whole file's hash before answering; allow time for big files
            val r = withTimeoutOrNull(120_000 + p.size / 20_000) { done.await() }
            if (r is Msg.FileDone) {
                pending.remove(p.id)
                update(p.id) { it.copy(progress = 1f, status = Transfer.Status.DONE) }
            } else {
                if (r is Msg.FileError) pending.remove(p.id) // the host refused it; resuming won't help
                fail(p.id)
            }
        } catch (e: SecurityException) {
            pending.remove(p.id); fail(p.id) // permission to read the file is gone
        } catch (e: Exception) {
            fail(p.id) // kept in `pending`: resumes on the next connection
        } finally {
            replies.remove(p.id + ":ready"); replies.remove(p.id + ":done")
        }
    }

    private fun accept(m: Msg.FileOffer) {
        val target = openDownload(m.name) ?: run { conn.send(Msg.FileReject(m.id)); return }
        val (out, publish, discard) = target
        receivers[m.id] = Incoming(FileReceiver(UUID.fromString(m.id), m.size, out), out, publish, discard)
        transfers.add(0, Transfer(m.id, m.name, outgoing = false))
        conn.send(Msg.FileAccept(m.id, 0))
    }

    private fun finishIncoming(m: Msg.FileEnd) {
        val inc = receivers.remove(m.id) ?: return
        val ok = inc.receiver.finish(m.sha256)
        closeQuietly(inc)
        if (ok) inc.publish() else inc.discard()
        update(m.id) { it.copy(progress = 1f, status = if (ok) Transfer.Status.DONE else Transfer.Status.FAILED) }
    }

    private fun closeQuietly(inc: Incoming) = runCatching { inc.out.close() }

    /** Opens Downloads/PC Remote/<name> (app-specific Downloads before Android 10, no permission needed). */
    private fun openDownload(name: String): Triple<OutputStream, () -> Unit, () -> Unit>? = runCatching {
        val safe = name.substringAfterLast('/').substringAfterLast('\\').ifBlank { "file" }
        if (Build.VERSION.SDK_INT >= 29) {
            val cv = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, safe)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/PC Remote")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)!!
            Triple(ctx.contentResolver.openOutputStream(uri)!!,
                { ctx.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null); Unit },
                { ctx.contentResolver.delete(uri, null, null); Unit })
        } else {
            val dir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "PC Remote").apply { mkdirs() }
            var f = File(dir, safe); var n = 1
            while (f.exists()) f = File(dir, "${safe.substringBeforeLast('.')} (${n++})" + (if ('.' in safe) "." + safe.substringAfterLast('.') else ""))
            val file = f
            Triple(FileOutputStream(file), {}, { file.delete(); Unit })
        }
    }.getOrNull()
}
